package com.riftsurvivors.game.core

import android.graphics.Color

/** One pickable level-up reward. */
class Upgrade(
    val title: String,
    val desc: String,
    val color: Int,
    val apply: () -> Unit
)

/** Builds the pool of currently-valid upgrade choices for a [World]. */
object Upgrades {

    fun roll(world: World, count: Int): List<Upgrade> {
        val pool = build(world).toMutableList()
        // Shuffle and take `count` distinct options.
        for (i in pool.indices.reversed()) {
            val j = (0..i).random()
            val t = pool[i]; pool[i] = pool[j]; pool[j] = t
        }
        return pool.take(count.coerceAtMost(pool.size))
    }

    private fun build(world: World): List<Upgrade> {
        val p = world.player
        val s = world.skills
        val list = ArrayList<Upgrade>()
        val cBody = Color.rgb(120, 230, 140)
        val cOff = Color.rgb(255, 120, 120)
        val cUtil = Color.rgb(120, 200, 255)
        val cSkill = Color.rgb(255, 200, 90)

        // --- Survivability ---
        list += Upgrade("Vitality", "+25 Max HP and heal", cBody) {
            p.maxHp += 25f; p.heal(40f)
        }
        list += Upgrade("Regeneration", "+0.8 HP / sec", cBody) {
            p.regen += 0.8f
        }
        list += Upgrade("Swift Boots", "+12% Move Speed", cUtil) {
            p.moveMul *= 1.12f
        }
        list += Upgrade("Magnet", "+35% Pickup Range", cUtil) {
            p.pickupRadius *= 1.35f
        }

        // --- Basic attack (VS-style auto weapon) ---
        list += Upgrade("Sharpened Bolts", "+30% Basic Attack Damage", cOff) {
            p.autoDamage *= 1.30f
        }
        list += Upgrade("Rapid Fire", "+25% Basic Attack Speed", cOff) {
            p.autoRate *= 1.25f
        }
        if (p.autoCount < 5) {
            list += Upgrade("Multishot", "+1 Basic Attack projectile", cOff) {
                p.autoCount += 1
            }
        }
        if (p.autoPierce < 4) {
            list += Upgrade("Penetration", "Basic attacks pierce +1", cOff) {
                p.autoPierce += 1
            }
        }

        // --- Global skill modifiers ---
        list += Upgrade("Arcane Power", "+18% Skill Damage", cSkill) {
            p.skillDmgMul *= 1.18f
        }
        list += Upgrade("Cooldown Matrix", "-12% Skill Cooldowns", cSkill) {
            p.cdMul *= 0.88f
        }
        list += Upgrade("Big Spells", "+15% Skill Size", cSkill) {
            p.projSizeMul *= 1.15f
        }

        // --- Skill unlocks / per-skill upgrades ---
        for (i in 1 until s.size) {
            val st = s[i]
            val def = Skills.defs[i]
            if (!st.unlocked) {
                list += Upgrade("Unlock: ${def.name} [${def.key}]", def.desc, def.color) {
                    st.unlocked = true; st.level = 1
                }
            } else if (st.level < 5) {
                list += Upgrade("${def.name} +", "Rank up ${def.name}: +25% dmg, -8% CD", def.color) {
                    st.level += 1
                    st.dmgMul *= 1.25f
                    st.cdMul *= 0.92f
                    st.radiusMul *= 1.06f
                }
            }
        }
        // Q upgrade too
        run {
            val st = s[0]
            if (st.level < 6) {
                val def = Skills.defs[0]
                list += Upgrade("${def.name} +", "Rank up ${def.name}: +20% dmg, +1 pierce", def.color) {
                    st.level += 1
                    st.dmgMul *= 1.20f
                    world.boltExtraPierce += 1
                }
            }
        }

        return list
    }
}
