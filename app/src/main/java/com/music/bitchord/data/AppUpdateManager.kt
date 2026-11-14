package com.music.bitchord.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import com.music.bitchord.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.File

/**
 * The one place the app decides whether it is allowed to run.
 *
 * V-Tune ships sideloaded from GitHub Releases, so a release is the only thing
 * that can announce a newer build. This manager polls that release, keeps the
 * result in a process-wide [MutableStateFlow], and owns the entire update round
 * trip — download, install, and the verification that finally lifts the gate.
 *
 * It is deliberately not tied to a screen. [com.music.bitchord.ui.components.MandatoryUpdateHost]
 * draws the gate once at the activity root, and every navigation only asks the
 * manager to re-check; no screen decides for itself whether an update matters.
 *
 * A release is *mandatory*: there is no "later" path. The gate stays until the
 * package manager reports a version that is no longer older than the release.
 */
object AppUpdateManager {

    data class UpdateInfo(
        val version: String,
        val releaseUrl: String,
        val apkUrl: String?,
        /** The release's own Markdown body, shown as this update's "what's new". */
        val notes: String?,
    )

    /** Where this update's APK download currently stands. */
    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(val fraction: Float) : DownloadState
        data class Ready(val file: File) : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    /** A navigation-triggered check will not hit the network again until this much time has passed. */
    private const val CHECK_TTL_MS = 5 * 60 * 1000L

    /** After a failed check, leave a little room to retry rather than waiting the full TTL. */
    private const val FAILURE_RETRY_MS = 60 * 1000L

    /** A foreground app still watches for a release that lands mid-session. */
    const val FOREGROUND_CHECK_INTERVAL_MS = 30 * 60 * 1000L

    private const val CACHE_SUBDIR = "updates"

    private val json = Json { ignoreUnknownKeys = true }

    private val _pending = MutableStateFlow<UpdateInfo?>(null)

    /** Non-null means the update gate is up. */
    val pending = _pending.asStateFlow()

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download = _download.asStateFlow()

    private var lastCheckedAt = 0L
    private var lastCheckFailed = false
    private var releaseEtag: String? = null

    /**
     * Checks the latest release now. Called at every cold start with [force],
     * and after each navigation without it, where the TTL keeps a fast tab
     * switch from turning into a request.
     *
     * Installation verification runs first: after the installer returns, a
     * package manager entry that is no longer older than the release clears the
     * gate before any network work is attempted.
     */
    suspend fun check(context: Context, force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        verifyInstalled(app)

        val now = SystemClock.elapsedRealtime()
        val ttl = if (lastCheckFailed) FAILURE_RETRY_MS else CHECK_TTL_MS
        if (!force && now - lastCheckedAt in 0 until ttl) return@withContext _pending.value

        runCatching {
            val request = Request.Builder()
                .url(BuildConfig.UPDATE_RELEASE_API_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "V-Tune-3.O/${BuildConfig.VERSION_NAME}")
                .apply { releaseEtag?.let { header("If-None-Match", it) } }
                .build()

            Http.client.newCall(request).execute().use { response ->
                if (response.code == 304) {
                    lastCheckedAt = SystemClock.elapsedRealtime()
                    lastCheckFailed = false
                    return@withContext _pending.value
                }
                if (!response.isSuccessful) {
                    lastCheckedAt = SystemClock.elapsedRealtime()
                    lastCheckFailed = true
                    return@withContext _pending.value
                }

                val body = response.body?.string().orEmpty()
                val release = json.parseToJsonElement(body) as? JsonObject
                    ?: return@withContext _pending.value
                val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull
                    ?: return@withContext _pending.value
                val url = release["html_url"]?.jsonPrimitive?.contentOrNull
                    ?: return@withContext _pending.value
                val latest = tag.removePrefix("v").trim()
                val installed = installedVersionName(app) ?: BuildConfig.VERSION_NAME

                releaseEtag = response.header("ETag") ?: releaseEtag
                lastCheckedAt = SystemClock.elapsedRealtime()
                lastCheckFailed = false

                if (isNewer(latest, installed)) {
                    val current = _pending.value
                    if (current?.version != latest) _download.value = DownloadState.Idle
                    _pending.value = UpdateInfo(
                        version = latest,
                        releaseUrl = url,
                        apkUrl = apkAssetUrl(release),
                        notes = release["body"]?.jsonPrimitive?.contentOrNull,
                    )
                    adoptCachedApk(app, latest)
                } else {
                    // The installed package has caught up. The gate only ever
                    // closes on this comparison, never on a button press.
                    _pending.value = null
                    _download.value = DownloadState.Idle
                }
                _pending.value
            }
        }.getOrElse {
            lastCheckedAt = SystemClock.elapsedRealtime()
            lastCheckFailed = true
            _pending.value
        }
    }

    /** A navigation happened; re-check only if the TTL has run out. */
    suspend fun checkIfStale(context: Context) = check(context, force = false)

    /**
     * Clears the gate only when the package manager now reports a build that is
     * no longer older than the pending release. Returns true when it did.
     *
     * This is what makes a cancelled install harmless: the installed version is
     * unchanged, so the gate stays up and the user can try again.
     */
    fun verifyInstalled(context: Context): Boolean {
        val update = _pending.value ?: return true
        val installed = installedVersionName(context) ?: return false
        if (isNewer(update.version, installed)) return false
        _pending.value = null
        _download.value = DownloadState.Idle
        return true
    }

    /**
     * One release carries the phone app and the TV app, each as a universal
     * APK plus per-ABI splits:
     *
     *     V-Tune-v1.0.1.apk     V-Tune-v1.0.1_arm64-v8a.apk     ...
     *     V-TuneTV-v1.0.1.apk   V-TuneTV-v1.0.1_arm64-v8a.apk   ...
     *
     * so this app's APKs are the ones named with its own
     * [BuildConfig.UPDATE_APK_PREFIX]. The universal build (no `_ABI` suffix)
     * is taken first; failing that, the split for this device's ABI. A release
     * with neither leaves [UpdateInfo.apkUrl] null and the gate falls back to
     * the release page rather than pretending it can install anything.
     */
    private fun apkAssetUrl(release: JsonObject): String? = runCatching {
        val apks = release["assets"]?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { asset ->
                val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val ours = name.startsWith(BuildConfig.UPDATE_APK_PREFIX, ignoreCase = true) &&
                    name.endsWith(".apk", ignoreCase = true) &&
                    asset["state"]?.jsonPrimitive?.contentOrNull == "uploaded"
                if (ours) name.removeSuffix(".apk").removeSuffix(".APK") to url else null
            }
            .orEmpty()
        apks.firstOrNull { (name, _) -> '_' !in name }?.second
            ?: Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
                apks.firstOrNull { (name, _) -> name.endsWith("_$abi") }?.second
            }
    }.getOrNull()

    /**
     * Streams the pending release's APK into the app cache, reporting progress
     * through [download]. The file is validated against the release's version
     * before it is handed to the installer.
     */
    suspend fun downloadApk(context: Context): Unit = withContext(Dispatchers.IO) {
        val info = _pending.value ?: return@withContext
        val url = info.apkUrl ?: return@withContext
        _download.value = DownloadState.Downloading(0f)

        runCatching {
            val dir = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
            val target = File(dir, "vtune-${info.version}.apk")
            // Drop leftovers from an older release.
            dir.listFiles()?.filter { it != target }?.forEach { it.delete() }

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "V-Tune-3.O/${BuildConfig.VERSION_NAME}")
                .build()
            Http.client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
                val body = response.body ?: error("Empty download body")
                val total = body.contentLength().takeIf { it > 0 }

                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var readTotal = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            readTotal += read
                            total?.let {
                                _download.value =
                                    DownloadState.Downloading((readTotal.toFloat() / it).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }

            check(isThisAppAtLeast(context, target, info.version)) {
                "The downloaded file is not V-Tune ${info.version}."
            }
            _download.value = DownloadState.Ready(target)
        }.onFailure { error ->
            _download.value = DownloadState.Failed(error.message ?: "Download failed")
        }
    }

    /** Hands a downloaded APK to the system installer. */
    fun installApk(context: Context, file: File): Boolean {
        if (!file.isFile || file.length() == 0L) {
            _download.value = DownloadState.Failed("The downloaded update is missing. Download it again.")
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val opened = runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            // The settings screen is the install permission step, not a way
            // out of the gate: returning re-verifies and leaves the card up.
            if (opened) return true
        }

        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(
                Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                    .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                    .addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_ACTIVITY_NEW_TASK,
                    ),
            )
            true
        }.getOrElse { error ->
            _download.value = DownloadState.Failed(
                error.message ?: "Couldn't open the installer.",
            )
            false
        }
    }

    /** Opens the release page when the release carries no installable APK. */
    fun openReleasePage(context: Context) {
        val url = _pending.value?.releaseUrl ?: return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** A cached APK from an earlier attempt (or a killed process) is still a valid head start. */
    private fun adoptCachedApk(context: Context, version: String) {
        if (_download.value is DownloadState.Downloading) return
        val file = File(File(context.cacheDir, CACHE_SUBDIR), "vtune-$version.apk")
        if (isThisAppAtLeast(context, file, version)) {
            _download.value = DownloadState.Ready(file)
        } else if (_download.value is DownloadState.Ready) {
            _download.value = DownloadState.Idle
        }
    }

    @Suppress("DEPRECATION")
    private fun installedVersionName(context: Context): String? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()

    /**
     * The file really is this app, and really is the build the release promised.
     *
     * The package name is checked first so a mis-named asset can never be handed
     * to the installer. The version test is "not older than" rather than "equal
     * to": a release tagged `v1.0.1` whose APK reports `1.0.1` is the same build,
     * and a build that carries a local suffix should still install instead of
     * leaving the gate up forever over a string mismatch.
     */
    @Suppress("DEPRECATION")
    private fun isThisAppAtLeast(context: Context, file: File, version: String): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        val info = runCatching {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }.getOrNull() ?: return false
        if (info.packageName != context.packageName) return false
        val apkVersion = info.versionName ?: return false
        return !isNewer(version, apkVersion)
    }

    /** A version split into its numeric dotted parts and whether it carries a "-suffix" (e.g. "-beta2"). */
    private data class ParsedVersion(val parts: List<Int>, val isPreRelease: Boolean)

    private fun parseVersion(raw: String): ParsedVersion {
        val dash = raw.indexOf('-')
        val base = if (dash >= 0) raw.substring(0, dash) else raw
        return ParsedVersion(base.split(".").map { it.toIntOrNull() ?: 0 }, dash >= 0)
    }

    /**
     * Numeric, dot-separated comparison — "1.10" outranks "1.9" — with one
     * extra rule: a "-betaN" build is older than a plain release of the same
     * numbers, so a beta tester is nudged onto the release it was testing.
     */
    private fun isNewer(latest: String, current: String): Boolean {
        val l = parseVersion(latest)
        val c = parseVersion(current)
        for (i in 0 until maxOf(l.parts.size, c.parts.size)) {
            val a = l.parts.getOrElse(i) { 0 }
            val b = c.parts.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return c.isPreRelease && !l.isPreRelease
    }
}
