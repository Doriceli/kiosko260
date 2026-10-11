package com.kiosko260.videoqr

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import kotlin.math.abs

/**
 * Curvas de velocidad. Cada punto es (posición en el video original de 0 a 1, velocidad).
 * Entre puntos la velocidad cambia de forma suave (ease in/out), como los "speed ramps" de CapCut.
 */
enum class SpeedEffect(val label: String, private val points: List<Pair<Float, Float>>) {
    /** Rápido → cámara lenta → rápido: el clásico de TikTok/Reels. */
    SPEED_RAMP(
        "⚡ Speed ramp (rápido · lento · rápido)",
        listOf(0f to 1.6f, 0.22f to 1.6f, 0.34f to 0.3f, 0.66f to 0.3f, 0.78f to 1.6f, 1f to 1.6f),
    ),

    /** Empieza normal y va frenando hasta quedar en cámara lenta. */
    PROGRESSIVE(
        "🌊 Cámara lenta progresiva",
        listOf(0f to 1f, 0.25f to 1f, 0.6f to 0.3f, 1f to 0.25f),
    ),

    /** Normal y, de golpe pero suave, un momento en cámara lenta al final. */
    SLOW_TRANSITION(
        "✨ Transición a cámara lenta",
        listOf(0f to 1.2f, 0.45f to 1.2f, 0.55f to 0.35f, 1f to 0.35f),
    ),

    /** Toda la toma en cámara lenta. */
    CONSTANT(
        "🐢 Cámara lenta constante",
        listOf(0f to 0.5f, 1f to 0.5f),
    );

    /** Velocidad en la posición [fraction] (0..1) del video original. */
    fun speedAt(fraction: Float): Float {
        val f = fraction.coerceIn(0f, 1f)
        val next = points.indexOfFirst { it.first >= f }.coerceAtLeast(1)
        val (x0, v0) = points[next - 1]
        val (x1, v1) = points[next]
        if (x1 <= x0) return v1
        val t = ((f - x0) / (x1 - x0)).coerceIn(0f, 1f)
        val eased = t * t * (3 - 2 * t) // smoothstep
        return v0 + (v1 - v0) * eased
    }

    @OptIn(UnstableApi::class)
    fun toSpeedProvider(durationUs: Long): RampSpeedProvider = RampSpeedProvider(this, durationUs)
}

/**
 * Convierte la curva en tramos cortos de velocidad constante (lo que entiende Media3).
 * Tramos de [STEP_US] (≈ 1 fotograma a 30 fps) hacen que la rampa se vea fluida.
 */
@OptIn(UnstableApi::class)
class RampSpeedProvider(effect: SpeedEffect, durationUs: Long) : SpeedProvider {

    private val starts: LongArray
    private val speeds: FloatArray

    init {
        val startList = mutableListOf<Long>()
        val speedList = mutableListOf<Float>()
        val total = durationUs.coerceAtLeast(STEP_US)
        var t = 0L
        while (t < total) {
            val middle = (t + STEP_US / 2).coerceAtMost(total)
            val speed = effect.speedAt(middle.toFloat() / total)
            // Se juntan los tramos con la misma velocidad.
            if (speedList.isEmpty() || abs(speedList.last() - speed) > 0.005f) {
                startList += t
                speedList += speed
            }
            t += STEP_US
        }
        starts = startList.toLongArray()
        speeds = speedList.toFloatArray()
    }

    private fun indexAt(timeUs: Long): Int {
        var index = starts.binarySearch(timeUs)
        if (index < 0) index = -index - 2
        return index.coerceIn(0, starts.lastIndex)
    }

    override fun getSpeed(timeUs: Long): Float = speeds[indexAt(timeUs)]

    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
        val next = indexAt(timeUs) + 1
        return if (next <= starts.lastIndex) starts[next] else C.TIME_UNSET
    }

    /** Duración del video ya con la velocidad aplicada. */
    fun outputDurationUs(inputDurationUs: Long): Long {
        var total = 0.0
        for (i in starts.indices) {
            val end = if (i < starts.lastIndex) starts[i + 1] else inputDurationUs
            if (end > starts[i]) total += (end - starts[i]) / speeds[i].toDouble()
        }
        return total.toLong()
    }

    private companion object {
        const val STEP_US = 33_000L
    }
}
