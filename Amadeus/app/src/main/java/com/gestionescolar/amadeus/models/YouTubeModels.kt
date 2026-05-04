package com.gestionescolar.amadeus.models

import java.io.File

data class YouTubeResult(
    val videoId: String,
    val title: String,
    val thumbnail: String,
    val channelTitle: String,
    val duration: String = ""
) {
    val url: String get() = "https://www.youtube.com/watch?v=$videoId"
}

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress: Float) : DownloadState()
    object Extracting : DownloadState()
    data class Success(val filePath: String) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

sealed class TranscriptionState {
    data class Downloading(val progress: Float, val message: String) : TranscriptionState()
    data class Analyzing(val progress: Float, val message: String) : TranscriptionState()
    data class Generating(val message: String) : TranscriptionState()
    data class Done(val outputFile: File) : TranscriptionState()
    data class Error(val message: String) : TranscriptionState()
}

data class DetectedNote(
    val midiPitch: Int,
    val startTimeMs: Long,
    val durationMs: Long,
    val amplitude: Float = 1.0f
)
