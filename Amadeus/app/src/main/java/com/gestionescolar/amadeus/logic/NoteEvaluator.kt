package com.gestionescolar.amadeus.logic

import com.gestionescolar.amadeus.models.ActiveNote

sealed class NoteResult {
    object Correct : NoteResult()
    object Wrong : NoteResult()
    object NoInput : NoteResult()
}

class NoteEvaluator {
    private val notes = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    
    // Mapeo de enarmónicos comunes
    private val enharmonics = mapOf(
        "DB" to "C#", "EB" to "D#", "GB" to "F#", "AB" to "G#", "BB" to "A#",
        "C#" to "C#", "D#" to "D#", "F#" to "F#", "G#" to "G#", "A#" to "A#"
    )

    fun evaluate(expected: ActiveNote, detected: String): NoteResult {
        if (detected == "---" || detected.isEmpty()) return NoteResult.NoInput
        
        val expectedNoteName = notes[expected.midiPitch % 12]
        
        // Normalizar entrada (quitar octava y pasar a mayúsculas)
        val normalizedDetected = detected.replace(Regex("[0-9]"), "").uppercase()
        val finalDetected = enharmonics[normalizedDetected] ?: normalizedDetected

        return if (expectedNoteName == finalDetected) {
            NoteResult.Correct
        } else {
            NoteResult.Wrong
        }
    }
}
