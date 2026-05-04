package com.gestionescolar.amadeus.models

data class NoteModel(
    val name: String,
    val frequency: Float,
    val octave: Int
)

data class ActiveNote(
    val midiPitch: Int,
    val startTime: Double
)

data class PitchResult(
    val frequency: Float,
    val noteName: String,
    val centsOff: Float,
    val amplitude: Float
)
