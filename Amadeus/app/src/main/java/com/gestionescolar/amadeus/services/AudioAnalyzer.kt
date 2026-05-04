package com.gestionescolar.amadeus.services

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.content.Context
import com.gestionescolar.amadeus.models.PitchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sqrt

class AudioAnalyzer(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioFocusManager = AudioFocusManager(context)
    private var focusRequest: AudioFocusRequest? = null

    private val sampleRate = 44100
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ) * 2

    private var currentInstrumentRange: IntRange = 0..20000 // Default: human hearing
    private var noiseThreshold: Float = 0.05f

    private var isListening = false

    fun setNoiseThreshold(threshold: Float) {
        noiseThreshold = threshold
    }

    fun stopListening() {
        isListening = false
        // ✅ Usar AudioFocusManager para abandonar foco
        audioFocusManager.abandonFocus()
        // Fallback por compatibilidad
        focusRequest?.let { 
            try {
                audioManager.abandonAudioFocusRequest(it)
            } catch (e: Exception) {
                android.util.Log.e("AudioAnalyzer", "Error abandoning focus", e)
            }
            focusRequest = null
        }
    }

    @SuppressLint("MissingPermission")
    fun startListening(): Flow<PitchResult> = flow {
        // ✅ Usar AudioFocusManager para solicitar foco de forma robusta
        audioFocusManager.requestRecordingFocus()

        isListening = true
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            return@flow
        }

        val buffer = ShortArray(bufferSize)
        try {
            audioRecord.startRecording()

            while (isListening) {
                val readSize = audioRecord.read(buffer, 0, bufferSize)
                if (readSize > 0) {
                    val amplitude = calculateAmplitude(buffer, readSize)
                    val frequency = detectPitch(buffer, readSize)
                    
                    if (amplitude > noiseThreshold && frequency.toInt() in currentInstrumentRange) {
                        val noteInfo = frequencyToNote(frequency)
                        emit(PitchResult(frequency, noteInfo.first, noteInfo.second, amplitude))
                    } else {
                        emit(PitchResult(0f, "---", 0f, amplitude))
                    }
                }
                delay(50)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            audioRecord.release()
        }
    }.flowOn(Dispatchers.IO)

    fun setInstrument(instrument: String) {
        currentInstrumentRange = when (instrument) {
            "Guitarra" -> 80..1200
            "Violín" -> 190..3500
            "Canto", "Voz" -> 80..1500
            "Batería" -> 30..15000
            else -> 0..20000
        }
    }

    fun calculateAmplitude(buffer: ShortArray, size: Int): Float {
        var sum = 0.0
        for (i in 0 until size) {
            sum += (buffer[i] * buffer[i]).toDouble()
        }
        return sqrt(sum / size).toFloat() / 32768f
    }

    fun detectPitch(buffer: ShortArray, size: Int): Float {
        var lastSample = 0
        var crossings = 0
        for (i in 0 until size) {
            val sample = buffer[i].toInt()
            if ((lastSample > 0 && sample <= 0) || (lastSample < 0 && sample >= 0)) {
                crossings++
            }
            lastSample = sample
        }
        return (crossings.toFloat() * sampleRate / (2 * size))
    }

    private fun frequencyToNote(frequency: Float): Pair<String, Float> {
        val notes = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val n = (12 * (log2(frequency / 440.0) / log2(2.0)) + 69).toInt()
        val noteName = notes[n % 12] + (n / 12 - 1)
        val expectedFreq = 440.0 * 2.0.pow((n - 69).toDouble() / 12.0)
        val centsOff = (1200 * log2((frequency / expectedFreq)) / log2(2.0)).toFloat()
        return Pair(noteName, centsOff)
    }
}
