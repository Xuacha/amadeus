package com.gestionescolar.amadeus.services

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Gestor robusto de Audio Focus para recording y playback.
 * Resuelve: problema de speakers deshabilitados en ColorOS (OPPO)
 *
 * Garantiza:
 *  - Thread-safe
 *  - Limpieza apropiada en todos los paths
 *  - Compatible con ColorOS y AOSP
 *  - No interfiere con otras apps
 */
class AudioFocusManager(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var isHoldingFocus = false
    private val lock = Any()
    
    /**
     * Solicitar foco para grabación (MIC input).
     * Usa AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK para permitir que otras apps reduzcan volumen.
     *
     * @return true si el foco fue concedido exitosamente
     */
    fun requestRecordingFocus(): Boolean {
        return synchronized(lock) {
            try {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { focusChange ->
                        onAudioFocusChanged(focusChange)
                    }
                    .build()
                
                val result = audioManager.requestAudioFocus(focusRequest!!)
                isHoldingFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                Log.d("AudioFocus", "Recording focus requested: $isHoldingFocus (result=$result)")
                return isHoldingFocus
            } catch (e: Exception) {
                Log.e("AudioFocus", "Error requesting recording focus", e)
                return false
            }
        }
    }
    
    /**
     * Solicitar foco para reproducción (Audio playback).
     * Usa AUDIOFOCUS_GAIN para tener prioridad total mientras se reproduce.
     *
     * @return true si el foco fue concedido exitosamente
     */
    fun requestPlaybackFocus(): Boolean {
        return synchronized(lock) {
            try {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { focusChange ->
                        onAudioFocusChanged(focusChange)
                    }
                    .build()
                
                val result = audioManager.requestAudioFocus(focusRequest!!)
                isHoldingFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                Log.d("AudioFocus", "Playback focus requested: $isHoldingFocus (result=$result)")
                return isHoldingFocus
            } catch (e: Exception) {
                Log.e("AudioFocus", "Error requesting playback focus", e)
                return false
            }
        }
    }
    
    /**
     * Abandonar foco de audio.
     * CRÍTICO: se llama en onPause(), onDestroy(), stopListening(), etc.
     * Garantiza que otras apps (Facebook, TikTok, etc) puedan reproducir sonido.
     */
    fun abandonFocus() {
        synchronized(lock) {
            try {
                focusRequest?.let {
                    val result = audioManager.abandonAudioFocusRequest(it)
                    Log.d("AudioFocus", "✅ Audio focus abandoned (result=$result)")
                }
                focusRequest = null
                isHoldingFocus = false
            } catch (e: Exception) {
                Log.e("AudioFocus", "Error abandoning focus", e)
            }
        }
    }
    
    /**
     * Verificar si esta app está reteniendo foco de audio.
     *
     * @return true si está reteniendo, false en caso contrario
     */
    fun isHoldingFocus(): Boolean = synchronized(lock) {
        isHoldingFocus
    }
    
    /**
     * Callback que se ejecuta cuando cambia el estado de foco de audio.
     * Importante para apps que graban/reproducen continuamente.
     *
     * @param focusChange Tipo de cambio (GAIN, LOSS, LOSS_TRANSIENT, etc)
     */
    private fun onAudioFocusChanged(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d("AudioFocus", "📢 AUDIOFOCUS_GAIN - Regained audio focus")
                isHoldingFocus = true
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.w("AudioFocus", "🔇 AUDIOFOCUS_LOSS - Lost audio focus to another app")
                isHoldingFocus = false
                // En una app de reproducción, aquí se pausaría
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.w("AudioFocus", "⏸️ AUDIOFOCUS_LOSS_TRANSIENT - Temporary loss (e.g., incoming call)")
                isHoldingFocus = false
                // Pausa temporal, reanuda cuando regresa
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.w("AudioFocus", "🔉 AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK - Reduce volume")
                // Reduce volumen pero sigue reproduciendo
            }
        }
    }
}
