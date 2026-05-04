package com.gestionescolar.amadeus.services

import com.gestionescolar.amadeus.models.DetectedNote
import java.io.File
import java.io.FileOutputStream
import java.util.*

class MusicXmlGenerator {

    fun generate(
        notes: List<DetectedNote>,
        title: String,
        tempo: Int = 120
    ): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<!DOCTYPE score-partwise PUBLIC \"-//Recordare//DTD MusicXML 3.1 Partwise//EN\" \"http://www.musicxml.org/dtds/partwise.dtd\">\n")
        sb.append("<score-partwise version=\"3.1\">\n")
        sb.append("  <work><work-title>$title</work-title></work>\n")
        sb.append("  <identification>\n")
        sb.append("    <creator type=\"composer\">Amadeus AI</creator>\n")
        sb.append("  </identification>\n")
        sb.append("  <part-list>\n")
        sb.append("    <score-part id=\"P1\"><part-name>Piano</part-name></score-part>\n")
        sb.append("  </part-list>\n")
        sb.append("  <part id=\"P1\">\n")
        sb.append("    <measure number=\"1\">\n")
        sb.append("      <attributes>\n")
        sb.append("        <divisions>256</divisions>\n")
        sb.append("        <key><fifths>0</fifths></key>\n")
        sb.append("        <time><beats>4</beats><beat-type>4</beat-type></time>\n")
        sb.append("        <clef><sign>G</sign><line>2</line></clef>\n")
        sb.append("      </attributes>\n")
        sb.append("      <direction placement=\"above\">\n")
        sb.append("        <direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>$tempo</per-minute></metronome></direction-type>\n")
        sb.append("      </direction>\n")

        // Lógica de exportación de notas con soporte para ligaduras (ties)
        var currentTimeMs = 0L
        val quarterNoteDurationMs = 60000L / tempo
        val divisionsPerQuarter = 256
        val measureDurationMs = quarterNoteDurationMs * 4
        var measureCount = 1

        notes.sortedBy { it.startTimeMs }.forEach { note ->
            // Manejar silencios si hay un hueco
            if (note.startTimeMs > currentTimeMs + 10) { // Tolerancia de 10ms
                var restRemaining = note.startTimeMs - currentTimeMs
                while (restRemaining > 0) {
                    val msInCurrentMeasure = measureDurationMs - (currentTimeMs % measureDurationMs)
                    val restInThisMeasure = minOf(restRemaining, msInCurrentMeasure)
                    
                    addRest(sb, restInThisMeasure, divisionsPerQuarter, quarterNoteDurationMs)
                    
                    currentTimeMs += restInThisMeasure
                    restRemaining -= restInThisMeasure
                    
                    if (currentTimeMs % measureDurationMs == 0L && restRemaining > 0) {
                        sb.append("    </measure>\n")
                        measureCount++
                        sb.append("    <measure number=\"$measureCount\">\n")
                    }
                }
            }

            // Procesar la nota (posiblemente dividida entre compases)
            var noteRemainingMs = note.durationMs
            var isFirstPart = true

            while (noteRemainingMs > 0) {
                val msInCurrentMeasure = measureDurationMs - (currentTimeMs % measureDurationMs)
                val durationInThisMeasure = minOf(noteRemainingMs, msInCurrentMeasure)
                
                val isLastPart = durationInThisMeasure == noteRemainingMs

                addNote(
                    sb, 
                    note.midiPitch, 
                    durationInThisMeasure, 
                    divisionsPerQuarter, 
                    quarterNoteDurationMs,
                    isStartTie = !isLastPart,
                    isStopTie = !isFirstPart
                )

                currentTimeMs += durationInThisMeasure
                noteRemainingMs -= durationInThisMeasure
                isFirstPart = false

                if (currentTimeMs % measureDurationMs == 0L && (noteRemainingMs > 0 || notes.any { it.startTimeMs > currentTimeMs })) {
                    sb.append("    </measure>\n")
                    measureCount++
                    sb.append("    <measure number=\"$measureCount\">\n")
                }
            }
        }

        if (!sb.endsWith("</measure>\n")) {
            sb.append("    </measure>\n")
        }
        sb.append("  </part>\n")
        sb.append("</score-partwise>\n")

        return sb.toString()
    }

    private fun addNote(
        sb: StringBuilder,
        midiPitch: Int,
        durationMs: Long,
        divisions: Int,
        quarterMs: Long,
        isStartTie: Boolean,
        isStopTie: Boolean
    ) {
        val durationInDivisions = (durationMs * divisions / quarterMs).toInt()
        if (durationInDivisions <= 0) return

        sb.append("      <note>\n")
        sb.append("        <pitch>\n")
        sb.append("          <step>${midiToStep(midiPitch)}</step>\n")
        if (isSharp(midiPitch)) sb.append("          <alter>1</alter>\n")
        sb.append("          <octave>${(midiPitch / 12) - 1}</octave>\n")
        sb.append("        </pitch>\n")
        sb.append("        <duration>$durationInDivisions</duration>\n")
        if (isStopTie) sb.append("        <tie type=\"stop\"/>\n")
        if (isStartTie) sb.append("        <tie type=\"start\"/>\n")
        sb.append("        <type>${getNoteType(durationInDivisions)}</type>\n")
        if (isStopTie || isStartTie) {
            sb.append("        <notations>\n")
            if (isStopTie) sb.append("          <tied type=\"stop\"/>\n")
            if (isStartTie) sb.append("          <tied type=\"start\"/>\n")
            sb.append("        </notations>\n")
        }
        sb.append("      </note>\n")
    }

    private fun addRest(sb: StringBuilder, durationMs: Long, divisions: Int, quarterMs: Long) {
        val div = (durationMs * divisions / quarterMs).toInt()
        if (div > 0) {
            sb.append("      <note>\n")
            sb.append("        <rest/>\n")
            sb.append("        <duration>$div</duration>\n")
            sb.append("        <type>${getNoteType(div)}</type>\n")
            sb.append("      </note>\n")
        }
    }

    private fun midiToStep(midi: Int): String {
        return when (midi % 12) {
            0 -> "C"
            1 -> "C" // C#
            2 -> "D"
            3 -> "D" // D#
            4 -> "E"
            5 -> "F"
            6 -> "F" // F#
            7 -> "G"
            8 -> "G" // G#
            9 -> "A"
            10 -> "A" // A#
            11 -> "B"
            else -> "C"
        }
    }

    private fun isSharp(midi: Int): Boolean {
        val mod = midi % 12
        return mod == 1 || mod == 3 || mod == 6 || mod == 8 || mod == 10
    }

    private fun getNoteType(divisions: Int): String {
        return when {
            divisions >= 1024 -> "whole"
            divisions >= 512 -> "half"
            divisions >= 256 -> "quarter"
            divisions >= 128 -> "eighth"
            divisions >= 64 -> "16th"
            else -> "32nd"
        }
    }
}
