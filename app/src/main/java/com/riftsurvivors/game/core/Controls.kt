package com.riftsurvivors.game.core

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import kotlin.math.atan2

/**
 * On-screen MOBA controls:
 *   - a floating movement joystick (appears wherever the left thumb presses)
 *   - four skill pads (bottom-right). Press a pad and DRAG to aim a skillshot,
 *     RELEASE to cast in that direction. A quick tap casts toward the nearest
 *     enemy (auto-aim).
 *
 * Touch handling tracks pointers by id so movement and several skill aims can
 * happen simultaneously.
 */
class Controls {

    class Pad(val index: Int, var x: Float, var y: Float, var r: Float)
    class Aim(val index: Int, val id: Int, val sx: Float, val sy: Float) {
        var dirX = 1f
        var dirY = 0f
        var dragging = false
    }
    class CastReq(val index: Int, val dragged: Boolean, val dirX: Float, val dirY: Float)

    var viewW = 0f
    var viewH = 0f
    var joyMaxR = 90f

    // movement joystick
    private var joyActive = false
    private var joyId = -1
    private var joyOx = 0f
    private var joyOy = 0f
    private var joyKx = 0f
    private var joyKy = 0f
    var moveX = 0f; private set
    var moveY = 0f; private set
    var moveMag = 0f; private set

    val pads = ArrayList<Pad>()
    private val aims = arrayOfNulls<Aim>(4)
    val pendingCasts = ArrayList<CastReq>()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val arc = RectF()

    fun layout(w: Int, h: Int) {
        viewW = w.toFloat(); viewH = h.toFloat()
        val mn = minOf(viewW, viewH)
        val r = Mathx.clamp(mn * 0.082f, 40f, 72f)
        val pad = r + mn * 0.05f
        val d = r * 1.55f
        val clx = viewW - pad - d
        val cly = viewH - pad - d
        pads.clear()
        // diamond: Q left, W top, E right, R bottom
        pads.add(Pad(0, clx - d, cly, r))
        pads.add(Pad(1, clx, cly - d, r))
        pads.add(Pad(2, clx + d, cly, r))
        pads.add(Pad(3, clx, cly + d, r * 1.04f))
        joyMaxR = Mathx.clamp(mn * 0.13f, 64f, 120f)
    }

    private fun padAt(x: Float, y: Float): Pad? {
        for (p in pads) {
            val rr = p.r * 1.3f
            if (Mathx.dist2(x, y, p.x, p.y) <= rr * rr) return p
        }
        return null
    }

    fun onTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                begin(e.getPointerId(idx), e.getX(idx), e.getY(idx))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    move(e.getPointerId(i), e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = e.actionIndex
                end(e.getPointerId(idx))
            }
            MotionEvent.ACTION_CANCEL -> {
                joyActive = false; joyId = -1; resetMove()
                for (i in aims.indices) aims[i] = null
            }
        }
    }

    private fun begin(id: Int, x: Float, y: Float) {
        val pad = padAt(x, y)
        if (pad != null && aims[pad.index] == null) {
            aims[pad.index] = Aim(pad.index, id, x, y)
            return
        }
        if (!joyActive) {
            joyActive = true; joyId = id
            joyOx = x; joyOy = y; joyKx = x; joyKy = y
            resetMove()
        }
    }

    private fun move(id: Int, x: Float, y: Float) {
        if (joyActive && joyId == id) {
            var dx = x - joyOx; var dy = y - joyOy
            val l = Mathx.len(dx, dy)
            if (l > joyMaxR) { dx = dx / l * joyMaxR; dy = dy / l * joyMaxR }
            joyKx = joyOx + dx; joyKy = joyOy + dy
            val out = FloatArray(2)
            Mathx.normInto(dx, dy, out)
            moveX = out[0]; moveY = out[1]
            moveMag = Mathx.clamp(Mathx.len(dx, dy) / joyMaxR, 0f, 1f)
            if (l < 6f) moveMag = 0f
            return
        }
        for (a in aims) {
            if (a != null && a.id == id) {
                val dx = x - a.sx; val dy = y - a.sy
                if (Mathx.len(dx, dy) > 16f) {
                    a.dragging = true
                    val out = FloatArray(2)
                    Mathx.normInto(dx, dy, out)
                    a.dirX = out[0]; a.dirY = out[1]
                }
            }
        }
    }

    private fun end(id: Int) {
        if (joyActive && joyId == id) {
            joyActive = false; joyId = -1; resetMove()
            return
        }
        for (i in aims.indices) {
            val a = aims[i]
            if (a != null && a.id == id) {
                pendingCasts.add(CastReq(a.index, a.dragging, a.dirX, a.dirY))
                aims[i] = null
            }
        }
    }

    private fun resetMove() { moveX = 0f; moveY = 0f; moveMag = 0f }

    fun activeAims(): List<Aim> = aims.filterNotNull()

    /** Draw the joystick + skill pads. Called after world rendering. */
    fun draw(c: Canvas, world: World) {
        // Joystick
        if (joyActive) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.color = Color.argb(70, 255, 255, 255)
            c.drawCircle(joyOx, joyOy, joyMaxR, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(120, 200, 220, 255)
            c.drawCircle(joyKx, joyKy, joyMaxR * 0.42f, paint)
        }

        // Skill pads
        for (p in pads) {
            val st = world.skills[p.index]
            val def = Skills.defs[p.index]
            val cx = p.x; val cy = p.y; val r = p.r

            // base disc
            paint.style = Paint.Style.FILL
            if (!st.unlocked) {
                paint.color = Color.argb(120, 40, 44, 60)
            } else {
                paint.color = Color.argb(150, 24, 28, 44)
            }
            c.drawCircle(cx, cy, r, paint)

            // colored ring
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5f
            paint.color = if (st.unlocked) def.color else Color.argb(120, 90, 90, 110)
            c.drawCircle(cx, cy, r, paint)

            if (st.unlocked) {
                // cooldown sweep (dark wedge shrinking clockwise)
                if (st.cd > 0f) {
                    val frac = (st.cd / (def.baseCooldown * st.cdMul * world.player.cdMul)).coerceIn(0f, 1f)
                    paint.style = Paint.Style.FILL
                    paint.color = Color.argb(150, 8, 10, 18)
                    arc.set(cx - r, cy - r, cx + r, cy + r)
                    c.drawArc(arc, -90f, 360f * frac, true, paint)
                }
                // key letter
                text.color = Color.WHITE
                text.textSize = r * 0.85f
                c.drawText(def.key, cx, cy + r * 0.30f, text)
            } else {
                // lock glyph (simple)
                text.color = Color.argb(180, 200, 200, 220)
                text.textSize = r * 0.7f
                c.drawText("🔒", cx, cy + r * 0.25f, text)
                text.textSize = r * 0.34f
                c.drawText("Lv?", cx, cy + r * 0.78f, text)
            }
        }
    }

    @Suppress("unused")
    private fun angleDeg(x: Float, y: Float) = Math.toDegrees(atan2(y, x).toDouble()).toFloat()
}
