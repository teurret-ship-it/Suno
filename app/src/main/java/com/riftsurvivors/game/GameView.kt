package com.riftsurvivors.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.riftsurvivors.game.core.World

/**
 * SurfaceView that drives a fixed-effort game loop on a background thread.
 * All gameplay/render logic lives in [World]; this class only owns the
 * surface, the loop thread, and forwarding of touch events.
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private val world = World(context)
    private var thread: GameThread? = null

    init {
        holder.addCallback(this)
        isFocusable = true
        keepScreenOn = true
    }

    /* ---------- Surface lifecycle ---------- */

    override fun surfaceCreated(holder: SurfaceHolder) {
        startThread()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        world.onResize(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopThread()
    }

    private fun startThread() {
        if (thread == null) {
            thread = GameThread(holder, world).also {
                it.running = true
                it.start()
            }
        }
    }

    private fun stopThread() {
        thread?.let {
            it.running = false
            var retry = true
            while (retry) {
                try {
                    it.join()
                    retry = false
                } catch (_: InterruptedException) { /* retry */ }
            }
        }
        thread = null
    }

    fun pause() {
        world.onAppPause()
    }

    fun resume() {
        world.onAppResume()
    }

    /* ---------- Input ---------- */

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        world.onTouch(event)
        return true
    }

    /**
     * Dedicated loop thread. Computes a clamped delta-time each frame and
     * renders into the locked canvas.
     */
    private class GameThread(
        private val surfaceHolder: SurfaceHolder,
        private val world: World
    ) : Thread() {
        @Volatile var running = false
        private var lastNanos = 0L

        override fun run() {
            lastNanos = System.nanoTime()
            while (running) {
                val now = System.nanoTime()
                var dt = (now - lastNanos) / 1_000_000_000f
                lastNanos = now
                // Clamp to avoid huge steps after a stall (spiral of death).
                if (dt > 0.05f) dt = 0.05f

                world.update(dt)

                var canvas: Canvas? = null
                try {
                    canvas = surfaceHolder.lockCanvas()
                    if (canvas != null) {
                        synchronized(surfaceHolder) {
                            world.draw(canvas)
                        }
                    }
                } finally {
                    if (canvas != null) {
                        try {
                            surfaceHolder.unlockCanvasAndPost(canvas)
                        } catch (_: Exception) { /* surface gone */ }
                    }
                }

                // Aim for ~60 FPS; yield the rest of the frame budget.
                val frameMs = (System.nanoTime() - now) / 1_000_000L
                val sleep = 16L - frameMs
                if (sleep > 0) {
                    try { sleep(sleep) } catch (_: InterruptedException) {}
                }
            }
        }
    }
}
