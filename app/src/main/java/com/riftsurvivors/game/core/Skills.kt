package com.riftsurvivors.game.core

import android.graphics.Color

/** How a skillshot resolves when cast. */
enum class SkillKind { LINE, LOB, DASH, NOVA }

/** Static description of one MOBA skillshot. All four are AIMED. */
class SkillDef(
    val id: String,
    val name: String,
    val key: String,
    val kind: SkillKind,
    val color: Int,
    val baseCooldown: Float,
    val range: Float,
    val damage: Float,
    // kind-specific
    val speed: Float = 0f,          // LINE projectile speed
    val radius: Float = 0f,         // LINE projectile / DASH hit radius
    val pierce: Int = 0,            // LINE
    val blastRadius: Float = 0f,    // LOB / NOVA
    val knockback: Float = 0f,
    val burnDps: Float = 0f,
    val burnTime: Float = 0f,
    val slowFactor: Float = 1f,
    val slowTime: Float = 0f,
    val iframes: Float = 0f,        // DASH invulnerability
    val desc: String
)

/** Per-run mutable state for a skill (cooldown, unlock, upgrade level). */
class SkillState(val index: Int) {
    var cd = 0f
    var unlocked = index == 0       // start with Q only; W/E/R unlock via level-ups
    var level = if (index == 0) 1 else 0
    var dmgMul = 1f
    var cdMul = 1f
    var rangeMul = 1f
    var radiusMul = 1f
}

object Skills {
    val defs: List<SkillDef> = listOf(
        SkillDef(
            id = "bolt", name = "Arcane Bolt", key = "Q", kind = SkillKind.LINE,
            color = Color.rgb(90, 209, 255),
            baseCooldown = 1.3f, range = 640f, damage = 30f,
            speed = 780f, radius = 16f, pierce = 3, knockback = 110f,
            desc = "Piercing bolt fired in a line."
        ),
        SkillDef(
            id = "meteor", name = "Meteor", key = "W", kind = SkillKind.LOB,
            color = Color.rgb(255, 157, 77),
            baseCooldown = 5.0f, range = 470f, damage = 80f,
            blastRadius = 135f, burnDps = 24f, burnTime = 3f,
            desc = "Lob a meteor; area blast + burn."
        ),
        SkillDef(
            id = "blink", name = "Blink Strike", key = "E", kind = SkillKind.DASH,
            color = Color.rgb(176, 140, 255),
            baseCooldown = 4.0f, range = 300f, damage = 46f,
            radius = 50f, iframes = 0.35f, knockback = 80f,
            desc = "Dash through foes, damaging them."
        ),
        SkillDef(
            id = "nova", name = "Cataclysm", key = "R", kind = SkillKind.NOVA,
            color = Color.rgb(255, 93, 122),
            baseCooldown = 20.0f, range = 360f, damage = 170f,
            blastRadius = 360f, knockback = 380f, slowFactor = 0.45f, slowTime = 2.5f,
            desc = "Ultimate: shockwave around you."
        )
    )

    fun newStates(): MutableList<SkillState> =
        defs.indices.map { SkillState(it) }.toMutableList()
}
