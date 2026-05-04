package com.gestionescolar.amadeus.services

import org.schabi.newpipe.extractor.NewPipe
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import android.util.Log

/**
 * Singleton para garantizar inicialización única y thread-safe de NewPipe.
 * Resuelve: race condition + error "downloader is null"
 *
 * Uso:
 *   - En AmadeusApplication.onCreate(): NewPipeInitializer.initialize()
 *   - En AudioDownloadManager: NewPipeInitializer.ensureInitialized()
 */
object NewPipeInitializer {
    private val lock = Any()
    private var initialized = false
    private var initException: Exception? = null
    
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
    
    /**
     * Inicializar NewPipe de forma sincrónica y thread-safe.
     * Puede llamarse múltiples veces sin problemas (es idempotente).
     *
     * @throws Exception si la inicialización falla
     */
    fun initialize() {
        synchronized(lock) {
            if (initialized) {
                if (initException != null) {
                    Log.e("NewPipeInit", "Previous init failed, rethrowing", initException)
                    throw initException!!
                }
                Log.d("NewPipeInit", "Already initialized, returning")
                return
            }
            
            try {
                Log.d("NewPipeInit", "Initializing NewPipe with custom Downloader...")
                NewPipe.init(NewPipeDownloader(okHttpClient))
                initialized = true
                Log.d("NewPipeInit", "✅ NewPipe initialized successfully")
            } catch (e: Exception) {
                initException = e
                Log.e("NewPipeInit", "❌ Failed to initialize NewPipe: ${e.message}", e)
                throw e
            }
        }
    }
    
    /**
     * Garantizar que NewPipe esté inicializado.
     * Intenta inicializar si no está hecho, retorna true/false según éxito.
     *
     * @return true si NewPipe está disponible, false si falló
     */
    fun ensureInitialized(): Boolean = synchronized(lock) {
        if (initialized && initException == null) {
            Log.d("NewPipeInit", "NewPipe already initialized and ready")
            return true
        }
        
        if (initException != null) {
            Log.e("NewPipeInit", "Previous init failed, cannot recover")
            return false
        }
        
        return try {
            initialize()
            true
        } catch (e: Exception) {
            Log.e("NewPipeInit", "ensureInitialized() failed: ${e.message}")
            false
        }
    }
    
    /**
     * Obtener el OkHttpClient singleton compartido.
     * Útil si otros servicios necesitan el mismo cliente.
     */
    fun getHttpClient(): OkHttpClient = okHttpClient
}
