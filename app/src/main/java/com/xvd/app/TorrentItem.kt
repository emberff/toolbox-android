package com.xvd.app

data class TorrentItem(
    val infoHash: String,
    val name: String,
    val savePath: String,
    val source: String,
    val sourceData: String,
    val state: String,
    val progress: Float,
    val downloadRate: Long,
    val uploadRate: Long,
    val totalDone: Long,
    val totalWanted: Long,
    val numPeers: Int,
    val numSeeds: Int,
    val hasVideo: Boolean = false,
    val hasMetadata: Boolean = false
) {
    val isFinished: Boolean get() = state == "已完成" || state == "做种中"

    fun progressPercent(): Int = ((progress.coerceIn(0f, 1f)) * 100).toInt()
}

data class TorrentFileInfo(
    val index: Int,
    val path: String,
    val name: String,
    val size: Long
)

fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1073741824 -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824.0)
        bytes >= 1048576 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
