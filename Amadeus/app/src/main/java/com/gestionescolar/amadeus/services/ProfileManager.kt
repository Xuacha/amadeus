package com.gestionescolar.amadeus.services

import android.content.Context
import android.content.SharedPreferences

class ProfileManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("amadeus_profile", Context.MODE_PRIVATE)

    fun getXP(): Int = prefs.getInt("total_xp", 0)

    fun addXP(amount: Int) {
        val currentXP = getXP()
        prefs.edit().putInt("total_xp", currentXP + amount).apply()
    }

    fun getLevel(): Int {
        val xp = getXP()
        return (xp / 1000) + 1
    }

    fun getXPInCurrentLevel(): Int {
        return getXP() % 1000
    }

    fun saveSessionResult(songName: String, score: Int) {
        val history = getHistory().toMutableList()
        history.add(0, "$songName - $score%")
        // Keep only last 10 entries
        val limitedHistory = if (history.size > 10) history.take(10) else history
        prefs.edit().putStringSet("history", limitedHistory.toSet()).apply()
        
        // Add XP based on score
        addXP(score * 2) 
    }

    fun getHistory(): List<String> {
        return prefs.getStringSet("history", emptySet())?.toList() ?: emptyList()
    }

    fun getNoiseThreshold(): Float = prefs.getFloat("noise_threshold", 0.05f)

    fun setNoiseThreshold(threshold: Float) {
        prefs.edit().putFloat("noise_threshold", threshold).apply()
    }

    fun getSelectedInstrument(): String = prefs.getString("selected_instrument", "Guitarra") ?: "Guitarra"

    fun setSelectedInstrument(instrument: String) {
        prefs.edit().putString("selected_instrument", instrument).apply()
    }
}
