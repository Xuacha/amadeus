package com.gestionescolar.amadeus.services

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

class SoundFontManager(private val context: Context) {
    private var cachedBase64: String? = null

    /**
     * Como el archivo ahora está bundleado en assets, siempre se considera "descargado".
     */
    fun isDownloaded(): Boolean = true

    /**
     * Mantenemos la firma por compatibilidad, pero ya no descarga nada.
     * Simplemente pre-carga el SoundFont en memoria.
     */
    suspend fun downloadSoundFont(onProgress: (Float) -> Unit = {}) {
        getSoundFontBase64() // Forzar carga inicial
        onProgress(1.0f)
    }

    /**
     * Lee el archivo sonivox.sf2 desde assets y lo convierte a Base64.
     * Utiliza caché en memoria para evitar lecturas repetidas de un archivo de ~30MB.
     */
    suspend fun getSoundFontBase64(): String? {
        if (cachedBase64 != null) return cachedBase64

        return withContext(Dispatchers.IO) {
            try {
                context.assets.open("soundfont/sonivox.sf2").use { inputStream ->
                    val bytes = inputStream.readBytes()
                    cachedBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    cachedBase64
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * Versión sincrónica para ser llamada desde la interfaz JavaScript.
     * Si no está en caché, retornará null (por eso se recomienda llamar a downloadSoundFont
     * o getSoundFontBase64 suspendido durante el splash o inicialización).
     */
    fun getSoundFontBase64Sync(): String? {
        return cachedBase64
    }
}
