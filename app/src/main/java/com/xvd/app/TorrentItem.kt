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
    val hasVideo: Boolean = false
) {
    val isFinished: Boolean get() = state == "已完成" || state == "做种中"

    fun progressPercent(): Int = ((progress.coerceIn(0f, 1f)) * 100).toInt()
}
