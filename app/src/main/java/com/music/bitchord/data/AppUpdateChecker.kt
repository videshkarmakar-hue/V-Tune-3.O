package com.music.bitchord.data

// App update checking removed — no GitHub release checks or auto-updates.
object AppUpdateChecker {
    // No-op placeholders so the API still compiles.
    sealed class UpdateInfo {}
    object Available : UpdateInfo()
    enum class DownloadState { Downloading, Ready, Failed }
    object Download : DownloadState()
    data class UpdateInfoException(val message: String) : Exception()
    suspend fun check() = Unit
    suspend fun downloadApk(context: Context) = Unit
    suspend fun cancelDownload() = Unit
    suspend fun installApk(context: Context, file: java.io.File) = Unit
    suspend fun resetDownload() = Unit
    suspend fun clearCache(context: Context) = Unit
    val available: StateFlow<UpdateInfo?> = emptyFlow()
    val download: StateFlow<DownloadState> = emptyFlow()
}