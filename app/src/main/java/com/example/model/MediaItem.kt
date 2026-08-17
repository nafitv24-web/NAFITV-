package com.example.model

enum class MediaType {
    LIVE_EVENT,
    LIVE_TV,
    MOVIE,
    SERIES
}

data class StreamServer(
    val name: String,
    val url: String
)

data class MediaItem(
    val id: String,
    val title: String,
    val category: String,
    val type: MediaType,
    val streamUrl: String,
    val backupUrl: String? = null,
    val servers: List<StreamServer> = emptyList(),
    val logoUrl: String? = null,
    val description: String? = null,
    val isLive: Boolean = false,
    val eventTime: String? = null,
    val tournament: String? = null,
    val status: String = "Live Now", // "LIVE NOW", "UPCOMING", "Finished"
    val team1: String? = null,
    val team2: String? = null,
    val team1Logo: String? = null,
    val team2Logo: String? = null,
    val matchTimeFormatted: String? = null, // e.g. "06:30 AM, Aug 13"
    val countdownTargetSeconds: Long? = null, // Remaining seconds or timestamp for live ticking countdown
    val score1: String? = null,
    val score2: String? = null,
    val quality: String = "HD",
    val rating: String? = null,
    val year: String? = null,
    val country: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    val cookie: String? = null,
    val origin: String? = null,
    val customHeaders: Map<String, String>? = null,
    val drmScheme: String? = null,
    val drmLicenseUrl: String? = null,
    val drmLicenseKey: String? = null,
    val drmHeaders: Map<String, String>? = null,
    val manifestType: String? = null
) {
    // Helper to get all available server URLs
    fun getAllServers(): List<StreamServer> {
        val list = mutableListOf<StreamServer>()
        if (servers.isNotEmpty()) {
            list.addAll(servers.filter { it.url.isNotBlank() })
        }
        if (list.none { it.url == streamUrl } && streamUrl.isNotBlank()) {
            list.add(0, StreamServer("সার্ভার ১ (Main)", streamUrl))
        }
        if (!backupUrl.isNullOrBlank() && list.none { it.url == backupUrl }) {
            list.add(StreamServer("সার্ভার ২ (Backup)", backupUrl))
        }
        return list.ifEmpty {
            if (streamUrl.isNotBlank()) listOf(StreamServer("সার্ভার ১", streamUrl)) else emptyList()
        }
    }
}

data class PlaylistInfo(
    val id: String,
    val title: String,
    val url: String,
    val logoUrl: String? = null,
    val description: String? = null,
    val channelCount: Int = 0
)

data class AppUpdateInfo(
    val versionCode: Int = 1,
    val versionName: String = "1.0",
    val downloadUrl: String = "",
    val releaseNotes: String = "",
    val isForceUpdate: Boolean = false,
    val minSupportedVersionCode: Int = 1,
    val apkSize: String = "",
    val releaseDate: String = ""
)

