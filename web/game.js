/* ============================================================
 * Rift Survivors — HTML5 Canvas build
 * Vampire-Survivors style horde survival + MOBA controls and
 * Wild Rift style drag-to-aim skillshots.
 *
 * Controls:
 *   Touch  : left thumb = floating move joystick; right pads = skills
 *            (tap = auto-aim cast, drag = aim then release to fire).
 *   Desktop: WASD / arrows move, mouse aims, Q W E R (or 1-4) cast,
 *            click to start / pick upgrades, P pauses.
 * ========================================================== */
(function () {
  'use strict';

  const TAU = Math.PI * 2;
  const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);
  const lerp = (a, b, t) => a + (b - a) * t;
  const rand = (a, b) => a + Math.random() * (b - a);
  const randInt = (a, b) => Math.floor(rand(a, b + 1));
  const chance = (p) => Math.random() < p;
  const dist = (ax, ay, bx, by) => Math.hypot(ax - bx, ay - by);
  const dist2 = (ax, ay, bx, by) => { const dx = ax - bx, dy = ay - by; return dx * dx + dy * dy; };
  function norm(x, y) { const l = Math.hypot(x, y); return l < 1e-6 ? { x: 1, y: 0 } : { x: x / l, y: y / l }; }
  function distPointSeg(px, py, ax, ay, bx, by) {
    const abx = bx - ax, aby = by - ay, apx = px - ax, apy = py - ay;
    const ab2 = abx * abx + aby * aby;
    let t = ab2 < 1e-6 ? 0 : (apx * abx + apy * aby) / ab2;
    t = clamp(t, 0, 1);
    return Math.hypot(px - (ax + abx * t), py - (ay + aby * t));
  }
  function fmtTime(s) {
    const m = Math.floor(s / 60), ss = Math.floor(s % 60);
    return (m < 10 ? '0' : '') + m + ':' + (ss < 10 ? '0' : '') + ss;
  }

  /* ---------------------------- Skills ---------------------------- */
  const SKILLS = [
    { id: 'bolt', name: 'Arcane Bolt', key: 'Q', kind: 'line', color: '#5ad1ff',
      cd: 1.3, range: 640, dmg: 30, speed: 780, radius: 16, pierce: 3, knockback: 110,
      desc: 'Piercing bolt fired in a line.' },
    { id: 'meteor', name: 'Meteor', key: 'W', kind: 'lob', color: '#ff9d4d',
      cd: 5.0, range: 470, dmg: 80, blast: 135, burnDps: 24, burnTime: 3,
      desc: 'Lob a meteor; area blast + burn.' },
    { id: 'blink', name: 'Blink Strike', key: 'E', kind: 'dash', color: '#b08cff',
      cd: 4.0, range: 300, dmg: 46, radius: 50, iframes: 0.35, knockback: 80,
      desc: 'Dash through foes, damaging them.' },
    { id: 'nova', name: 'Cataclysm', key: 'R', kind: 'nova', color: '#ff5d7a',
      cd: 20.0, range: 360, dmg: 170, blast: 360, knockback: 380, slowFactor: 0.45, slowTime: 2.5,
      desc: 'Ultimate: shockwave around you.' }
  ];

  /* ============================================================ */
  class Game {
    constructor(canvas) {
      this.canvas = canvas;
      this.ctx = canvas.getContext('2d');
      this.dpr = Math.min(window.devicePixelRatio || 1, 2);
      this.viewW = 0; this.viewH = 0;
      this.state = 'menu'; // menu | playing | levelup | paused | gameover
      this.best = parseFloat(localStorage.getItem('rift_best') || '0') || 0;

      this.keys = {};
      this.mouse = { x: 0, y: 0 };
      this.pointers = new Map();    // id -> {role, ...}
      this.joy = { active: false, id: null, ox: 0, oy: 0, kx: 0, ky: 0, dx: 0, dy: 0, mag: 0 };
      this.aims = [null, null, null, null];
      this.pads = [];

      // World state — initialised empty so the menu loop is safe before a run.
      this.player = null;
      this.skills = [];
      this.enemies = []; this.shots = []; this.eshots = [];
      this.gems = []; this.particles = []; this.rings = []; this.hazards = [];
      this.cam = { x: 0, y: 0 }; this.shake = 0;
      this.time = 0; this.spawnTimer = 0;
      this.pendingLevels = 0; this.choices = [];
      this.lastAim = { x: 1, y: 0 };
      this.boltExtraPierce = 0;

      this.resize();
      window.addEventListener('resize', () => this.resize());
      this.bindInput();

      this.last = performance.now();
      requestAnimationFrame((t) => this.frame(t));
    }

    resize() {
      const w = window.innerWidth, h = window.innerHeight;
      this.viewW = w; this.viewH = h;
      this.canvas.width = Math.floor(w * this.dpr);
      this.canvas.height = Math.floor(h * this.dpr);
      this.canvas.style.width = w + 'px';
      this.canvas.style.height = h + 'px';
      this.ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
      this.layoutPads();
    }

    layoutPads() {
      const w = this.viewW, h = this.viewH, mn = Math.min(w, h);
      const r = clamp(mn * 0.085, 40, 70);
      const pad = r + mn * 0.05;
      const d = r * 1.55;
      const clx = w - pad - d, cly = h - pad - d;
      this.pads = [
        { idx: 0, x: clx - d, y: cly, r },
        { idx: 1, x: clx, y: cly - d, r },
        { idx: 2, x: clx + d, y: cly, r },
        { idx: 3, x: clx, y: cly + d, r: r * 1.04 }
      ];
      this.joyMaxR = clamp(mn * 0.13, 64, 120);
    }

    /* ---------------------------- run setup ---------------------------- */
    startRun() {
      this.player = {
        x: 0, y: 0, radius: 20, maxHp: 100, hp: 100, regen: 0.6,
        level: 1, xp: 0, xpToNext: 6, speed: 230, moveMul: 1,
        pickupRadius: 95, iframes: 0,
        autoDamage: 14, autoRate: 1.6, autoTimer: 0, autoSpeed: 620,
        autoCount: 1, autoPierce: 0, autoRange: 520,
        skillDmgMul: 1, cdMul: 1, projSizeMul: 1, kills: 0
      };
      this.skills = SKILLS.map((s, i) => ({
        idx: i, cd: 0, unlocked: i === 0, level: i === 0 ? 1 : 0,
        dmgMul: 1, cdMul: 1, rangeMul: 1, radiusMul: 1
      }));
      this.boltExtraPierce = 0;
      this.enemies = []; this.shots = []; this.eshots = [];
      this.gems = []; this.particles = []; this.rings = []; this.hazards = [];
      this.cam = { x: 0, y: 0 }; this.shake = 0;
      this.time = 0; this.spawnTimer = 0;
      this.pendingLevels = 0; this.choices = [];
      this.lastAim = { x: 1, y: 0 };
      this.state = 'playing';
    }

    /* ---------------------------- input ---------------------------- */
    bindInput() {
      const c = this.canvas;
      c.addEventListener('pointerdown', (e) => { c.setPointerCapture?.(e.pointerId); this.onDown(e); });
      c.addEventListener('pointermove', (e) => this.onMove(e));
      c.addEventListener('pointerup', (e) => this.onUp(e));
      c.addEventListener('pointercancel', (e) => this.onUp(e));
      window.addEventListener('keydown', (e) => this.onKey(e, true));
      window.addEventListener('keyup', (e) => this.onKey(e, false));
      window.addEventListener('mousemove', (e) => { this.mouse.x = e.clientX; this.mouse.y = e.clientY; });
    }

    padAt(x, y) {
      for (const p of this.pads) {
        const rr = p.r * 1.3;
        if (dist2(x, y, p.x, p.y) <= rr * rr) return p;
      }
      return null;
    }

    onDown(e) {
      const x = e.clientX, y = e.clientY;
      if (this.state === 'menu') { this.startRun(); return; }
      if (this.state === 'gameover') { this.state = 'menu'; return; }
      if (this.state === 'paused') {
        if (this.hit(this.btnResume(), x, y)) this.state = 'playing';
        else if (this.hit(this.btnRestart(), x, y)) this.startRun();
        return;
      }
      if (this.state === 'levelup') {
        const rects = this.cardRects();
        for (let i = 0; i < rects.length; i++) if (this.hit(rects[i], x, y)) { this.chooseUpgrade(i); break; }
        return;
      }
      // playing
      if (this.hit(this.pauseBtn(), x, y)) { this.state = 'paused'; return; }
      const pad = this.padAt(x, y);
      if (pad && !this.aims[pad.idx]) {
        this.aims[pad.idx] = { id: e.pointerId, sx: x, sy: y, dx: 1, dy: 0, dragging: false };
        this.pointers.set(e.pointerId, { role: 'aim', idx: pad.idx });
        return;
      }
      if (!this.joy.active) {
        this.joy.active = true; this.joy.id = e.pointerId;
        this.joy.ox = x; this.joy.oy = y; this.joy.kx = x; this.joy.ky = y;
        this.joy.dx = 0; this.joy.dy = 0; this.joy.mag = 0;
        this.pointers.set(e.pointerId, { role: 'joy' });
      }
    }

    onMove(e) {
      const x = e.clientX, y = e.clientY;
      const p = this.pointers.get(e.pointerId);
      if (!p) return;
      if (p.role === 'joy' && this.joy.active && this.joy.id === e.pointerId) {
        let dx = x - this.joy.ox, dy = y - this.joy.oy;
        const l = Math.hypot(dx, dy);
        if (l > this.joyMaxR) { dx = dx / l * this.joyMaxR; dy = dy / l * this.joyMaxR; }
        this.joy.kx = this.joy.ox + dx; this.joy.ky = this.joy.oy + dy;
        const n = norm(dx, dy);
        this.joy.dx = n.x; this.joy.dy = n.y;
        this.joy.mag = clamp(Math.hypot(dx, dy) / this.joyMaxR, 0, 1);
        if (l < 6) this.joy.mag = 0;
      } else if (p.role === 'aim') {
        const a = this.aims[p.idx];
        if (a && a.id === e.pointerId) {
          const dx = x - a.sx, dy = y - a.sy;
          if (Math.hypot(dx, dy) > 16) { a.dragging = true; const n = norm(dx, dy); a.dx = n.x; a.dy = n.y; }
        }
      }
    }

    onUp(e) {
      const p = this.pointers.get(e.pointerId);
      this.pointers.delete(e.pointerId);
      if (!p) return;
      if (p.role === 'joy') {
        if (this.joy.id === e.pointerId) { this.joy.active = false; this.joy.id = null; this.joy.mag = 0; this.joy.dx = 0; this.joy.dy = 0; }
      } else if (p.role === 'aim') {
        const a = this.aims[p.idx];
        if (a) {
          let dir = a.dragging ? { x: a.dx, y: a.dy } : this.autoAimDir(p.idx);
          this.cast(p.idx, dir);
          this.aims[p.idx] = null;
        }
      }
    }

    onKey(e, down) {
      const k = e.key.toLowerCase();
      this.keys[k] = down;
      if (!down) return;
      if (this.state === 'menu') { if (k === ' ' || k === 'enter') this.startRun(); return; }
      if (this.state === 'gameover') { if (k === ' ' || k === 'enter') this.state = 'menu'; return; }
      if (this.state === 'levelup') {
        if (k === '1' || k === '2' || k === '3') this.chooseUpgrade(parseInt(k, 10) - 1);
        return;
      }
      if (this.state === 'paused') { if (k === 'p' || k === 'escape') this.state = 'playing'; return; }
      // playing
      const map = { q: 0, w: 1, e: 2, r: 3, '1': 0, '2': 1, '3': 2, '4': 3 };
      if (k in map) this.cast(map[k], this.aimFromScreen(this.mouse.x, this.mouse.y));
      if (k === 'p' || k === 'escape') this.state = 'paused';
    }

    keyboardMove() {
      let dx = 0, dy = 0;
      if (this.keys['a'] || this.keys['arrowleft']) dx -= 1;
      if (this.keys['d'] || this.keys['arrowright']) dx += 1;
      if (this.keys['w'] || this.keys['arrowup']) dy -= 1;
      if (this.keys['s'] || this.keys['arrowdown']) dy += 1;
      if (dx === 0 && dy === 0) return null;
      const n = norm(dx, dy);
      return { dx: n.x, dy: n.y, mag: 1 };
    }

    aimFromScreen(sx, sy) {
      // screen point -> world direction from player
      const wx = sx - this.viewW / 2 + this.cam.x;
      const wy = sy - this.viewH / 2 + this.cam.y;
      return norm(wx - this.player.x, wy - this.player.y);
    }

    hit(r, x, y) { return x >= r.x && x <= r.x + r.w && y >= r.y && y <= r.y + r.h; }

    /* ---------------------------- loop ---------------------------- */
    frame(t) {
      let dt = (t - this.last) / 1000;
      this.last = t;
      if (dt > 0.05) dt = 0.05;
      try {
        this.update(dt);
        this.draw();
      } catch (err) {
        if (!this._loggedErr) { console.error('loop error', err); this._loggedErr = true; }
      }
      requestAnimationFrame((tt) => this.frame(tt));
    }

    update(dt) {
      if (this.state !== 'playing') { this.updateFx(dt); return; }
      this.time += dt;

      // movement (keyboard takes over if used, else joystick)
      const kb = this.keyboardMove();
      let mvx = 0, mvy = 0, mag = 0;
      if (kb) { mvx = kb.dx; mvy = kb.dy; mag = kb.mag; }
      else if (this.joy.mag > 0.02) { mvx = this.joy.dx; mvy = this.joy.dy; mag = this.joy.mag; }
      const pl = this.player;
      if (mag > 0.02) {
        const sp = pl.speed * pl.moveMul;
        pl.x += mvx * mag * sp * dt; pl.y += mvy * mag * sp * dt;
        this.lastAim = { x: mvx, y: mvy };
      }
      if (pl.iframes > 0) pl.iframes -= dt;
      if (pl.hp < pl.maxHp) pl.hp = Math.min(pl.maxHp, pl.hp + pl.regen * dt);

      this.autoAttack(dt);
      this.spawn(dt);
      this.updateEnemies(dt);
      this.updateShots(dt);
      this.updateEnemyShots(dt);
      this.updateHazards(dt);
      this.updateGems(dt);
      this.updateFx(dt);

      for (const s of this.skills) if (s.cd > 0) s.cd = Math.max(0, s.cd - dt);
      if (this.shake > 0) this.shake = Math.max(0, this.shake - dt * 26);

      this.sweepDeaths();
      if (pl.hp <= 0) this.gameOver();
      this.cam.x = pl.x; this.cam.y = pl.y;
    }

    updateFx(dt) {
      for (let i = this.particles.length - 1; i >= 0; i--) {
        const p = this.particles[i];
        p.x += p.vx * dt; p.y += p.vy * dt; p.vx *= 0.9; p.vy *= 0.9;
        p.life -= dt; if (p.life <= 0) this.particles.splice(i, 1);
      }
      for (let i = this.rings.length - 1; i >= 0; i--) {
        const r = this.rings[i];
        r.life -= dt;
        r.r = lerp(r.r, r.maxR, 1 - clamp(r.life / r.maxLife, 0, 1));
        if (r.life <= 0) this.rings.splice(i, 1);
      }
      if (this.shake > 0 && this.state !== 'playing') this.shake = Math.max(0, this.shake - dt * 26);
    }

    /* ---------------------------- combat ---------------------------- */
    autoAttack(dt) {
      const pl = this.player;
      pl.autoTimer -= dt;
      if (pl.autoTimer > 0) return;
      const tgt = this.nearestEnemy(pl.x, pl.y, pl.autoRange);
      if (!tgt) { pl.autoTimer = 0.15; return; }
      pl.autoTimer = 1 / pl.autoRate;
      const base = Math.atan2(tgt.y - pl.y, tgt.x - pl.x);
      const n = pl.autoCount, spread = n > 1 ? 0.22 : 0;
      for (let i = 0; i < n; i++) {
        const a = base + (i - (n - 1) / 2) * spread;
        this.shots.push({
          x: pl.x, y: pl.y, vx: Math.cos(a) * pl.autoSpeed, vy: Math.sin(a) * pl.autoSpeed,
          radius: 9 * pl.projSizeMul, dmg: pl.autoDamage, pierce: pl.autoPierce,
          life: pl.autoRange / pl.autoSpeed, color: '#b4f0ff', knockback: 40,
          burnDps: 0, burnTime: 0, hit: new Set()
        });
      }
    }

    cast(idx, dir) {
      const st = this.skills[idx]; const def = SKILLS[idx];
      if (!st || !st.unlocked || st.cd > 0) return;
      const n = norm(dir.x, dir.y);
      this.lastAim = { x: n.x, y: n.y };
      st.cd = def.cd * st.cdMul * this.player.cdMul;
      const dmg = def.dmg * st.dmgMul * this.player.skillDmgMul;
      const pl = this.player;
      if (def.kind === 'line') {
        const r = def.radius * pl.projSizeMul * st.radiusMul;
        this.shots.push({
          x: pl.x, y: pl.y, vx: n.x * def.speed, vy: n.y * def.speed, radius: r,
          dmg, pierce: def.pierce + this.boltExtraPierce, life: (def.range * st.rangeMul) / def.speed,
          color: def.color, knockback: def.knockback, burnDps: 0, burnTime: 0, hit: new Set()
        });
        this.addParticles(pl.x, pl.y, 6, def.color, 160, 0.3);
      } else if (def.kind === 'lob') {
        const range = def.range * st.rangeMul;
        const lx = pl.x + n.x * range, ly = pl.y + n.y * range;
        const blast = def.blast * pl.projSizeMul * st.radiusMul;
        this.hazards.push({ x: lx, y: ly, radius: blast, timer: 0.55, maxTimer: 0.55, dmg,
          color: def.color, knockback: 60, burnDps: def.burnDps, burnTime: def.burnTime, slowFactor: 1, slowTime: 0, det: false });
      } else if (def.kind === 'dash') {
        const range = def.range * st.rangeMul;
        const sx = pl.x, sy = pl.y, ex = pl.x + n.x * range, ey = pl.y + n.y * range;
        pl.x = ex; pl.y = ey; pl.iframes = Math.max(pl.iframes, def.iframes);
        const hitR = def.radius * pl.projSizeMul * st.radiusMul;
        for (const en of this.enemies) {
          if (en.hp <= 0) continue;
          if (distPointSeg(en.x, en.y, sx, sy, ex, ey) < hitR + en.radius)
            this.damage(en, dmg, n.x, n.y, def.knockback, 0, 0);
        }
        for (let k = 0; k <= 10; k++) this.addParticles(lerp(sx, ex, k / 10), lerp(sy, ey, k / 10), 1, def.color, 40, 0.35);
        this.rings.push({ x: ex, y: ey, r: hitR * 0.4, maxR: hitR, life: 0.4, maxLife: 0.4, color: def.color, w: 6 });
        this.shake = Math.max(this.shake, 4);
      } else if (def.kind === 'nova') {
        const blast = def.blast * pl.projSizeMul * st.radiusMul;
        this.explode(pl.x, pl.y, blast, dmg, def.knockback, 0, 0, def.slowFactor, def.slowTime, def.color);
        this.rings.push({ x: pl.x, y: pl.y, r: 30, maxR: blast, life: 0.55, maxLife: 0.55, color: def.color, w: 14 });
        this.rings.push({ x: pl.x, y: pl.y, r: 10, maxR: blast * 0.7, life: 0.4, maxLife: 0.4, color: '#ffffff', w: 8 });
        this.shake = Math.max(this.shake, 16);
      }
    }

    explode(cx, cy, radius, dmg, knockback, burnDps, burnTime, slowFactor, slowTime, color) {
      for (const en of this.enemies) {
        if (en.hp <= 0) continue;
        if (dist(cx, cy, en.x, en.y) < radius + en.radius) {
          const n = norm(en.x - cx, en.y - cy);
          this.damage(en, dmg, n.x, n.y, knockback, burnDps, burnTime, slowFactor, slowTime);
        }
      }
      this.addParticles(cx, cy, 24, color, 260, 0.6);
    }

    damage(en, dmg, kbx, kby, knockback, burnDps, burnTime, slowFactor, slowTime) {
      if (en.hp <= 0) return;
      en.hp -= dmg; en.hitFlash = 0.09;
      if (knockback > 0) { const k = en.kind === 'brute' ? knockback * 0.3 : knockback; en.kx += kbx * k; en.ky += kby * k; }
      if (burnTime > 0) { en.burnDps = Math.max(en.burnDps, burnDps); en.burnTimer = Math.max(en.burnTimer, burnTime); }
      if (slowTime > 0) { en.slowFactor = Math.min(en.slowFactor, slowFactor); en.slowTimer = Math.max(en.slowTimer, slowTime); }
    }

    /* ---------------------------- spawning ---------------------------- */
    spawn(dt) {
      this.spawnTimer -= dt;
      if (this.spawnTimer > 0) return;
      const interval = clamp(0.95 - this.time * 0.006, 0.18, 0.95);
      this.spawnTimer = interval;
      const batch = 1 + Math.floor(this.time / 35);
      for (let i = 0; i < batch; i++) this.spawnOne();
      if (this.time > 30 && chance(0.04 + this.time * 0.0002)) this.spawnOne('brute');
    }

    spawnOne(force) {
      const kind = force || this.rollKind();
      const ang = rand(0, TAU), d = Math.max(this.viewW, this.viewH) * 0.62 + rand(0, 160);
      const pl = this.player;
      const e = { x: pl.x + Math.cos(ang) * d, y: pl.y + Math.sin(ang) * d, kind,
        radius: 16, maxHp: 20, hp: 20, speed: 80, touchDmg: 8, xp: 1,
        hitFlash: 0, slowTimer: 0, slowFactor: 1, burnTimer: 0, burnDps: 0,
        kx: 0, ky: 0, shootTimer: 0, counted: false };
      const hp = 1 + this.time / 50, dm = 1 + this.time / 110;
      if (kind === 'grunt') { e.radius = 17; e.maxHp = 22 * hp; e.speed = rand(72, 92); e.touchDmg = 16 * dm; e.xp = 1; }
      else if (kind === 'runner') { e.radius = 13; e.maxHp = 12 * hp; e.speed = rand(150, 185); e.touchDmg = 12 * dm; e.xp = 1; }
      else if (kind === 'brute') { e.radius = 30; e.maxHp = 120 * hp; e.speed = rand(48, 62); e.touchDmg = 34 * dm; e.xp = 5; }
      else { e.radius = 16; e.maxHp = 30 * hp; e.speed = rand(70, 88); e.touchDmg = 14 * dm; e.xp = 3; e.shootTimer = rand(1.2, 2.4); }
      e.hp = e.maxHp;
      this.enemies.push(e);
    }

    rollKind() {
      const t = this.time, r = Math.random();
      if (t < 20) return r < 0.85 ? 'grunt' : 'runner';
      if (t < 45) return r < 0.55 ? 'grunt' : r < 0.85 ? 'runner' : 'brute';
      return r < 0.42 ? 'grunt' : r < 0.70 ? 'runner' : r < 0.86 ? 'caster' : 'brute';
    }

    /* ---------------------------- entity updates ---------------------------- */
    updateEnemies(dt) {
      const pl = this.player;
      for (const e of this.enemies) {
        if (e.hp <= 0) continue;
        if (e.slowTimer > 0) e.slowTimer -= dt; else e.slowFactor = 1;
        if (e.burnTimer > 0) { e.burnTimer -= dt; e.hp -= e.burnDps * dt; if (chance(0.3)) this.addParticles(e.x, e.y, 1, '#ff8c3c', 60, 0.4); }
        if (e.hitFlash > 0) e.hitFlash -= dt;
        e.x += e.kx * dt; e.y += e.ky * dt; e.kx *= 0.86; e.ky *= 0.86;
        const n = norm(pl.x - e.x, pl.y - e.y);
        const d = dist(pl.x, pl.y, e.x, e.y);
        const sp = e.speed * e.slowFactor;
        if (e.kind === 'caster') {
          if (d > 330) { e.x += n.x * sp * dt; e.y += n.y * sp * dt; }
          else { e.x += -n.y * sp * 0.4 * dt; e.y += n.x * sp * 0.4 * dt; }
          e.shootTimer -= dt;
          if (e.shootTimer <= 0 && d < 560) {
            e.shootTimer = rand(2.0, 3.0);
            this.eshots.push({ x: e.x, y: e.y, vx: n.x * 300, vy: n.y * 300, radius: 9, dmg: e.touchDmg * 0.9, life: 3.2, color: '#78e6c8' });
          }
        } else { e.x += n.x * sp * dt; e.y += n.y * sp * dt; }
        if (d < pl.radius + e.radius && pl.iframes <= 0) { pl.hp -= e.touchDmg * dt; if (chance(0.2)) this.shake = Math.max(this.shake, 3); }
      }
      this.separate();
    }

    separate() {
      const arr = this.enemies, n = arr.length;
      for (let i = 0; i < n; i++) {
        const a = arr[i]; if (a.hp <= 0) continue;
        for (let j = i + 1; j < n; j++) {
          const b = arr[j]; if (b.hp <= 0) continue;
          const dx = b.x - a.x, dy = b.y - a.y, rr = a.radius + b.radius, d2 = dx * dx + dy * dy;
          if (d2 > 0.0001 && d2 < rr * rr) {
            const d = Math.sqrt(d2), push = (rr - d) * 0.5, nx = dx / d, ny = dy / d;
            a.x -= nx * push; a.y -= ny * push; b.x += nx * push; b.y += ny * push;
          }
        }
      }
    }

    updateShots(dt) {
      for (let i = this.shots.length - 1; i >= 0; i--) {
        const s = this.shots[i];
        s.x += s.vx * dt; s.y += s.vy * dt; s.life -= dt;
        let dead = s.life <= 0;
        if (!dead) {
          for (const e of this.enemies) {
            if (e.hp <= 0 || s.hit.has(e)) continue;
            if (dist(s.x, s.y, e.x, e.y) < s.radius + e.radius) {
              s.hit.add(e);
              const n = norm(s.vx, s.vy);
              this.damage(e, s.dmg, n.x, n.y, s.knockback, s.burnDps, s.burnTime);
              this.addParticles(s.x, s.y, 3, s.color, 140, 0.3);
              if (s.pierce <= 0) { dead = true; break; }
              s.pierce--;
            }
          }
        }
        if (dead) this.shots.splice(i, 1);
      }
    }

    updateEnemyShots(dt) {
      const pl = this.player;
      for (let i = this.eshots.length - 1; i >= 0; i--) {
        const s = this.eshots[i];
        s.x += s.vx * dt; s.y += s.vy * dt; s.life -= dt;
        if (s.life <= 0) { this.eshots.splice(i, 1); continue; }
        if (pl.iframes <= 0 && dist(s.x, s.y, pl.x, pl.y) < pl.radius + s.radius) {
          pl.hp -= s.dmg; this.shake = Math.max(this.shake, 5);
          this.addParticles(s.x, s.y, 6, s.color, 120, 0.4);
          this.eshots.splice(i, 1);
        }
      }
    }

    updateHazards(dt) {
      for (let i = this.hazards.length - 1; i >= 0; i--) {
        const h = this.hazards[i];
        h.timer -= dt;
        if (h.timer <= 0 && !h.det) {
          h.det = true;
          this.explode(h.x, h.y, h.radius, h.dmg, h.knockback, h.burnDps, h.burnTime, h.slowFactor, h.slowTime, h.color);
          this.rings.push({ x: h.x, y: h.y, r: h.radius * 0.3, maxR: h.radius, life: 0.4, maxLife: 0.4, color: h.color, w: 12 });
          this.shake = Math.max(this.shake, 9);
          this.hazards.splice(i, 1);
        }
      }
    }

    updateGems(dt) {
      const pl = this.player;
      for (let i = this.gems.length - 1; i >= 0; i--) {
        const g = this.gems[i];
        const d = dist(g.x, g.y, pl.x, pl.y);
        if (d < pl.pickupRadius || g.attracted) {
          g.attracted = true;
          const n = norm(pl.x - g.x, pl.y - g.y);
          const pull = lerp(520, 980, 1 - clamp(d / Math.max(1, pl.pickupRadius), 0, 1));
          g.x += n.x * pull * dt; g.y += n.y * pull * dt;
        }
        if (d < pl.radius + g.radius) {
          this.gainXp(g.value);
          this.addParticles(g.x, g.y, 3, '#78f0c8', 120, 0.25);
          this.gems.splice(i, 1);
        }
      }
    }

    gainXp(v) {
      const pl = this.player;
      pl.xp += v;
      while (pl.xp >= pl.xpToNext) {
        pl.xp -= pl.xpToNext; pl.level += 1;
        pl.xpToNext = 5 + pl.level * 4 + pl.level * pl.level * 0.25;
        this.pendingLevels += 1;
      }
      if (this.pendingLevels > 0 && this.state === 'playing') this.openLevelUp();
    }

    openLevelUp() { this.choices = this.rollUpgrades(3); this.state = 'levelup'; }

    chooseUpgrade(i) {
      const u = this.choices[i];
      if (u) u.apply();
      this.pendingLevels -= 1;
      if (this.pendingLevels > 0) this.choices = this.rollUpgrades(3);
      else { this.choices = []; this.state = 'playing'; }
    }

    rollUpgrades(count) {
      const pl = this.player, s = this.skills, list = [];
      const A = (title, desc, color, apply) => list.push({ title, desc, color, apply });
      A('Vitality', '+25 Max HP and heal', '#78e68c', () => { pl.maxHp += 25; pl.hp = Math.min(pl.maxHp, pl.hp + 40); });
      A('Regeneration', '+0.8 HP / sec', '#78e68c', () => { pl.regen += 0.8; });
      A('Swift Boots', '+12% Move Speed', '#78c8ff', () => { pl.moveMul *= 1.12; });
      A('Magnet', '+35% Pickup Range', '#78c8ff', () => { pl.pickupRadius *= 1.35; });
      A('Sharpened Bolts', '+30% Basic Damage', '#ff7878', () => { pl.autoDamage *= 1.3; });
      A('Rapid Fire', '+25% Basic Attack Speed', '#ff7878', () => { pl.autoRate *= 1.25; });
      if (pl.autoCount < 5) A('Multishot', '+1 Basic projectile', '#ff7878', () => { pl.autoCount += 1; });
      if (pl.autoPierce < 4) A('Penetration', 'Basic attacks pierce +1', '#ff7878', () => { pl.autoPierce += 1; });
      A('Arcane Power', '+18% Skill Damage', '#ffc85a', () => { pl.skillDmgMul *= 1.18; });
      A('Cooldown Matrix', '-12% Skill Cooldowns', '#ffc85a', () => { pl.cdMul *= 0.88; });
      A('Big Spells', '+15% Skill Size', '#ffc85a', () => { pl.projSizeMul *= 1.15; });
      for (let i = 1; i < s.length; i++) {
        const st = s[i], def = SKILLS[i];
        if (!st.unlocked) A('Unlock: ' + def.name + ' [' + def.key + ']', def.desc, def.color, () => { st.unlocked = true; st.level = 1; });
        else if (st.level < 5) A(def.name + ' +', 'Rank up: +25% dmg, -8% CD', def.color, () => { st.level++; st.dmgMul *= 1.25; st.cdMul *= 0.92; st.radiusMul *= 1.06; });
      }
      const q = s[0];
      if (q.level < 6) A('Arcane Bolt +', 'Rank up: +20% dmg, +1 pierce', SKILLS[0].color, () => { q.level++; q.dmgMul *= 1.2; this.boltExtraPierce += 1; });
      for (let i = list.length - 1; i > 0; i--) { const j = randInt(0, i); const t = list[i]; list[i] = list[j]; list[j] = t; }
      return list.slice(0, Math.min(count, list.length));
    }

    /* ---------------------------- deaths ---------------------------- */
    sweepDeaths() {
      for (const e of this.enemies) if (e.hp <= 0 && !e.counted) { e.counted = true; this.onDeath(e); }
      this.enemies = this.enemies.filter((e) => e.hp > 0);
    }

    onDeath(e) {
      this.player.kills += 1;
      this.addParticles(e.x, e.y, 10, this.enemyColor(e.kind), 200, 0.5);
      const drops = e.kind === 'brute' ? 3 : 1;
      for (let k = 0; k < drops; k++)
        this.gems.push({ x: e.x + rand(-10, 10), y: e.y + rand(-10, 10), value: drops > 1 ? 2 : e.xp, radius: 7, attracted: false });
    }

    gameOver() {
      this.state = 'gameover';
      if (this.time > this.best) { this.best = this.time; localStorage.setItem('rift_best', String(this.best)); }
    }

    /* ---------------------------- helpers ---------------------------- */
    nearestEnemy(x, y, range) {
      let best = null, bd = range * range;
      for (const e of this.enemies) { if (e.hp <= 0) continue; const d = dist2(x, y, e.x, e.y); if (d < bd) { bd = d; best = e; } }
      return best;
    }

    autoAimDir(idx) {
      const def = SKILLS[idx];
      const t = this.nearestEnemy(this.player.x, this.player.y, def.range * 1.5);
      if (t) return norm(t.x - this.player.x, t.y - this.player.y);
      return { x: this.lastAim.x, y: this.lastAim.y };
    }

    addParticles(x, y, n, color, speed, life) {
      for (let i = 0; i < n; i++) {
        const a = rand(0, TAU), sp = rand(speed * 0.3, speed);
        this.particles.push({ x, y, vx: Math.cos(a) * sp, vy: Math.sin(a) * sp, life, maxLife: life, size: rand(2, 4.5), color });
      }
    }

    enemyColor(k) { return k === 'grunt' ? '#ce5480' : k === 'runner' ? '#f0d060' : k === 'brute' ? '#b44040' : '#60ceb2'; }

    /* ============================ rendering ============================ */
    draw() {
      const ctx = this.ctx, w = this.viewW, h = this.viewH;
      const ox = this.shake > 0.2 ? rand(-this.shake, this.shake) : 0;
      const oy = this.shake > 0.2 ? rand(-this.shake, this.shake) : 0;
      const camX = (this.cam ? this.cam.x : 0), camY = (this.cam ? this.cam.y : 0);
      const sx = w / 2 - camX + ox, sy = h / 2 - camY + oy;

      // background
      ctx.fillStyle = '#0c0a16'; ctx.fillRect(0, 0, w, h);
      this.drawGrid(ctx, sx, sy);

      if (this.player) {
        // gems
        ctx.fillStyle = '#5af0be';
        for (const g of this.gems) { ctx.beginPath(); ctx.arc(g.x + sx, g.y + sy, g.radius, 0, TAU); ctx.fill(); }
        // hazards
        for (const hz of this.hazards) {
          const frac = 1 - clamp(hz.timer / hz.maxTimer, 0, 1);
          ctx.strokeStyle = hz.color; ctx.lineWidth = 4;
          ctx.beginPath(); ctx.arc(hz.x + sx, hz.y + sy, hz.radius, 0, TAU); ctx.stroke();
          ctx.globalAlpha = 0.35 * frac; ctx.fillStyle = hz.color;
          ctx.beginPath(); ctx.arc(hz.x + sx, hz.y + sy, hz.radius * frac, 0, TAU); ctx.fill();
          ctx.globalAlpha = 1;
        }
        // enemy shots
        for (const s of this.eshots) { ctx.fillStyle = s.color; ctx.beginPath(); ctx.arc(s.x + sx, s.y + sy, s.radius, 0, TAU); ctx.fill(); }
        this.drawEnemies(ctx, sx, sy);
        // player shots
        for (const s of this.shots) { ctx.fillStyle = s.color; ctx.beginPath(); ctx.arc(s.x + sx, s.y + sy, s.radius, 0, TAU); ctx.fill(); }
        this.drawPlayer(ctx, sx, sy);
        this.drawRings(ctx, sx, sy);
        this.drawParticles(ctx, sx, sy);
        if (this.state === 'playing') for (const a of this.aims) if (a) this.drawAim(ctx, a, sx, sy);
      }

      if (this.state === 'playing' || this.state === 'paused') this.drawControls(ctx);
      if (this.player) this.drawHud(ctx);

      if (this.state === 'menu') this.drawMenu(ctx);
      else if (this.state === 'levelup') this.drawLevelUp(ctx);
      else if (this.state === 'paused') this.drawPaused(ctx);
      else if (this.state === 'gameover') this.drawGameOver(ctx);
    }

    drawGrid(ctx, sx, sy) {
      const sp = 72; ctx.strokeStyle = 'rgba(90,100,160,0.16)'; ctx.lineWidth = 1.5;
      ctx.beginPath();
      for (let gx = sx % sp; gx < this.viewW; gx += sp) { ctx.moveTo(gx, 0); ctx.lineTo(gx, this.viewH); }
      for (let gy = sy % sp; gy < this.viewH; gy += sp) { ctx.moveTo(0, gy); ctx.lineTo(this.viewW, gy); }
      ctx.stroke();
    }

    drawEnemies(ctx, sx, sy) {
      for (const e of this.enemies) {
        const x = e.x + sx, y = e.y + sy;
        if (x < -60 || y < -60 || x > this.viewW + 60 || y > this.viewH + 60) continue;
        ctx.fillStyle = e.hitFlash > 0 ? '#ffffff' : this.enemyColor(e.kind);
        ctx.beginPath(); ctx.arc(x, y, e.radius, 0, TAU); ctx.fill();
        ctx.strokeStyle = 'rgba(0,0,0,0.6)'; ctx.lineWidth = 3;
        ctx.beginPath(); ctx.arc(x, y, e.radius, 0, TAU); ctx.stroke();
        if (e.hp < e.maxHp) {
          const frac = clamp(e.hp / e.maxHp, 0, 1);
          ctx.strokeStyle = '#3cdc5a'; ctx.lineWidth = 3;
          ctx.beginPath(); ctx.arc(x, y, e.radius + 5, -Math.PI / 2, -Math.PI / 2 + TAU * frac); ctx.stroke();
        }
      }
    }

    drawPlayer(ctx, sx, sy) {
      const pl = this.player, x = pl.x + sx, y = pl.y + sy;
      ctx.fillStyle = pl.iframes > 0 ? 'rgba(120,230,255,0.7)' : '#5ac8ff';
      ctx.beginPath(); ctx.arc(x, y, pl.radius, 0, TAU); ctx.fill();
      ctx.strokeStyle = '#fff'; ctx.lineWidth = 3;
      ctx.beginPath(); ctx.arc(x, y, pl.radius, 0, TAU); ctx.stroke();
      const a = Math.atan2(this.lastAim.y, this.lastAim.x);
      ctx.fillStyle = '#fff';
      ctx.beginPath(); ctx.arc(x + Math.cos(a) * pl.radius, y + Math.sin(a) * pl.radius, 5, 0, TAU); ctx.fill();
    }

    drawRings(ctx, sx, sy) {
      for (const r of this.rings) {
        ctx.globalAlpha = clamp(r.life / r.maxLife, 0, 1);
        ctx.strokeStyle = r.color; ctx.lineWidth = r.w;
        ctx.beginPath(); ctx.arc(r.x + sx, r.y + sy, r.r, 0, TAU); ctx.stroke();
      }
      ctx.globalAlpha = 1;
    }

    drawParticles(ctx, sx, sy) {
      for (const p of this.particles) {
        ctx.globalAlpha = clamp(p.life / p.maxLife, 0, 1);
        ctx.fillStyle = p.color;
        ctx.beginPath(); ctx.arc(p.x + sx, p.y + sy, p.size, 0, TAU); ctx.fill();
      }
      ctx.globalAlpha = 1;
    }

    drawAim(ctx, a, sx, sy) {
      const def = SKILLS[a.idx], st = this.skills[a.idx], pl = this.player;
      let dx = a.dx, dy = a.dy;
      if (!a.dragging) { const d = this.autoAimDir(a.idx); dx = d.x; dy = d.y; }
      const px = pl.x + sx, py = pl.y + sy;
      ctx.save();
      if (def.kind === 'line' || def.kind === 'dash') {
        const range = def.range * st.rangeMul, ex = px + dx * range, ey = py + dy * range;
        const ww = def.radius * pl.projSizeMul * st.radiusMul;
        ctx.lineCap = 'round'; ctx.globalAlpha = 0.25; ctx.strokeStyle = def.color; ctx.lineWidth = ww * 2;
        ctx.beginPath(); ctx.moveTo(px, py); ctx.lineTo(ex, ey); ctx.stroke();
        ctx.globalAlpha = 0.9; ctx.lineWidth = 4;
        ctx.beginPath(); ctx.moveTo(px, py); ctx.lineTo(ex, ey); ctx.stroke();
        ctx.fillStyle = def.color; ctx.beginPath(); ctx.arc(ex, ey, 8, 0, TAU); ctx.fill();
      } else if (def.kind === 'lob') {
        const range = def.range * st.rangeMul, ex = px + dx * range, ey = py + dy * range;
        const blast = def.blast * pl.projSizeMul * st.radiusMul;
        ctx.globalAlpha = 0.22; ctx.fillStyle = def.color; ctx.beginPath(); ctx.arc(ex, ey, blast, 0, TAU); ctx.fill();
        ctx.globalAlpha = 0.9; ctx.strokeStyle = def.color; ctx.lineWidth = 4; ctx.beginPath(); ctx.arc(ex, ey, blast, 0, TAU); ctx.stroke();
      } else if (def.kind === 'nova') {
        const blast = def.blast * pl.projSizeMul * st.radiusMul;
        ctx.globalAlpha = 0.22; ctx.fillStyle = def.color; ctx.beginPath(); ctx.arc(px, py, blast, 0, TAU); ctx.fill();
        ctx.globalAlpha = 0.9; ctx.strokeStyle = def.color; ctx.lineWidth = 4; ctx.beginPath(); ctx.arc(px, py, blast, 0, TAU); ctx.stroke();
      }
      ctx.restore();
    }

    /* ---------------------------- controls + HUD ---------------------------- */
    drawControls(ctx) {
      if (this.joy.active) {
        ctx.strokeStyle = 'rgba(255,255,255,0.28)'; ctx.lineWidth = 4;
        ctx.beginPath(); ctx.arc(this.joy.ox, this.joy.oy, this.joyMaxR, 0, TAU); ctx.stroke();
        ctx.fillStyle = 'rgba(200,220,255,0.5)';
        ctx.beginPath(); ctx.arc(this.joy.kx, this.joy.ky, this.joyMaxR * 0.42, 0, TAU); ctx.fill();
      }
      for (const p of this.pads) {
        const st = this.skills[p.idx], def = SKILLS[p.idx];
        ctx.fillStyle = st.unlocked ? 'rgba(24,28,44,0.6)' : 'rgba(40,44,60,0.5)';
        ctx.beginPath(); ctx.arc(p.x, p.y, p.r, 0, TAU); ctx.fill();
        ctx.strokeStyle = st.unlocked ? def.color : 'rgba(90,90,110,0.5)'; ctx.lineWidth = 5;
        ctx.beginPath(); ctx.arc(p.x, p.y, p.r, 0, TAU); ctx.stroke();
        if (st.unlocked) {
          if (st.cd > 0) {
            const frac = clamp(st.cd / (def.cd * st.cdMul * this.player.cdMul), 0, 1);
            ctx.fillStyle = 'rgba(8,10,18,0.6)';
            ctx.beginPath(); ctx.moveTo(p.x, p.y);
            ctx.arc(p.x, p.y, p.r, -Math.PI / 2, -Math.PI / 2 + TAU * frac); ctx.closePath(); ctx.fill();
          }
          ctx.fillStyle = '#fff'; ctx.font = 'bold ' + (p.r * 0.85) + 'px system-ui'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
          ctx.fillText(def.key, p.x, p.y);
        } else {
          ctx.fillStyle = 'rgba(200,200,220,0.8)'; ctx.font = (p.r * 0.6) + 'px system-ui'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
          ctx.fillText('🔒', p.x, p.y);
        }
      }
    }

    drawHud(ctx) {
      const w = this.viewW, pl = this.player;
      // xp bar
      const xpFrac = clamp(pl.xp / pl.xpToNext, 0, 1);
      ctx.fillStyle = 'rgba(20,24,40,0.7)'; ctx.fillRect(0, 0, w, 10);
      ctx.fillStyle = '#5adcff'; ctx.fillRect(0, 0, w * xpFrac, 10);
      // hp
      const hx = 16, hy = 22, hw = w * 0.34, hh = 24;
      ctx.fillStyle = 'rgba(30,12,16,0.7)'; this.rr(ctx, hx, hy, hw, hh, 8); ctx.fill();
      ctx.fillStyle = '#e64650'; this.rr(ctx, hx, hy, hw * clamp(pl.hp / pl.maxHp, 0, 1), hh, 8); ctx.fill();
      ctx.fillStyle = '#fff'; ctx.font = 'bold 16px system-ui'; ctx.textAlign = 'left'; ctx.textBaseline = 'middle';
      ctx.fillText(Math.ceil(pl.hp) + ' / ' + Math.round(pl.maxHp), hx + 10, hy + hh / 2);
      ctx.font = 'bold 18px system-ui'; ctx.fillText('Lv ' + pl.level, hx, hy + hh + 16);
      // timer + kills
      ctx.textAlign = 'center';
      ctx.font = 'bold 30px system-ui'; ctx.fillText(fmtTime(this.time), w / 2, 24);
      ctx.font = '16px system-ui'; ctx.fillStyle = 'rgba(230,230,240,0.9)'; ctx.fillText('Kills ' + pl.kills, w / 2, 50);
      // pause btn
      const pb = this.pauseBtn();
      ctx.fillStyle = 'rgba(30,34,52,0.6)'; this.rr(ctx, pb.x, pb.y, pb.w, pb.h, 10); ctx.fill();
      ctx.fillStyle = '#fff'; ctx.fillRect(pb.x + pb.w / 2 - 9, pb.y + 12, 5, pb.h - 24); ctx.fillRect(pb.x + pb.w / 2 + 4, pb.y + 12, 5, pb.h - 24);
    }

    rr(ctx, x, y, w, h, r) {
      r = Math.min(r, w / 2, h / 2); if (w < 0) w = 0;
      ctx.beginPath();
      ctx.moveTo(x + r, y); ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r);
      ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.closePath();
    }

    /* ---------------------------- menus ---------------------------- */
    dim(ctx, a) { ctx.fillStyle = 'rgba(0,0,0,' + a + ')'; ctx.fillRect(0, 0, this.viewW, this.viewH); }

    drawMenu(ctx) {
      const w = this.viewW, h = this.viewH;
      this.dim(ctx, 0.72);
      ctx.textAlign = 'center'; ctx.textBaseline = 'alphabetic';
      ctx.fillStyle = '#78dcff'; ctx.font = 'bold ' + clamp(w * 0.07, 40, 72) + 'px system-ui';
      ctx.fillText('RIFT SURVIVORS', w / 2, h * 0.3);
      ctx.fillStyle = 'rgba(230,230,240,0.9)'; ctx.font = '22px system-ui';
      ctx.fillText('Survive the swarm — MOBA skillshots', w / 2, h * 0.3 + 38);
      if (this.best > 0) { ctx.fillStyle = '#ffd278'; ctx.font = '22px system-ui'; ctx.fillText('Best: ' + fmtTime(this.best), w / 2, h * 0.45); }
      const pulse = 0.6 + 0.4 * Math.sin(performance.now() / 250);
      ctx.fillStyle = 'rgba(255,255,255,' + pulse + ')'; ctx.font = 'bold 32px system-ui';
      ctx.fillText('TAP / CLICK TO PLAY', w / 2, h * 0.6);
      ctx.fillStyle = 'rgba(200,210,230,0.85)'; ctx.font = '18px system-ui';
      ctx.fillText('Left thumb = move • Right pads = aim & cast', w / 2, h * 0.72);
      ctx.fillText('Drag a skill pad to aim the skillshot, release to fire', w / 2, h * 0.72 + 26);
      ctx.fillText('Desktop: WASD move • mouse aim • Q W E R cast', w / 2, h * 0.72 + 52);
    }

    drawPaused(ctx) {
      const w = this.viewW, h = this.viewH;
      this.dim(ctx, 0.65);
      ctx.textAlign = 'center';
      ctx.fillStyle = '#fff'; ctx.font = 'bold 48px system-ui'; ctx.fillText('PAUSED', w / 2, h * 0.34);
      this.button(ctx, this.btnResume(), 'RESUME', '#5ac8ff');
      this.button(ctx, this.btnRestart(), 'RESTART', '#e65a64');
    }

    drawGameOver(ctx) {
      const w = this.viewW, h = this.viewH, pl = this.player;
      this.dim(ctx, 0.74);
      ctx.textAlign = 'center';
      ctx.fillStyle = '#ff5a6e'; ctx.font = 'bold 56px system-ui'; ctx.fillText('YOU DIED', w / 2, h * 0.3);
      ctx.fillStyle = '#fff'; ctx.font = '28px system-ui';
      ctx.fillText('Survived ' + fmtTime(this.time), w / 2, h * 0.3 + 48);
      ctx.fillText('Level ' + pl.level + ' • ' + pl.kills + ' kills', w / 2, h * 0.3 + 86);
      if (this.best > 0) { ctx.fillStyle = '#ffd278'; ctx.font = '22px system-ui'; ctx.fillText('Best: ' + fmtTime(this.best), w / 2, h * 0.3 + 124); }
      const pulse = 0.6 + 0.4 * Math.sin(performance.now() / 250);
      ctx.fillStyle = 'rgba(255,255,255,' + pulse + ')'; ctx.font = 'bold 28px system-ui';
      ctx.fillText('TAP / CLICK TO CONTINUE', w / 2, h * 0.7);
    }

    drawLevelUp(ctx) {
      const w = this.viewW, h = this.viewH;
      this.dim(ctx, 0.78);
      ctx.textAlign = 'center';
      ctx.fillStyle = '#ffd778'; ctx.font = 'bold 42px system-ui'; ctx.fillText('LEVEL UP!', w / 2, h * 0.16);
      ctx.fillStyle = 'rgba(220,220,235,0.9)'; ctx.font = '20px system-ui'; ctx.fillText('Choose an upgrade', w / 2, h * 0.16 + 32);
      const rects = this.cardRects();
      for (let i = 0; i < this.choices.length; i++) {
        const r = rects[i], u = this.choices[i];
        ctx.fillStyle = '#181a2a'; this.rr(ctx, r.x, r.y, r.w, r.h, 18); ctx.fill();
        ctx.strokeStyle = u.color; ctx.lineWidth = 4; this.rr(ctx, r.x, r.y, r.w, r.h, 18); ctx.stroke();
        ctx.fillStyle = u.color; ctx.globalAlpha = 0.18; this.rr(ctx, r.x, r.y, r.w, r.h * 0.26, 18); ctx.fill(); ctx.globalAlpha = 1;
        ctx.fillStyle = u.color; ctx.font = 'bold 24px system-ui';
        this.wrap(ctx, u.title, r.x + r.w / 2, r.y + r.h * 0.16, r.w - 28, 28);
        ctx.fillStyle = '#fff'; ctx.font = '19px system-ui';
        this.wrap(ctx, u.desc, r.x + r.w / 2, r.y + r.h * 0.42, r.w - 28, 24);
        ctx.fillStyle = 'rgba(200,210,230,0.7)'; ctx.font = '16px system-ui';
        ctx.fillText('TAP TO PICK', r.x + r.w / 2, r.y + r.h - 20);
      }
    }

    button(ctx, r, label, color) {
      ctx.fillStyle = '#181a2a'; this.rr(ctx, r.x, r.y, r.w, r.h, 14); ctx.fill();
      ctx.strokeStyle = color; ctx.lineWidth = 4; this.rr(ctx, r.x, r.y, r.w, r.h, 14); ctx.stroke();
      ctx.fillStyle = '#fff'; ctx.font = 'bold 26px system-ui'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
      ctx.fillText(label, r.x + r.w / 2, r.y + r.h / 2); ctx.textBaseline = 'alphabetic';
    }

    wrap(ctx, s, cx, top, maxW, lineH) {
      const words = s.split(' '); let line = ''; const lines = [];
      for (const wd of words) { const test = line ? line + ' ' + wd : wd; if (ctx.measureText(test).width > maxW && line) { lines.push(line); line = wd; } else line = test; }
      if (line) lines.push(line);
      let y = top; for (const l of lines) { ctx.fillText(l, cx, y); y += lineH; }
    }

    /* ---------------------------- geometry ---------------------------- */
    pauseBtn() { return { x: this.viewW - 66, y: 14, w: 52, h: 52 }; }
    btnResume() { const w = Math.min(this.viewW * 0.5, 360), x = (this.viewW - w) / 2; return { x, y: this.viewH * 0.46, w, h: 64 }; }
    btnRestart() { const w = Math.min(this.viewW * 0.5, 360), x = (this.viewW - w) / 2; return { x, y: this.viewH * 0.46 + 84, w, h: 64 }; }
    cardRects() {
      const n = 3, gap = this.viewW * 0.025;
      const cw = Math.min((this.viewW - gap * (n + 1)) / n, this.viewW * 0.28);
      const ch = this.viewH * 0.52, total = cw * n + gap * (n - 1);
      const startX = (this.viewW - total) / 2, top = this.viewH * 0.28;
      const out = [];
      for (let i = 0; i < n; i++) out.push({ x: startX + i * (cw + gap), y: top, w: cw, h: ch });
      return out;
    }
  }

  window.addEventListener('load', () => {
    const game = new Game(document.getElementById('game'));
    window.GAME = game; // exposed for debugging / automated screenshots
  });
})();
