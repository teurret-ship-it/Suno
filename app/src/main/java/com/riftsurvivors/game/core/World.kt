package com.riftsurvivors.game.core

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The whole game: state machine, simulation, spawning, collisions, the
 * level-up flow, rendering and on-screen menus. Driven by [GameView]'s loop.
 */
class World(private val context: Context) {

    enum class State { MENU, PLAYING, LEVELUP, PAUSED, GAMEOVER }

    var state = State.MENU
        private set

    var viewW = 1080
    var viewH = 1920

    val player = Player()
    val skills = Skills.newStates()
    val controls = Controls()

    private val enemies = ArrayList<Enemy>()
    private val shots = ArrayList<Shot>()
    private val eshots = ArrayList<EnemyShot>()
    private val gems = ArrayList<Gem>()
    private val particles = ArrayList<Particle>()
    private val texts = ArrayList<FloatText>()
    private val hazards = ArrayList<Hazard>()
    private val rings = ArrayList<RingFx>()

    private var camX = 0f
    private var camY = 0f
    private var shake = 0f

    var gameTime = 0f
        private set
    private var spawnTimer = 0f

    private var pendingLevels = 0
    private var choices: List<Upgrade> = emptyList()

    // referenced by Upgrades (Q rank-up adds pierce)
    var boltExtraPierce = 0

    private var lastAimX = 1f
    private var lastAimY = 0f

    private var bestTime = 0f

    private val prefs = context.getSharedPreferences("rift_survivors", Context.MODE_PRIVATE)
    private val vibrator: Vibrator? =
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    // Paints reused across draws.
    private val pf = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ps = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val ptl = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val rf = RectF()

    init {
        bestTime = prefs.getFloat("best", 0f)
    }

    /* ============================ lifecycle ============================ */

    fun onResize(w: Int, h: Int) {
        viewW = w; viewH = h
        controls.layout(w, h)
    }

    fun onAppPause() {
        if (state == State.PLAYING) state = State.PAUSED
    }

    fun onAppResume() { /* stay paused until tapped */ }

    /* ============================ input ============================ */

    fun onTouch(e: MotionEvent) {
        when (state) {
            State.PLAYING -> {
                val a = e.actionMasked
                if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
                    val i = e.actionIndex
                    val x = e.getX(i); val y = e.getY(i)
                    if (pauseBtn().contains(x, y)) { state = State.PAUSED; return }
                }
                controls.onTouch(e)
            }
            State.MENU -> {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) startRun()
            }
            State.GAMEOVER -> {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) state = State.MENU
            }
            State.PAUSED -> {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    val x = e.getX(e.actionIndex); val y = e.getY(e.actionIndex)
                    when {
                        resumeBtn().contains(x, y) -> state = State.PLAYING
                        restartBtn().contains(x, y) -> startRun()
                    }
                }
            }
            State.LEVELUP -> {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    val x = e.getX(e.actionIndex); val y = e.getY(e.actionIndex)
                    val rects = cardRects()
                    for (i in rects.indices) {
                        if (rects[i].contains(x, y)) { chooseUpgrade(i); break }
                    }
                }
            }
        }
    }

    /* ============================ run setup ============================ */

    private fun startRun() {
        enemies.clear(); shots.clear(); eshots.clear(); gems.clear()
        particles.clear(); texts.clear(); hazards.clear(); rings.clear()

        // fresh player
        resetPlayer()
        skills.clear(); skills.addAll(Skills.newStates())
        boltExtraPierce = 0

        camX = 0f; camY = 0f; shake = 0f
        gameTime = 0f; spawnTimer = 0f
        pendingLevels = 0; choices = emptyList()
        lastAimX = 1f; lastAimY = 0f
        state = State.PLAYING
    }

    private fun resetPlayer() {
        val p = player
        p.x = 0f; p.y = 0f
        p.maxHp = 100f; p.hp = 100f
        p.regen = 0.6f
        p.level = 1; p.xp = 0f; p.xpToNext = 6f
        p.speed = 230f; p.moveMul = 1f
        p.pickupRadius = 95f; p.iframes = 0f
        p.autoDamage = 14f; p.autoRate = 1.6f; p.autoTimer = 0f
        p.autoSpeed = 620f; p.autoCount = 1; p.autoPierce = 0; p.autoRange = 520f
        p.skillDmgMul = 1f; p.cdMul = 1f; p.projSizeMul = 1f
        p.kills = 0
    }

    /* ============================ update ============================ */

    fun update(dt: Float) {
        if (state != State.PLAYING) {
            controls.pendingCasts.clear()
            updateParticlesOnly(dt)
            return
        }

        gameTime += dt

        // drain queued casts from controls
        if (controls.pendingCasts.isNotEmpty()) {
            for (c in controls.pendingCasts) {
                castSkill(c.index, c.dragged, c.dirX, c.dirY)
            }
            controls.pendingCasts.clear()
        }

        updatePlayer(dt)
        autoAttack(dt)
        spawnEnemies(dt)
        updateEnemies(dt)
        updateShots(dt)
        updateEnemyShots(dt)
        updateHazards(dt)
        updateGems(dt)
        updateParticles(dt)
        updateRings(dt)
        updateTexts(dt)
        sweepDeaths()

        // cooldowns
        for (st in skills) if (st.cd > 0f) st.cd = max(0f, st.cd - dt)

        if (shake > 0f) shake = max(0f, shake - dt * 26f)

        if (!player.alive()) gameOver()

        camX = player.x; camY = player.y
    }

    private fun updatePlayer(dt: Float) {
        var dx = controls.moveX * controls.moveMag
        var dy = controls.moveY * controls.moveMag
        val mag = controls.moveMag
        if (mag > 0.02f) {
            val sp = player.speed * player.moveMul
            player.x += dx * sp * dt
            player.y += dy * sp * dt
            lastAimX = controls.moveX; lastAimY = controls.moveY
        }
        if (player.iframes > 0f) player.iframes -= dt
        if (player.hp < player.maxHp) player.heal(player.regen * dt)
    }

    private fun autoAttack(dt: Float) {
        player.autoTimer -= dt
        if (player.autoTimer > 0f) return
        val target = nearestEnemy(player.x, player.y, player.autoRange) ?: run {
            player.autoTimer = 0.15f   // retry soon if nothing in range
            return
        }
        player.autoTimer = 1f / player.autoRate

        val out = FloatArray(2)
        Mathx.normInto(target.x - player.x, target.y - player.y, out)
        val baseAng = atan2(out[1], out[0])
        val n = player.autoCount
        val spread = if (n > 1) 0.22f else 0f
        for (i in 0 until n) {
            val a = baseAng + (i - (n - 1) / 2f) * spread
            val vx = cos(a) * player.autoSpeed
            val vy = sin(a) * player.autoSpeed
            shots.add(
                Shot(
                    player.x, player.y, vx, vy,
                    9f * player.projSizeMul, player.autoDamage,
                    player.autoPierce, player.autoRange / player.autoSpeed,
                    Color.rgb(180, 240, 255), knockback = 40f
                )
            )
        }
    }

    /* ---------------------------- spawning ---------------------------- */

    private fun spawnEnemies(dt: Float) {
        spawnTimer -= dt
        if (spawnTimer > 0f) return
        // Spawn cadence tightens over time.
        val interval = Mathx.clamp(0.95f - gameTime * 0.006f, 0.18f, 0.95f)
        spawnTimer = interval

        val batch = 1 + (gameTime / 35f).toInt()
        for (i in 0 until batch) spawnOne()

        // Elite brute pressure every ~45s.
        if (gameTime > 30f && Mathx.chance(0.04f + gameTime * 0.0002f)) {
            spawnOne(EnemyKind.BRUTE)
        }
    }

    private fun spawnOne(forceKind: EnemyKind? = null) {
        val kind = forceKind ?: rollKind()
        val ang = Mathx.rand(0f, (Math.PI * 2).toFloat())
        val d = max(viewW, viewH) * 0.62f + Mathx.rand(0f, 160f)
        val ex = player.x + cos(ang) * d
        val ey = player.y + sin(ang) * d
        val e = Enemy(ex, ey, kind)

        val hpScale = 1f + gameTime / 50f
        val dmgScale = 1f + gameTime / 110f
        when (kind) {
            EnemyKind.GRUNT -> {
                e.radius = 17f; e.maxHp = 22f * hpScale; e.speed = Mathx.rand(72f, 92f)
                e.touchDmg = 16f * dmgScale; e.xpValue = 1f
            }
            EnemyKind.RUNNER -> {
                e.radius = 13f; e.maxHp = 12f * hpScale; e.speed = Mathx.rand(150f, 185f)
                e.touchDmg = 12f * dmgScale; e.xpValue = 1f
            }
            EnemyKind.BRUTE -> {
                e.radius = 30f; e.maxHp = 120f * hpScale; e.speed = Mathx.rand(48f, 62f)
                e.touchDmg = 34f * dmgScale; e.xpValue = 5f
            }
            EnemyKind.CASTER -> {
                e.radius = 16f; e.maxHp = 30f * hpScale; e.speed = Mathx.rand(70f, 88f)
                e.touchDmg = 14f * dmgScale; e.xpValue = 3f
                e.shootTimer = Mathx.rand(1.2f, 2.4f)
            }
        }
        e.hp = e.maxHp
        enemies.add(e)
    }

    private fun rollKind(): EnemyKind {
        val t = gameTime
        val r = Math.random()
        return when {
            t < 20f -> if (r < 0.85) EnemyKind.GRUNT else EnemyKind.RUNNER
            t < 45f -> when {
                r < 0.55 -> EnemyKind.GRUNT
                r < 0.85 -> EnemyKind.RUNNER
                else -> EnemyKind.BRUTE
            }
            else -> when {
                r < 0.42 -> EnemyKind.GRUNT
                r < 0.70 -> EnemyKind.RUNNER
                r < 0.86 -> EnemyKind.CASTER
                else -> EnemyKind.BRUTE
            }
        }
    }

    /* ---------------------------- enemies ---------------------------- */

    private fun updateEnemies(dt: Float) {
        val px = player.x; val py = player.y
        for (e in enemies) {
            if (!e.alive()) continue

            // status effects
            if (e.slowTimer > 0f) e.slowTimer -= dt else e.slowFactor = 1f
            if (e.burnTimer > 0f) {
                e.burnTimer -= dt
                e.hp -= e.burnDps * dt
                if (Mathx.chance(0.3f)) {
                    addParticles(e.x, e.y, 1, Color.rgb(255, 140, 60), 60f, 0.4f)
                }
            }
            if (e.hitFlash > 0f) e.hitFlash -= dt

            // knockback decay
            e.x += e.kx * dt; e.y += e.ky * dt
            e.kx *= 0.86f; e.ky *= 0.86f

            val out = FloatArray(2)
            Mathx.normInto(px - e.x, py - e.y, out)
            val d = Mathx.dist(px, py, e.x, e.y)
            val sp = e.speed * e.slowFactor

            if (e.kind == EnemyKind.CASTER) {
                // kite: approach until ~320, then hold and shoot
                if (d > 330f) {
                    e.x += out[0] * sp * dt; e.y += out[1] * sp * dt
                } else {
                    // gentle strafe
                    e.x += -out[1] * sp * 0.4f * dt
                    e.y += out[0] * sp * 0.4f * dt
                }
                e.shootTimer -= dt
                if (e.shootTimer <= 0f && d < 560f) {
                    e.shootTimer = Mathx.rand(2.0f, 3.0f)
                    val es = EnemyShot(
                        e.x, e.y, out[0] * 300f, out[1] * 300f,
                        9f, e.touchDmg * 0.9f, 3.2f, Color.rgb(120, 230, 200)
                    )
                    eshots.add(es)
                }
            } else {
                e.x += out[0] * sp * dt; e.y += out[1] * sp * dt
            }

            // contact damage (dps while overlapping)
            if (d < player.radius + e.radius && player.iframes <= 0f) {
                player.hp -= e.touchDmg * dt
                if (Mathx.chance(0.2f)) addShake(3f)
            }
        }

        // simple separation so enemies don't perfectly stack
        separateEnemies()
    }

    private fun separateEnemies() {
        val n = enemies.size
        if (n < 2) return
        // O(n^2) is fine for the swarm sizes here; keep it light by sampling.
        var i = 0
        while (i < n) {
            val a = enemies[i]
            if (!a.alive()) { i++; continue }
            var j = i + 1
            while (j < n) {
                val b = enemies[j]
                if (b.alive()) {
                    val dx = b.x - a.x; val dy = b.y - a.y
                    val rr = a.radius + b.radius
                    val d2 = dx * dx + dy * dy
                    if (d2 > 0.0001f && d2 < rr * rr) {
                        val d = Math.sqrt(d2.toDouble()).toFloat()
                        val push = (rr - d) * 0.5f
                        val nx = dx / d; val ny = dy / d
                        a.x -= nx * push; a.y -= ny * push
                        b.x += nx * push; b.y += ny * push
                    }
                }
                j++
            }
            i++
        }
    }

    private fun updateEnemyShots(dt: Float) {
        val it = eshots.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.x += s.vx * dt; s.y += s.vy * dt
            s.life -= dt
            if (s.life <= 0f) { it.remove(); continue }
            if (player.iframes <= 0f &&
                Mathx.dist(s.x, s.y, player.x, player.y) < player.radius + s.radius
            ) {
                player.hp -= s.dmg
                addShake(5f); haptic(18)
                addParticles(s.x, s.y, 6, s.color, 120f, 0.4f)
                it.remove()
            }
        }
    }

    /* ---------------------------- player shots ---------------------------- */

    private fun updateShots(dt: Float) {
        val it = shots.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.x += s.vx * dt; s.y += s.vy * dt
            s.life -= dt
            var dead = s.life <= 0f

            if (!dead) {
                for (e in enemies) {
                    if (!e.alive() || e.id in s.hitIds) continue
                    if (Mathx.dist(s.x, s.y, e.x, e.y) < s.radius + e.radius) {
                        s.hitIds.add(e.id)
                        val out = FloatArray(2)
                        Mathx.normInto(s.vx, s.vy, out)
                        damageEnemy(
                            e, s.dmg, out[0], out[1], s.knockback,
                            s.burnDps, s.burnTime
                        )
                        addParticles(s.x, s.y, 3, s.color, 140f, 0.3f)
                        if (s.pierceLeft <= 0) { dead = true; break }
                        s.pierceLeft -= 1
                    }
                }
            }
            if (dead) it.remove()
        }
    }

    /* ---------------------------- casting ---------------------------- */

    private fun castSkill(index: Int, dragged: Boolean, dirX: Float, dirY: Float) {
        val st = skills.getOrNull(index) ?: return
        val def = Skills.defs[index]
        if (!st.unlocked || st.cd > 0f) return

        var dx = dirX; var dy = dirY
        if (!dragged) {
            val a = autoAimDir(index)
            dx = a[0]; dy = a[1]
        } else {
            val out = FloatArray(2); Mathx.normInto(dx, dy, out); dx = out[0]; dy = out[1]
        }
        lastAimX = dx; lastAimY = dy

        st.cd = def.baseCooldown * st.cdMul * player.cdMul
        val dmg = def.damage * st.dmgMul * player.skillDmgMul

        when (def.kind) {
            SkillKind.LINE -> {
                val r = def.radius * player.projSizeMul * st.radiusMul
                val sp = def.speed
                shots.add(
                    Shot(
                        player.x, player.y, dx * sp, dy * sp, r, dmg,
                        def.pierce + boltExtraPierce,
                        (def.range * st.rangeMul) / sp,
                        def.color, knockback = def.knockback
                    )
                )
                addParticles(player.x, player.y, 6, def.color, 160f, 0.3f)
                haptic(10)
            }
            SkillKind.LOB -> {
                val range = def.range * st.rangeMul
                val lx = player.x + dx * range
                val ly = player.y + dy * range
                val blast = def.blastRadius * player.projSizeMul * st.radiusMul
                hazards.add(
                    Hazard(
                        lx, ly, blast, 0.55f, dmg, def.color,
                        knockback = 60f, burnDps = def.burnDps, burnTime = def.burnTime
                    )
                )
                haptic(12)
            }
            SkillKind.DASH -> {
                val range = def.range * st.rangeMul
                val sx = player.x; val sy = player.y
                val ex = player.x + dx * range
                val ey = player.y + dy * range
                player.x = ex; player.y = ey
                player.iframes = max(player.iframes, def.iframes)
                val hitR = def.radius * player.projSizeMul * st.radiusMul
                for (e in enemies) {
                    if (!e.alive()) continue
                    if (Mathx.distPointSeg(e.x, e.y, sx, sy, ex, ey) < hitR + e.radius) {
                        damageEnemy(e, dmg, dx, dy, def.knockback, 0f, 0f)
                    }
                }
                // dash trail
                for (k in 0..10) {
                    val t = k / 10f
                    addParticles(
                        Mathx.lerp(sx, ex, t), Mathx.lerp(sy, ey, t),
                        1, def.color, 40f, 0.35f
                    )
                }
                rings.add(RingFx(ex, ey, hitR * 0.4f, hitR, 0.4f, def.color, 6f))
                addShake(4f); haptic(14)
            }
            SkillKind.NOVA -> {
                val blast = def.blastRadius * player.projSizeMul * st.radiusMul
                explode(
                    player.x, player.y, blast, dmg, def.knockback,
                    def.burnDps, def.burnTime, def.slowFactor, def.slowTime, def.color
                )
                rings.add(RingFx(player.x, player.y, 30f, blast, 0.55f, def.color, 14f))
                rings.add(RingFx(player.x, player.y, 10f, blast * 0.7f, 0.4f, Color.WHITE, 8f))
                addShake(16f); haptic(40)
            }
        }
    }

    /** Area damage helper used by meteor detonation and the ultimate. */
    private fun explode(
        cx: Float, cy: Float, radius: Float, dmg: Float, knockback: Float,
        burnDps: Float, burnTime: Float, slowFactor: Float, slowTime: Float, color: Int
    ) {
        for (e in enemies) {
            if (!e.alive()) continue
            if (Mathx.dist(cx, cy, e.x, e.y) < radius + e.radius) {
                val out = FloatArray(2)
                Mathx.normInto(e.x - cx, e.y - cy, out)
                damageEnemy(e, dmg, out[0], out[1], knockback, burnDps, burnTime, slowFactor, slowTime)
            }
        }
        addParticles(cx, cy, 24, color, 260f, 0.6f)
    }

    private fun damageEnemy(
        e: Enemy, dmg: Float,
        kbx: Float, kby: Float, knockback: Float,
        burnDps: Float, burnTime: Float,
        slowFactor: Float = 1f, slowTime: Float = 0f
    ) {
        if (!e.alive()) return
        e.hp -= dmg
        e.hitFlash = 0.09f
        if (knockback > 0f) {
            // brutes resist knockback
            val k = if (e.kind == EnemyKind.BRUTE) knockback * 0.3f else knockback
            e.kx += kbx * k; e.ky += kby * k
        }
        if (burnTime > 0f) {
            e.burnDps = max(e.burnDps, burnDps); e.burnTimer = max(e.burnTimer, burnTime)
        }
        if (slowTime > 0f) {
            e.slowFactor = min(e.slowFactor, slowFactor); e.slowTimer = max(e.slowTimer, slowTime)
        }
    }

    /* ---------------------------- hazards (meteor telegraphs) ---------------------------- */

    private fun updateHazards(dt: Float) {
        val it = hazards.iterator()
        while (it.hasNext()) {
            val h = it.next()
            h.timer -= dt
            if (h.timer <= 0f && !h.detonated) {
                h.detonated = true
                explode(
                    h.x, h.y, h.radius, h.dmg, h.knockback,
                    h.burnDps, h.burnTime, h.slowFactor, h.slowTime, h.color
                )
                rings.add(RingFx(h.x, h.y, h.radius * 0.3f, h.radius, 0.4f, h.color, 12f))
                addShake(9f); haptic(22)
                it.remove()
            }
        }
    }

    /* ---------------------------- gems / leveling ---------------------------- */

    private fun updateGems(dt: Float) {
        val it = gems.iterator()
        while (it.hasNext()) {
            val g = it.next()
            val d = Mathx.dist(g.x, g.y, player.x, player.y)
            if (d < player.pickupRadius || g.attracted) {
                g.attracted = true
                val out = FloatArray(2)
                Mathx.normInto(player.x - g.x, player.y - g.y, out)
                val pull = Mathx.lerp(520f, 980f, 1f - (d / max(1f, player.pickupRadius)).coerceIn(0f, 1f))
                g.vx = out[0] * pull; g.vy = out[1] * pull
                g.x += g.vx * dt; g.y += g.vy * dt
            }
            if (d < player.radius + g.radius) {
                gainXp(g.value)
                addParticles(g.x, g.y, 3, Color.rgb(120, 240, 200), 120f, 0.25f)
                it.remove()
            }
        }
    }

    private fun gainXp(v: Float) {
        player.xp += v
        while (player.xp >= player.xpToNext) {
            player.xp -= player.xpToNext
            player.level += 1
            player.xpToNext = 5f + player.level * 4f + player.level * player.level * 0.25f
            pendingLevels += 1
        }
        if (pendingLevels > 0 && state == State.PLAYING) openLevelUp()
    }

    private fun openLevelUp() {
        choices = Upgrades.roll(this, 3)
        state = State.LEVELUP
        haptic(30)
    }

    private fun chooseUpgrade(i: Int) {
        choices.getOrNull(i)?.apply?.invoke()
        pendingLevels -= 1
        if (pendingLevels > 0) {
            choices = Upgrades.roll(this, 3)
        } else {
            choices = emptyList()
            state = State.PLAYING
        }
    }

    /* ---------------------------- deaths ---------------------------- */

    private fun sweepDeaths() {
        var i = 0
        while (i < enemies.size) {
            val e = enemies[i]
            if (!e.alive() && !e.counted) {
                e.counted = true
                onEnemyDeath(e)
            }
            i++
        }
        enemies.removeAll { !it.alive() }
    }

    private fun onEnemyDeath(e: Enemy) {
        player.kills += 1
        addParticles(e.x, e.y, 10, enemyColor(e.kind), 200f, 0.5f)
        // drop xp (brutes drop a few)
        val drops = if (e.kind == EnemyKind.BRUTE) 3 else 1
        for (k in 0 until drops) {
            gems.add(
                Gem(
                    e.x + Mathx.rand(-10f, 10f),
                    e.y + Mathx.rand(-10f, 10f),
                    if (drops > 1) 2f else e.xpValue
                )
            )
        }
    }

    private fun gameOver() {
        state = State.GAMEOVER
        if (gameTime > bestTime) {
            bestTime = gameTime
            prefs.edit().putFloat("best", bestTime).apply()
        }
        haptic(60)
    }

    /* ---------------------------- particles / fx ---------------------------- */

    private fun addParticles(x: Float, y: Float, n: Int, color: Int, speed: Float, life: Float) {
        for (i in 0 until n) {
            val a = Mathx.rand(0f, (Math.PI * 2).toFloat())
            val sp = Mathx.rand(speed * 0.3f, speed)
            particles.add(
                Particle(
                    x, y, cos(a) * sp, sin(a) * sp,
                    life, life, Mathx.rand(2f, 4.5f), color
                )
            )
        }
    }

    private fun updateParticles(dt: Float) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.x += p.vx * dt; p.y += p.vy * dt
            p.vx *= 0.9f; p.vy *= 0.9f
            p.life -= dt
            if (p.life <= 0f) it.remove()
        }
    }

    // used while paused/menu so effects still settle but no sim runs
    private fun updateParticlesOnly(dt: Float) {
        updateParticles(dt); updateRings(dt); updateTexts(dt)
        if (shake > 0f) shake = max(0f, shake - dt * 26f)
    }

    private fun updateRings(dt: Float) {
        val it = rings.iterator()
        while (it.hasNext()) {
            val r = it.next()
            r.life -= dt
            r.r = Mathx.lerp(r.r, r.maxR, 1f - (r.life / r.maxLife).coerceIn(0f, 1f))
            if (r.life <= 0f) it.remove()
        }
    }

    private fun updateTexts(dt: Float) {
        val it = texts.iterator()
        while (it.hasNext()) {
            val t = it.next()
            t.y -= 40f * dt
            t.life -= dt
            if (t.life <= 0f) it.remove()
        }
    }

    /* ---------------------------- helpers ---------------------------- */

    private fun nearestEnemy(x: Float, y: Float, maxRange: Float): Enemy? {
        var best: Enemy? = null
        var bestD = maxRange * maxRange
        for (e in enemies) {
            if (!e.alive()) continue
            val d2 = Mathx.dist2(x, y, e.x, e.y)
            if (d2 < bestD) { bestD = d2; best = e }
        }
        return best
    }

    private fun autoAimDir(index: Int): FloatArray {
        val def = Skills.defs[index]
        val target = nearestEnemy(player.x, player.y, def.range * 1.5f)
        val out = FloatArray(2)
        if (target != null) {
            Mathx.normInto(target.x - player.x, target.y - player.y, out)
        } else {
            out[0] = lastAimX; out[1] = lastAimY
        }
        return out
    }

    /** Exposed so input could auto-aim if needed. */
    fun autoAimDirPublic(index: Int): FloatArray = autoAimDir(index)

    private fun addShake(m: Float) { shake = max(shake, m) }

    private fun haptic(ms: Long) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") v.vibrate(ms)
            }
        } catch (_: Exception) { }
    }

    private fun enemyColor(k: EnemyKind): Int = when (k) {
        EnemyKind.GRUNT -> Color.rgb(206, 84, 128)
        EnemyKind.RUNNER -> Color.rgb(240, 208, 96)
        EnemyKind.BRUTE -> Color.rgb(180, 64, 64)
        EnemyKind.CASTER -> Color.rgb(96, 206, 178)
    }

    /* ============================ rendering ============================ */

    fun draw(c: Canvas) {
        val ox = if (shake > 0.2f) Mathx.rand(-shake, shake) else 0f
        val oy = if (shake > 0.2f) Mathx.rand(-shake, shake) else 0f
        val sxBase = viewW / 2f - camX + ox
        val syBase = viewH / 2f - camY + oy

        drawBackground(c, sxBase, syBase)

        // gems
        pf.style = Paint.Style.FILL
        for (g in gems) {
            pf.color = Color.rgb(90, 240, 190)
            c.drawCircle(g.x + sxBase, g.y + syBase, g.radius, pf)
        }

        // hazard telegraphs (meteor incoming)
        for (h in hazards) {
            val frac = 1f - (h.timer / h.maxTimer).coerceIn(0f, 1f)
            ps.color = Color.argb(180, Color.red(h.color), Color.green(h.color), Color.blue(h.color))
            ps.strokeWidth = 4f
            c.drawCircle(h.x + sxBase, h.y + syBase, h.radius, ps)
            pf.color = Color.argb((90 * frac).toInt(), Color.red(h.color), Color.green(h.color), Color.blue(h.color))
            c.drawCircle(h.x + sxBase, h.y + syBase, h.radius * frac, pf)
        }

        // enemy shots
        pf.style = Paint.Style.FILL
        for (s in eshots) {
            pf.color = s.color
            c.drawCircle(s.x + sxBase, s.y + syBase, s.radius, pf)
        }

        drawEnemies(c, sxBase, syBase)
        drawShots(c, sxBase, syBase)
        drawPlayer(c, sxBase, syBase)
        drawRings(c, sxBase, syBase)
        drawParticles(c, sxBase, syBase)

        // aim indicators (skillshots being aimed)
        if (state == State.PLAYING) {
            for (a in controls.activeAims()) drawAimIndicator(c, a, sxBase, syBase)
        }

        // on-screen controls
        if (state == State.PLAYING || state == State.PAUSED) controls.draw(c, this)

        drawHud(c)

        when (state) {
            State.MENU -> drawMenu(c)
            State.LEVELUP -> drawLevelUp(c)
            State.PAUSED -> drawPaused(c)
            State.GAMEOVER -> drawGameOver(c)
            else -> {}
        }
    }

    private fun drawBackground(c: Canvas, sx: Float, sy: Float) {
        c.drawColor(Color.rgb(12, 10, 22))
        val spacing = 72f
        ps.color = Color.argb(40, 90, 100, 160)
        ps.strokeWidth = 1.5f
        var gx = (sx % spacing)
        while (gx < viewW) {
            c.drawLine(gx, 0f, gx, viewH.toFloat(), ps); gx += spacing
        }
        var gy = (sy % spacing)
        while (gy < viewH) {
            c.drawLine(0f, gy, viewW.toFloat(), gy, ps); gy += spacing
        }
    }

    private fun drawEnemies(c: Canvas, sx: Float, sy: Float) {
        for (e in enemies) {
            val x = e.x + sx; val y = e.y + sy
            if (x < -60 || y < -60 || x > viewW + 60 || y > viewH + 60) continue
            pf.style = Paint.Style.FILL
            pf.color = if (e.hitFlash > 0f) Color.WHITE else enemyColor(e.kind)
            c.drawCircle(x, y, e.radius, pf)
            ps.color = Color.argb(160, 0, 0, 0); ps.strokeWidth = 3f
            c.drawCircle(x, y, e.radius, ps)
            // hp ring when hurt
            if (e.hp < e.maxHp) {
                val frac = (e.hp / e.maxHp).coerceIn(0f, 1f)
                ps.color = Color.rgb(60, 220, 90); ps.strokeWidth = 3f
                rf.set(x - e.radius - 5, y - e.radius - 5, x + e.radius + 5, y + e.radius + 5)
                c.drawArc(rf, -90f, 360f * frac, false, ps)
            }
        }
    }

    private fun drawShots(c: Canvas, sx: Float, sy: Float) {
        pf.style = Paint.Style.FILL
        for (s in shots) {
            pf.color = s.color
            c.drawCircle(s.x + sx, s.y + sy, s.radius, pf)
        }
    }

    private fun drawPlayer(c: Canvas, sx: Float, sy: Float) {
        val x = player.x + sx; val y = player.y + sy
        // body
        pf.style = Paint.Style.FILL
        pf.color = if (player.iframes > 0f) Color.argb(170, 120, 230, 255) else Color.rgb(90, 200, 255)
        c.drawCircle(x, y, player.radius, pf)
        ps.color = Color.WHITE; ps.strokeWidth = 3f
        c.drawCircle(x, y, player.radius, ps)
        // facing barrel
        val a = atan2(lastAimY, lastAimX)
        pf.color = Color.WHITE
        c.drawCircle(x + cos(a) * player.radius, y + sin(a) * player.radius, 5f, pf)
    }

    private fun drawRings(c: Canvas, sx: Float, sy: Float) {
        ps.style = Paint.Style.STROKE
        for (r in rings) {
            val alpha = (255 * (r.life / r.maxLife)).toInt().coerceIn(0, 255)
            ps.color = Color.argb(alpha, Color.red(r.color), Color.green(r.color), Color.blue(r.color))
            ps.strokeWidth = r.width
            c.drawCircle(r.x + sx, r.y + sy, r.r, ps)
        }
    }

    private fun drawParticles(c: Canvas, sx: Float, sy: Float) {
        pf.style = Paint.Style.FILL
        for (p in particles) {
            val alpha = (255 * (p.life / p.maxLife)).toInt().coerceIn(0, 255)
            pf.color = Color.argb(alpha, Color.red(p.color), Color.green(p.color), Color.blue(p.color))
            c.drawCircle(p.x + sx, p.y + sy, p.size, pf)
        }
    }

    private fun drawAimIndicator(c: Canvas, a: Controls.Aim, sx: Float, sy: Float) {
        val def = Skills.defs[a.index]
        val st = skills[a.index]
        var dx = a.dirX; var dy = a.dirY
        if (!a.dragging) {
            val d = autoAimDir(a.index); dx = d[0]; dy = d[1]
        }
        val px = player.x + sx; val py = player.y + sy
        val col = def.color
        val fill = Color.argb(60, Color.red(col), Color.green(col), Color.blue(col))
        val line = Color.argb(200, Color.red(col), Color.green(col), Color.blue(col))

        when (def.kind) {
            SkillKind.LINE, SkillKind.DASH -> {
                val range = def.range * st.rangeMul
                val ex = px + dx * range; val ey = py + dy * range
                val w = (if (def.kind == SkillKind.LINE) def.radius else def.radius) *
                    player.projSizeMul * st.radiusMul
                ps.strokeCap = Paint.Cap.ROUND
                ps.color = fill; ps.strokeWidth = w * 2f
                c.drawLine(px, py, ex, ey, ps)
                ps.color = line; ps.strokeWidth = 4f
                c.drawLine(px, py, ex, ey, ps)
                // arrowhead
                pf.style = Paint.Style.FILL; pf.color = line
                c.drawCircle(ex, ey, 8f, pf)
                ps.strokeCap = Paint.Cap.BUTT
            }
            SkillKind.LOB -> {
                val range = def.range * st.rangeMul
                val ex = px + dx * range; val ey = py + dy * range
                val blast = def.blastRadius * player.projSizeMul * st.radiusMul
                pf.style = Paint.Style.FILL; pf.color = fill
                c.drawCircle(ex, ey, blast, pf)
                ps.color = line; ps.strokeWidth = 4f
                c.drawCircle(ex, ey, blast, ps)
            }
            SkillKind.NOVA -> {
                val blast = def.blastRadius * player.projSizeMul * st.radiusMul
                pf.style = Paint.Style.FILL; pf.color = fill
                c.drawCircle(px, py, blast, pf)
                ps.color = line; ps.strokeWidth = 4f
                c.drawCircle(px, py, blast, ps)
            }
        }
    }

    /* ---------------------------- HUD + menus ---------------------------- */

    private fun drawHud(c: Canvas) {
        // XP bar across the very top
        val xpFrac = (player.xp / player.xpToNext).coerceIn(0f, 1f)
        pf.style = Paint.Style.FILL
        pf.color = Color.argb(120, 20, 24, 40)
        c.drawRect(0f, 0f, viewW.toFloat(), 10f, pf)
        pf.color = Color.rgb(90, 220, 255)
        c.drawRect(0f, 0f, viewW * xpFrac, 10f, pf)

        // HP bar (top-left)
        val hx = 16f; val hy = 22f; val hw = viewW * 0.34f; val hh = 24f
        pf.color = Color.argb(160, 30, 12, 16)
        rf.set(hx, hy, hx + hw, hy + hh); c.drawRoundRect(rf, 8f, 8f, pf)
        val hpFrac = (player.hp / player.maxHp).coerceIn(0f, 1f)
        pf.color = Color.rgb(230, 70, 80)
        rf.set(hx, hy, hx + hw * hpFrac, hy + hh); c.drawRoundRect(rf, 8f, 8f, pf)
        ptl.color = Color.WHITE; ptl.textSize = 18f; ptl.isFakeBoldText = true
        c.drawText("${player.hp.toInt()} / ${player.maxHp.toInt()}", hx + 10f, hy + 18f, ptl)

        // Level badge
        ptl.textSize = 20f
        c.drawText("Lv ${player.level}", hx, hy + hh + 24f, ptl)

        // Timer (top center)
        pt.color = Color.WHITE; pt.textSize = 34f; pt.isFakeBoldText = true
        c.drawText(Mathx.fmtTime(gameTime), viewW / 2f, 40f, pt)

        // Kills (under timer)
        pt.textSize = 18f; pt.color = Color.argb(220, 230, 230, 240)
        c.drawText("Kills ${player.kills}", viewW / 2f, 62f, pt)

        // Pause button (top-right)
        val pb = pauseBtn()
        pf.color = Color.argb(140, 30, 34, 52)
        c.drawRoundRect(pb, 10f, 10f, pf)
        pf.color = Color.WHITE
        val bw = 5f; val cy = pb.centerY()
        c.drawRect(pb.centerX() - 11f, cy - 11f, pb.centerX() - 11f + bw, cy + 11f, pf)
        c.drawRect(pb.centerX() + 6f, cy - 11f, pb.centerX() + 6f + bw, cy + 11f, pf)
    }

    private fun dim(c: Canvas, alpha: Int) {
        pf.style = Paint.Style.FILL
        pf.color = Color.argb(alpha, 0, 0, 0)
        c.drawRect(0f, 0f, viewW.toFloat(), viewH.toFloat(), pf)
    }

    private fun drawMenu(c: Canvas) {
        dim(c, 180)
        pt.isFakeBoldText = true
        pt.color = Color.rgb(120, 220, 255); pt.textSize = 64f
        c.drawText("RIFT SURVIVORS", viewW / 2f, viewH * 0.32f, pt)
        pt.color = Color.argb(220, 230, 230, 240); pt.textSize = 24f
        c.drawText("Survive the swarm — MOBA skillshots", viewW / 2f, viewH * 0.32f + 40f, pt)

        pt.color = Color.WHITE; pt.textSize = 34f
        val pulse = 0.6f + 0.4f * sin(gameTime * 4f)
        pt.color = Color.argb((255 * pulse).toInt(), 255, 255, 255)
        c.drawText("TAP TO PLAY", viewW / 2f, viewH * 0.6f, pt)

        pt.color = Color.argb(200, 200, 210, 230); pt.textSize = 20f
        c.drawText("Left thumb = move • Right pads = aim & cast", viewW / 2f, viewH * 0.72f, pt)
        c.drawText("Drag a skill pad to aim the skillshot, release to fire", viewW / 2f, viewH * 0.72f + 28f, pt)

        if (bestTime > 0f) {
            pt.color = Color.rgb(255, 210, 120); pt.textSize = 22f
            c.drawText("Best: ${Mathx.fmtTime(bestTime)}", viewW / 2f, viewH * 0.46f, pt)
        }
    }

    private fun drawPaused(c: Canvas) {
        dim(c, 170)
        pt.isFakeBoldText = true; pt.color = Color.WHITE; pt.textSize = 52f
        c.drawText("PAUSED", viewW / 2f, viewH * 0.34f, pt)
        drawButton(c, resumeBtn(), "RESUME", Color.rgb(90, 200, 255))
        drawButton(c, restartBtn(), "RESTART", Color.rgb(230, 90, 100))
    }

    private fun drawGameOver(c: Canvas) {
        dim(c, 190)
        pt.isFakeBoldText = true
        pt.color = Color.rgb(255, 90, 110); pt.textSize = 60f
        c.drawText("YOU DIED", viewW / 2f, viewH * 0.30f, pt)
        pt.color = Color.WHITE; pt.textSize = 30f
        c.drawText("Survived ${Mathx.fmtTime(gameTime)}", viewW / 2f, viewH * 0.30f + 50f, pt)
        c.drawText("Level ${player.level} • ${player.kills} kills", viewW / 2f, viewH * 0.30f + 90f, pt)
        if (bestTime > 0f) {
            pt.color = Color.rgb(255, 210, 120); pt.textSize = 24f
            c.drawText("Best: ${Mathx.fmtTime(bestTime)}", viewW / 2f, viewH * 0.30f + 130f, pt)
        }
        pt.color = Color.WHITE; pt.textSize = 30f
        val pulse = 0.6f + 0.4f * sin(gameTime * 4f)
        pt.color = Color.argb((255 * pulse).toInt(), 255, 255, 255)
        c.drawText("TAP TO CONTINUE", viewW / 2f, viewH * 0.7f, pt)
    }

    private fun drawLevelUp(c: Canvas) {
        dim(c, 200)
        pt.isFakeBoldText = true; pt.color = Color.rgb(255, 215, 120); pt.textSize = 44f
        c.drawText("LEVEL UP!", viewW / 2f, viewH * 0.16f, pt)
        pt.color = Color.argb(220, 220, 220, 235); pt.textSize = 22f
        c.drawText("Choose an upgrade", viewW / 2f, viewH * 0.16f + 34f, pt)

        val rects = cardRects()
        for (i in choices.indices) {
            val r = rects[i]
            val u = choices[i]
            // card bg
            pf.style = Paint.Style.FILL
            pf.color = Color.rgb(24, 26, 42)
            c.drawRoundRect(r, 18f, 18f, pf)
            ps.style = Paint.Style.STROKE; ps.strokeWidth = 4f; ps.color = u.color
            c.drawRoundRect(r, 18f, 18f, ps)

            // accent header strip
            pf.color = Color.argb(60, Color.red(u.color), Color.green(u.color), Color.blue(u.color))
            rf.set(r.left, r.top, r.right, r.top + r.height() * 0.26f)
            c.drawRoundRect(rf, 18f, 18f, pf)

            // title
            pt.isFakeBoldText = true; pt.color = u.color; pt.textSize = 26f
            drawWrapped(c, u.title, r.centerX(), r.top + r.height() * 0.18f, r.width() - 28f, 28f, pt)
            // desc
            pt.isFakeBoldText = false; pt.color = Color.WHITE; pt.textSize = 21f
            drawWrapped(c, u.desc, r.centerX(), r.top + r.height() * 0.45f, r.width() - 28f, 26f, pt)

            pt.color = Color.argb(180, 200, 210, 230); pt.textSize = 18f
            c.drawText("TAP TO PICK", r.centerX(), r.bottom - 22f, pt)
        }
    }

    private fun drawButton(c: Canvas, r: RectF, label: String, color: Int) {
        pf.style = Paint.Style.FILL; pf.color = Color.rgb(24, 26, 42)
        c.drawRoundRect(r, 14f, 14f, pf)
        ps.style = Paint.Style.STROKE; ps.strokeWidth = 4f; ps.color = color
        c.drawRoundRect(r, 14f, 14f, ps)
        pt.isFakeBoldText = true; pt.color = Color.WHITE; pt.textSize = 28f
        c.drawText(label, r.centerX(), r.centerY() + 10f, pt)
    }

    /** Very small word-wrap helper for upgrade cards. */
    private fun drawWrapped(
        c: Canvas, s: String, cx: Float, top: Float, maxW: Float, lineH: Float, paint: Paint
    ) {
        val words = s.split(" ")
        val lines = ArrayList<String>()
        var cur = StringBuilder()
        for (w in words) {
            val test = if (cur.isEmpty()) w else "$cur $w"
            if (paint.measureText(test) > maxW && cur.isNotEmpty()) {
                lines.add(cur.toString()); cur = StringBuilder(w)
            } else cur = StringBuilder(test)
        }
        if (cur.isNotEmpty()) lines.add(cur.toString())
        var y = top
        for (l in lines) { c.drawText(l, cx, y, paint); y += lineH }
    }

    /* ---------------------------- menu geometry ---------------------------- */

    private fun pauseBtn(): RectF {
        val s = 52f
        return RectF(viewW - s - 14f, 14f, viewW - 14f, 14f + s)
    }

    private fun resumeBtn(): RectF {
        val w = min(viewW * 0.5f, 360f); val h = 64f
        val x = (viewW - w) / 2f
        return RectF(x, viewH * 0.46f, x + w, viewH * 0.46f + h)
    }

    private fun restartBtn(): RectF {
        val w = min(viewW * 0.5f, 360f); val h = 64f
        val x = (viewW - w) / 2f
        return RectF(x, viewH * 0.46f + 84f, x + w, viewH * 0.46f + 84f + h)
    }

    private fun cardRects(): List<RectF> {
        val n = 3
        val gap = viewW * 0.025f
        val cardW = min((viewW - gap * (n + 1)) / n, viewW * 0.28f)
        val cardH = viewH * 0.52f
        val totalW = cardW * n + gap * (n - 1)
        val startX = (viewW - totalW) / 2f
        val top = viewH * 0.28f
        val list = ArrayList<RectF>(n)
        for (i in 0 until n) {
            val x = startX + i * (cardW + gap)
            list.add(RectF(x, top, x + cardW, top + cardH))
        }
        return list
    }
}
