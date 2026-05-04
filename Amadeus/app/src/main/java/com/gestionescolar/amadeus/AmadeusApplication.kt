package com.gestionescolar.amadeus

import android.app.Application
import android.util.Log
import com.gestionescolar.amadeus.services.NewPipeInitializer

class AmadeusApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        try {
            // ✅ Inicializar NewPipe Extractor usando singleton thread-safe
            // Esto resuelve race conditions y el error "downloader is null"
            NewPipeInitializer.initialize()
            Log.d("AmadeusApp", "✅ Application initialized successfully")
        } catch (e: Exception) {
            Log.e("AmadeusApp", "⚠️ Failed to initialize NewPipe in Application", e)
            // No crash completamente - AudioDownloadManager puede reintentar
        }
    }
}
