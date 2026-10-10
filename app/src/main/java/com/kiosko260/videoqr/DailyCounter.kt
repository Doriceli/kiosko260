package com.kiosko260.videoqr

import android.content.Context
import java.time.LocalDate

/** Cuenta los videos editados por día (se reinicia solo al cambiar la fecha). */
class DailyCounter(context: Context) {
    private val prefs = context.getSharedPreferences("contador", Context.MODE_PRIVATE)

    private fun key(date: LocalDate = LocalDate.now()) = "videos_$date"

    fun today(): Int = prefs.getInt(key(), 0)

    fun increment(): Int {
        val count = today() + 1
        prefs.edit().putInt(key(), count).apply()
        return count
    }
}
