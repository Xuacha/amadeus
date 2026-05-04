package com.gestionescolar.amadeus.logic

import com.gestionescolar.amadeus.services.AudioAnalyzer
import com.gestionescolar.amadeus.services.MidiService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import android.media.midi.MidiDeviceInfo

class NoteDetectionManager(
    private val audioAnalyzer: AudioAnalyzer,
    private val midiService: MidiService,
    private val scope: CoroutineScope
) {
    private val _detectedNoteFlow = MutableSharedFlow<String>(replay = 0)
    val detectedNoteFlow: SharedFlow<String> = _detectedNoteFlow.asSharedFlow()

    private var audioJob: Job? = null
    
    private val notes = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    fun start(isMidi: Boolean = false) {
        if (!isMidi) {
            audioJob?.cancel()
            audioJob = scope.launch {
                audioAnalyzer.startListening().collect { result ->
                    if (result.frequency > 0) {
                        _detectedNoteFlow.emit(result.noteName)
                    }
                }
            }
        } else {
            midiService.setListener(object : MidiService.MidiEventListener {
                override fun onNoteOn(note: Int, velocity: Int) {
                    val noteName = notes[note % 12] + (note / 12 - 1)
                    scope.launch { _detectedNoteFlow.emit(noteName) }
                }
                override fun onNoteOff(note: Int) {}
                override fun onDeviceAdded(device: MidiDeviceInfo) {}
                override fun onDeviceRemoved(device: MidiDeviceInfo) {}
            })
        }
    }

    fun stop() {
        audioJob?.cancel()
        audioAnalyzer.stopListening()
        // MidiService listener is kept or cleared if necessary
    }
}
