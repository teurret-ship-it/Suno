package com.riftsurvivors.game.core

import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random

/** Small math helpers shared across the game. */
object Mathx {
    fun clamp(v: Float, lo: Float, hi: Float) = if (v < lo) lo else if (v > hi) hi else v
    fun clampI(v: Int, lo: Int, hi: Int) = if (v < lo) lo else if (v > hi) hi else v
    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    fun rand(min: Float, max: Float) = min + Random.nextFloat() * (max - min)
    fun randInt(min: Int, max: Int) = Random.nextInt(min, max + 1)
    fun chance(p: Float) = Random.nextFloat() < p

    fun dist(ax: Float, ay: Float, bx: Float, by: Float) = hypot(ax - bx, ay - by)
    fun dist2(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx; val dy = ay - by; return dx * dx + dy * dy
    }
    fun len(x: Float, y: Float) = hypot(x, y)

    /** Distance from point P to segment AB — used for line skillshots and dashes. */
    fun distPointSeg(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val abx = bx - ax; val aby = by - ay
        val apx = px - ax; val apy = py - ay
        val abLen2 = abx * abx + aby * aby
        var t = if (abLen2 < 1e-6f) 0f else (apx * abx + apy * aby) / abLen2
        if (t < 0f) t = 0f else if (t > 1f) t = 1f
        val cx = ax + abx * t; val cy = ay + aby * t
        return hypot(px - cx, py - cy)
    }

    /** Returns a unit vector (nx,ny) into [out]; falls back to (1,0). */
    fun normInto(x: Float, y: Float, out: FloatArray) {
        val l = sqrt(x * x + y * y)
        if (l < 1e-6f) { out[0] = 1f; out[1] = 0f } else { out[0] = x / l; out[1] = y / l }
    }

    fun fmtTime(sec: Float): String {
        val total = sec.toInt()
        val m = total / 60
        val s = total % 60
        return (if (m < 10) "0" else "") + m + ":" + (if (s < 10) "0" else "") + s
    }
}
