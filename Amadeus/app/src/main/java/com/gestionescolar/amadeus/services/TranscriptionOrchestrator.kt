package com.gestionescolar.amadeus.services

import android.content.Context
import android.net.Uri
import com.gestionescolar.amadeus.models.YouTubeResult
import com.gestionescolar.amadeus.models.TranscriptionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TranscriptionOrchestrator(
    private val context: Context,
    private val downloadManager: AudioDownloadManager,
    private val transcriptionEngine: MusicTranscriptionEngine,
    private val xmlGenerator: MusicXmlGenerator
) {

    suspend fun transcribeFromYouTube(
        result: YouTubeResult,
        onProgress: (TranscriptionState) -> Unit
    ): File = withContext(Dispatchers.IO) {
        try {
            // Verificar Caché
            val cacheDir = File(context.filesDir, "transcripciones")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            
            val cachedFile = cacheDir.listFiles()?.find { it.name.startsWith(result.videoId) && it.name.endsWith(".xml") }
            if (cachedFile != null) {
                onProgress(TranscriptionState.Done(cachedFile))
                return@withContext cachedFile
            }

            // 1. Descarga
            onProgress(TranscriptionState.Downloading(0f, "Extrayendo señal de audio..."))
            val audioFile = downloadManager.downloadAudio(result.videoId) { progress, message ->
                onProgress(TranscriptionState.Downloading(progress, message))
            }

            // 2. Análisis
            onProgress(TranscriptionState.Analyzing(0f, "Analizando frecuencias..."))
            val notes = transcriptionEngine.transcribe(audioFile) { progress ->
                onProgress(TranscriptionState.Analyzing(progress, "Detectando melodía... ${(progress * 100).toInt()}%"))
            }

            // 3. Generación XML
            onProgress(TranscriptionState.Generating("Generando partitura interactiva..."))
            val xmlContent = xmlGenerator.generate(notes, result.title)
            
            val timestamp = System.currentTimeMillis()
            val outputFile = File(cacheDir, "${result.videoId}_$timestamp.xml")
            outputFile.writeText(xmlContent)

            onProgress(TranscriptionState.Done(outputFile))
            outputFile
        } catch (e: Exception) {
            onProgress(TranscriptionState.Error(e.message ?: "Error desconocido"))
            throw e
        }
    }
}
