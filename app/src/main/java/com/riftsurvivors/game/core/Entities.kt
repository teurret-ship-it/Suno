package com.riftsurvivors.game.core

/** Mutable game entities. Plain classes (no allocation-heavy patterns) so the
 *  per-frame update loop stays cheap. */

class Player {
    var x = 0f
    var y = 0f
    val radius = 20f

    var maxHp = 100f
    var hp = 100f
    var regen = 0.6f                 // hp per second

    var level = 1
    var xp = 0f
    var xpToNext = 6f

    var speed = 230f                 // world units / sec
    var moveMul = 1f
    var pickupRadius = 95f
    var iframes = 0f                 // invulnerability timer (e.g. after blink)

    // Basic auto-attack (the Vampire-Survivors style automatic weapon).
    var autoDamage = 14f
    var autoRate = 1.6f              // shots per second
    var autoTimer = 0f
    var autoSpeed = 620f
    var autoCount = 1               // projectiles per volley
    var autoPierce = 0
    var autoRange = 520f

    // Global skill modifiers.
    var skillDmgMul = 1f
    var cdMul = 1f
    var projSizeMul = 1f

    var kills = 0

    fun heal(a: Float) { hp = (hp + a).coerceAtMost(maxHp) }
    fun alive() = hp > 0f
}

enum class EnemyKind { GRUNT, RUNNER, BRUTE, CASTER }

class Enemy(
    var x: Float,
    var y: Float,
    val kind: EnemyKind
) {
    var radius = 16f
    var maxHp = 20f
    var hp = 20f
    var speed = 80f
    var touchDmg = 8f
    var xpValue = 1f

    var hitFlash = 0f
    var slowTimer = 0f
    var slowFactor = 1f
    var burnTimer = 0f
    var burnDps = 0f

    // knockback velocity (decays)
    var kx = 0f
    var ky = 0f

    var attackCd = 0f               // melee contact cadence
    var shootTimer = 0f             // caster ranged cadence
    var counted = false             // death already processed?

    val id = nextId++

    fun alive() = hp > 0f

    companion object { private var nextId = 1L }
}

class Shot(
    var x: Float, var y: Float,
    var vx: Float, var vy: Float,
    var radius: Float,
    var dmg: Float,
    var pierceLeft: Int,
    var life: Float,
    var color: Int,
    var knockback: Float = 0f,
    var burnDps: Float = 0f,
    var burnTime: Float = 0f
) {
    val hitIds = HashSet<Long>()
}

class EnemyShot(
    var x: Float, var y: Float,
    var vx: Float, var vy: Float,
    var radius: Float,
    var dmg: Float,
    var life: Float,
    var color: Int
)

class Gem(var x: Float, var y: Float, var value: Float) {
    var vx = 0f
    var vy = 0f
    val radius = 7f
    var attracted = false
}

class Particle(
    var x: Float, var y: Float,
    var vx: Float, var vy: Float,
    var life: Float, var maxLife: Float,
    var size: Float, var color: Int
)

class FloatText(
    var x: Float, var y: Float,
    var text: String,
    var color: Int,
    var size: Float,
    var life: Float = 0.9f
) {
    val maxLife = life
}

/** Delayed-area effect (meteor telegraph that detonates) plus visual rings. */
class Hazard(
    var x: Float, var y: Float,
    var radius: Float,
    var timer: Float,
    var dmg: Float,
    var color: Int,
    var knockback: Float = 0f,
    var burnDps: Float = 0f,
    var burnTime: Float = 0f,
    var slowFactor: Float = 1f,
    var slowTime: Float = 0f
) {
    val maxTimer = timer
    var detonated = false
}

/** Expanding ring / flash visual effect (no gameplay impact). */
class RingFx(
    var x: Float, var y: Float,
    var r: Float, var maxR: Float,
    var life: Float, var color: Int,
    var width: Float = 6f
) {
    val maxLife = life
}
