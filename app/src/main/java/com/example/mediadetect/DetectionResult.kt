package com.example.mediadetect

data class DetectionResult(
    val mediaUrl: String?,
    val streamType: String, // "mp4" | "hls" | "dash" | "unknown"
    val drm: Boolean,
    val reason: String?,
    val evidence: Evidence
) {
    data class Evidence(
        val score: Int,
        val scoreItems: List<String>,
        val segmentsSeen: Int,
        val cadenceMsAvg: Long,
        val player: Player?,
        val headers: Map<String, String>
    )
    data class Player(
        val currentSrc: String?,
        val paused: Boolean,
        val readyState: Int,
        val inViewport: Boolean,
        val duration: Double,
        val width: Int,
        val height: Int
    )
}
