package com.music.bitchord.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import com.music.bitchord.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

    /** What a check started from Settings found. */
    sealed interface ManualCheck {
        /** The installed build is the latest published release. */
        data object UpToDate : ManualCheck

        /** A release is pending, and the gate is already up over the app. */
        data class Available(val version: String) : ManualCheck

        /** GitHub could not be reached, so the check learned nothing. */
        data object Unreachable : ManualCheck
    }

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

    /** The release that gated the last session, so force-stopping the app is not an escape hatch. */
    private const val UPDATE_PREFS = "vtune_update"
    private const val KEY_PENDING_RELEASE = "pending_release"
    private const val KEY_PENDING_AT = "pending_at"

    /**
     * A gate restored from disk is only honoured while it is still plausible —
     * a release from weeks ago that can no longer be confirmed is more likely
     * a deleted tag than a build worth locking the app for.
     */
    private const val MAX_PERSISTED_AGE_MS = 14L * 24 * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }

    private val _pending = MutableStateFlow<UpdateInfo?>(null)

    /** Non-null means the update gate is up. */
    val pending = _pending.asStateFlow()

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download = _download.asStateFlow()

    private var lastCheckedAt = 0L
    @Volatile
    private var lastCheckFailed = false
    private var releaseEtag: String? = null

    /** How long a failed check waits before trying again; widened when GitHub says so. */
    private var failureBackoffMs = FAILURE_RETRY_MS

    /** Set only while the user is away on the "install unknown apps" screen. */
    private var awaitingInstallPermission = false

    /** Serialises concurrent checks; see [checkLocked]. */
    private val checkMutex = Mutex()

    /** Owns the download so it outlives whichever screen happens to be composed. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    /**
     * Checks the latest release now. Called at every cold start with [force],
     * and after each navigation without it, where the TTL keeps a fast tab
     * switch from turning into a request.
     *
     * Installation verification runs first: after the installer returns, a
     * package manager entry that is no longer older than the release clears the
     * gate before any network work is attempted.
     */
    suspend fun check(context: Context, force: Boolean = false): UpdateInfo? =
        checkMutex.withLock { checkLocked(context, force) }

    /**
     * Cold start, the resume loop and a navigation can all ask at once. The
     * lock means one request, and the TTL check under it means the later
     * callers read that result instead of repeating the work.
     */
    private suspend fun checkLocked(context: Context, force: Boolean): UpdateInfo? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        verifyInstalled(app)
        // Killed and relaunched offline, the app is still gated: the last
        // confirmed release is restored before the network is even tried.
        if (_pending.value == null) restorePending(app)

        val now = SystemClock.elapsedRealtime()
        val ttl = if (lastCheckFailed) failureBackoffMs else CHECK_TTL_MS
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
                    failureBackoffMs = FAILURE_RETRY_MS
                    return@withContext _pending.value
                }
                if (!response.isSuccessful) {
                    lastCheckedAt = SystemClock.elapsedRealtime()
                    lastCheckFailed = true
                    // GitHub answers an exhausted anonymous quota with 403/429
                    // and says when it will reset. Retrying a minute later would
                    // only spend more of it, so wait the time it asked for.
                    failureBackoffMs = retryDelayMs(response)
                    return@withContext _pending.value
                }

                val body = response.body.string()
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
                failureBackoffMs = FAILURE_RETRY_MS

                if (isNewer(latest, installed)) {
                    val current = _pending.value
                    if (current?.version != latest) _download.value = DownloadState.Idle
                    val info = UpdateInfo(
                        version = latest,
                        releaseUrl = url,
                        apkUrl = apkAssetUrl(release),
                        notes = release["body"]?.jsonPrimitive?.contentOrNull,
                    )
                    _pending.value = info
                    storePending(app, info)
                    adoptCachedApk(app, latest)
                } else {
                    // The installed package has caught up. The gate only ever
                    // closes on this comparison, never on a button press.
                    _pending.value = null
                    _download.value = DownloadState.Idle
                    storePending(app, null)
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
     * A check the user asked for from Settings.
     *
     * It bypasses the cache window so the answer is about right now, and it
     * reports the outcome instead of leaving it implicit in [pending], which is
     * null both when the app is current and when the request failed.
     */
    suspend fun checkForUpdates(context: Context): ManualCheck {
        val update = check(context, force = true)
        return when {
            update != null -> ManualCheck.Available(update.version)
            lastCheckFailed -> ManualCheck.Unreachable
            else -> ManualCheck.UpToDate
        }
    }

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
        storePending(context, null)
        return true
    }

    /**
     * How long to leave GitHub alone after a failed check, in milliseconds.
     *
     * `Retry-After` carries seconds and `X-RateLimit-Reset` an epoch timestamp;
     * either is clamped to half an hour so a skewed header cannot park the
     * updater for a day.
     */
    private fun retryDelayMs(response: okhttp3.Response): Long {
        val retryAfter = response.header("Retry-After")?.toLongOrNull()?.times(1000L)
        val resetAt = response.header("X-RateLimit-Reset")?.toLongOrNull()
            ?.let { it * 1000L - System.currentTimeMillis() }
        val asked = listOfNotNull(retryAfter, resetAt).maxOrNull() ?: FAILURE_RETRY_MS
        return asked.coerceIn(FAILURE_RETRY_MS, 30 * 60 * 1000L)
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
     *
     * Runs on the manager's own scope rather than a composable's: the gate is
     * shown over a dozen different screens, and turning the phone must not
     * cancel a download that is halfway through.
     */
    fun startDownload(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (downloadJob?.isActive == true) return
            downloadJob = scope.launch { downloadApk(app) }
        }
    }

    private suspend fun downloadApk(context: Context): Unit = withContext(Dispatchers.IO) {
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
                val body = response.body
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
            // A newer release may have been published while this one was on the
            // wire. Installing it would satisfy the wrong gate, so the file is
            // dropped and the card falls back to Update Now for the new one.
            if (_pending.value?.version != info.version) {
                target.delete()
                _download.value = DownloadState.Idle
                return@withContext
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
            awaitingInstallPermission = true
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
        awaitingInstallPermission = false

        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            // ACTION_INSTALL_PACKAGE is deprecated in favour of PackageInstaller
            // sessions, which are for apps that stream their own bytes. This is
            // a sideloaded APK already on disk, and the intent still lands in
            // the system installer on every supported release.
            @Suppress("DEPRECATION")
            val installer = Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK,
                )
            context.startActivity(installer)
            true
        }.getOrElse { error ->
            _download.value = DownloadState.Failed(
                error.message ?: "Couldn't open the installer.",
            )
            false
        }
    }

    /**
     * Picks the install back up after the "install unknown apps" screen.
     *
     * The permission step is the only part of the flow Android insists a human
     * perform; once it is granted there is no reason to make them find the
     * button again, so returning to the app hands the APK straight to the
     * installer. It runs nothing unless [installApk] actually detoured there,
     * so coming back from the installer itself cannot loop.
     */
    fun resumeInstallIfWaiting(context: Context) {
        if (!awaitingInstallPermission) return
        awaitingInstallPermission = false
        val ready = _download.value as? DownloadState.Ready ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            // Still refused: leave the card up and let the button explain again.
            return
        }
        installApk(context, ready.file)
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

    /**
     * Remembers (or forgets) the release that is gating this install, so the
     * gate survives the process being killed. A user who force-stops the app to
     * get past the popup comes back to it.
     */
    private fun storePending(context: Context, info: UpdateInfo?) {
        runCatching {
            val prefs = context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
            if (info == null) {
                prefs.edit().remove(KEY_PENDING_RELEASE).remove(KEY_PENDING_AT).apply()
                return
            }
            val stored = buildJsonObject {
                put("version", info.version)
                put("releaseUrl", info.releaseUrl)
                info.apkUrl?.let { put("apkUrl", it) }
                info.notes?.let { put("notes", it) }
            }.toString()
            prefs.edit()
                .putString(KEY_PENDING_RELEASE, stored)
                .putLong(KEY_PENDING_AT, System.currentTimeMillis())
                .apply()
        }
    }

    /** Re-establishes the gate from the last confirmed release, still subject to the installed version. */
    private fun restorePending(context: Context) {
        runCatching {
            val prefs = context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
            val stored = prefs.getString(KEY_PENDING_RELEASE, null) ?: return
            val storedAt = prefs.getLong(KEY_PENDING_AT, 0L)
            if (System.currentTimeMillis() - storedAt > MAX_PERSISTED_AGE_MS) {
                prefs.edit().remove(KEY_PENDING_RELEASE).remove(KEY_PENDING_AT).apply()
                return
            }
            val release = json.parseToJsonElement(stored) as? JsonObject ?: return
            val version = release["version"]?.jsonPrimitive?.contentOrNull ?: return
            val url = release["releaseUrl"]?.jsonPrimitive?.contentOrNull ?: return
            val installed = installedVersionName(context) ?: BuildConfig.VERSION_NAME
            if (!isNewer(version, installed)) {
                prefs.edit().remove(KEY_PENDING_RELEASE).remove(KEY_PENDING_AT).apply()
                return
            }
            val info = UpdateInfo(
                version = version,
                releaseUrl = url,
                apkUrl = release["apkUrl"]?.jsonPrimitive?.contentOrNull,
                notes = release["notes"]?.jsonPrimitive?.contentOrNull,
            )
            _pending.value = info
            adoptCachedApk(context, version)
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

    /**
     * Reads `1.10.2`, `v1.10.2`, `1.10.2+27` and `1.10.2-beta3` alike.
     *
     * Build metadata after a `+` never affects precedence, so a versionName that
     * carries one still compares equal to the release tag it was built from —
     * otherwise `1.0.1+27` would rank below `1.0.1` and gate a build that is
     * already current. A segment that isn't purely numeric is read up to its
     * first non-digit rather than discarded, and a `-suffix` marks a pre-release.
     */
    private fun parseVersion(raw: String): ParsedVersion {
        val withoutBuild = raw.substringBefore('+')
        val dash = withoutBuild.indexOf('-')
        val base = if (dash >= 0) withoutBuild.substring(0, dash) else withoutBuild
        val parts = base.split(".").map { segment ->
            segment.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
        return ParsedVersion(parts, dash >= 0)
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
