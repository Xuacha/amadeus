package com.gestionescolar.amadeus.services

import android.content.Context
import android.util.Log
import com.gestionescolar.amadeus.models.YouTubeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.YoutubeService
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class AudioDownloadManager(private val context: Context) {

    /**
     * Garantizar que NewPipe Extractor esté inicializado.
     * Usa el singleton NewPipeInitializer para evitar race conditions.
     * 
     * @throws Exception si no se puede inicializar
     */
    private fun ensureInitialized() {
        if (!NewPipeInitializer.ensureInitialized()) {
            throw Exception("No se pudo inicializar NewPipe Extractor. Verifica tu conexión a internet y reintenta.")
        }
        Log.d("AmadeusDownload", "✅ NewPipe Extractor ready")
    }

    suspend fun downloadAudio(
        videoId: String,
        onProgress: (Float, String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        try {
            // ✅ Garantizar inicialización antes de usar NewPipe
            ensureInitialized()
            
            val url = "https://www.youtube.com/watch?v=$videoId"
            Log.d("AmadeusDownload", "Iniciando extracción para: $url")
            
            val service = ServiceList.YouTube as YoutubeService
            val extractor = service.getStreamExtractor(url)
            extractor.fetchPage()

            val audioStream = extractor.audioStreams
                .filter { it.format == MediaFormat.M4A || it.format == MediaFormat.WEBM }
                .maxByOrNull { it.averageBitrate }
                ?: throw Exception("No se encontró un flujo de audio compatible")

            val outputFile = File(context.cacheDir, "audio_$videoId.m4a")
            
            val durationSeconds = extractor.length
            val isLongVideo = durationSeconds > 600 // Cap a 10 min
            
            val connection = URL(audioStream.content).openConnection() as HttpURLConnection
            if (isLongVideo) {
                val bitrate = if (audioStream.bitrate > 0) audioStream.bitrate else 128000
                val limitBytes = (600 * bitrate / 8).toLong()
                connection.setRequestProperty("Range", "bytes=0-$limitBytes")
            }

            connection.connect()
            
            val totalBytes = if (connection.contentLength > 0) {
                connection.contentLength.toLong()
            } else {
                0L 
            }
            
            connection.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(32768)
                    var bytesRead: Long = 0
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        if (totalBytes > 0) {
                            val progress = bytesRead.toFloat() / totalBytes
                            onProgress(progress, "Descargando audio... ${(progress * 100).toInt()}%")
                        } else {
                            onProgress(0.5f, "Descargando audio... (${(bytesRead / 1024 / 1024)} MB)")
                        }
                    }
                }
            }
            
            Log.d("AmadeusDownload", "Descarga completada: ${outputFile.absolutePath}")
            outputFile
        } catch (e: Exception) {
            Log.e("AmadeusDownload", "Error en downloadAudio", e)
            throw Exception("Fallo al extraer audio de YouTube: ${e.localizedMessage}")
        }
    }
}
