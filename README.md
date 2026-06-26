# Rift Survivors

A native **Android** action game: a *Vampire Survivors*-style horde survival roguelite,
but controlled like a **MOBA** — a floating movement joystick plus **Wild Rift–style
aimable skillshots** (drag a skill pad to aim, release to fire).

Written in **pure Kotlin** with a custom `SurfaceView` game loop and the Android
`Canvas` API — no game engine, no third-party game libraries.

---

## Gameplay

- **Survive** an ever-growing swarm. The longer you live, the harder it gets.
- **Auto-attack**: your hero automatically fires at the nearest enemy (the
  Vampire-Survivors "automatic weapon" feel).
- **Skillshots (the MOBA twist)**: four aimed abilities you steer yourself.
- **Level up**: enemies drop XP gems. Filling the XP bar pauses the action and
  lets you pick **1 of 3 upgrades** (roguelite progression).
- **Goal**: beat your best survival time.

### Skills

| Pad | Skill        | Type        | Effect                                            |
|-----|--------------|-------------|---------------------------------------------------|
| Q   | Arcane Bolt  | Line        | Piercing bolt fired in a straight line            |
| W   | Meteor       | Lob / AoE   | Telegraphed area blast + burn (enemies can dodge) |
| E   | Blink Strike | Dash        | Blink through enemies, damaging them, brief i-frames |
| R   | Cataclysm    | Nova (Ult)  | Huge shockwave around you: damage, knockback, slow |

You start with **Q**; **W / E / R** are unlocked through level-up choices and can
then be ranked up further.

### Enemies

- **Grunt** – baseline melee swarmer.
- **Runner** – fast, fragile.
- **Brute** – big, tanky, resists knockback; drops extra XP.
- **Caster** – kites you and fires ranged shots you must dodge.

---

## Controls

- **Left thumb** (anywhere on the left): floating **movement joystick**.
- **Right-side pads** (bottom-right diamond): the four skills.
  - **Tap** a pad → cast toward the **nearest enemy** (auto-aim).
  - **Press + drag** a pad → an **aim indicator** appears on your hero; **release**
    to cast the skillshot in that direction. This is the Wild Rift aiming model.
- **Pause** button: top-right.

The game runs in **landscape, fullscreen immersive**.

---

## Building the APK

> This repo contains a complete Android Studio / Gradle project. You need the
> Android SDK to build it (it is **not** bundled).

### Option A — Android Studio (easiest)
1. Open Android Studio → **Open** → select this project folder.
2. Let it sync Gradle (it will provision the Android SDK if needed).
3. **Run** ▶ on a connected device/emulator, or **Build → Build APK(s)**.

### Option B — Command line
1. Install the Android SDK and set `ANDROID_HOME` (or create `local.properties`
   with `sdk.dir=/path/to/Android/sdk`).
2. Build a debug APK:
   ```bash
   ./gradlew assembleDebug
   ```
   Output: `app/build/outputs/apk/debug/app-debug.apk`
3. Install on a device:
   ```bash
   ./gradlew installDebug
   ```

### Toolchain
- Gradle **8.7** (via the included wrapper)
- Android Gradle Plugin **8.2.2**, Kotlin **1.9.22**
- `compileSdk` / `targetSdk` **34**, `minSdk` **24** (Android 7.0+)
- JDK **17** required to run the build

---

## Browser-playable preview (`web/`)

The `web/` folder contains an **HTML5 Canvas** port of the same game — identical
design (floating joystick + drag-to-aim skillshots, auto-attack, enemies,
XP/level-ups, menus). It runs in any modern browser, desktop or mobile, with no
build step, so you can play/preview without the Android toolchain.

```bash
cd web
python3 -m http.server 8099
# then open http://localhost:8099/ (or your phone on the same network)
```

- **Touch**: left thumb = move joystick; right pads = skills (tap = auto-aim,
  drag = aim then release to fire).
- **Desktop**: WASD/arrows move, mouse aims, `Q W E R` (or `1-4`) cast,
  click to start / pick upgrades, `P` pauses.

The native Kotlin project under `app/` remains the target Android build.

## Project layout

```
app/src/main/
├── AndroidManifest.xml
├── java/com/riftsurvivors/game/
│   ├── MainActivity.kt        # fullscreen immersive host activity
│   ├── GameView.kt            # SurfaceView + game-loop thread
│   └── core/
│       ├── Mathx.kt           # math / RNG helpers
│       ├── Entities.kt        # Player, Enemy, projectiles, gems, effects
│       ├── Skills.kt          # the 4 MOBA skillshot definitions
│       ├── Upgrades.kt        # level-up reward pool
│       ├── Controls.kt        # joystick + drag-to-aim skill pads
│       └── World.kt           # simulation, collisions, rendering, menus
└── res/                       # icons, theme, strings
```

All gameplay state lives in `World`; `GameView` only owns the surface and the
loop thread. There are no per-frame heap-heavy patterns in the hot loop.
