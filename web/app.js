'use strict';
/* Dharma · versão web
 * Toda a linha do tempo (sinos, entrada e saída suave) é agendada no relógio do
 * AudioContext, então os tempos são exatos mesmo com a aba em segundo plano.
 * Loops: AudioBufferSourceNode.loop = true sobre arquivos periódicos (sem emenda). */

const MUSIC = [
  { id: 'music_templo', name: 'Templo' },
  { id: 'music_himalaia', name: 'Himalaia' },
  { id: 'music_lotus', name: 'Lótus' },
  { id: 'music_om', name: 'Om (drone)' },
];
const FX = [
  { id: 'rain', name: '🌧 Chuva' },
  { id: 'wind', name: '🍃 Vento' },
  { id: 'chimes', name: '🎐 Sinos de vento' },
  { id: 'stream', name: '💧 Riacho' },
  { id: 'ocean', name: '🌊 Ondas do mar' },
  { id: 'crickets', name: '🌙 Grilos' },
  { id: 'fire', name: '🔥 Fogueira' },
];

const DEFAULTS = {
  sessions: 3, minutes: 15, music: 'music_templo', musicVol: 60, bellVol: 80, fadeOut: 20,
  fx: Object.fromEntries(FX.map(f => [f.id, { on: false, vol: 50 }])),
};
let cfg = load();

function load() {
  try {
    const s = JSON.parse(localStorage.getItem('dharma.cfg'));
    return { ...DEFAULTS, ...s, fx: { ...DEFAULTS.fx, ...(s && s.fx) } };
  } catch (e) { return structuredClone(DEFAULTS); }
}
function save() { try { localStorage.setItem('dharma.cfg', JSON.stringify(cfg)); } catch (e) { /* ignora */ } }

const $ = id => document.getElementById(id);
const gainOf = v => (v / 100) ** 2;   // curva perceptual

function fmt(sec) {
  sec = Math.max(0, Math.ceil(sec));
  const h = Math.floor(sec / 3600), m = Math.floor(sec / 60) % 60, s = sec % 60;
  const p = n => String(n).padStart(2, '0');
  return h ? `${h}:${p(m)}:${p(s)}` : `${p(m)}:${p(s)}`;
}

/* ---------------------------------------------------------------- Interface */
function buildFxRows(container, live) {
  container.innerHTML = '';
  for (const f of FX) {
    const row = document.createElement('div');
    row.className = 'fx';
    row.innerHTML = `<span class="name">${f.name}</span>
      <label class="switch"><input type="checkbox"><i></i></label>
      <input type="range" min="0" max="100">`;
    const [chk, rng] = [row.querySelector('input[type=checkbox]'), row.querySelector('input[type=range]')];
    chk.checked = cfg.fx[f.id].on;
    rng.value = cfg.fx[f.id].vol;
    rng.disabled = !chk.checked;
    chk.onchange = () => { cfg.fx[f.id].on = chk.checked; rng.disabled = !chk.checked; save(); if (live) engine.sync(); else refreshFxRows(); };
    rng.oninput = () => { cfg.fx[f.id].vol = +rng.value; save(); if (live) engine.sync(); };
    container.appendChild(row);
  }
}
function refreshFxRows() { /* apenas mantém as duas listas coerentes */ buildFxRows($('fxList'), false); }

function buildMusicChips() {
  const box = $('musicChips');
  box.innerHTML = '';
  for (const m of [{ id: null, name: 'Nenhuma' }, ...MUSIC]) {
    const b = document.createElement('button');
    b.className = 'chip' + (cfg.music === m.id ? ' on' : '');
    b.textContent = m.name;
    b.onclick = () => { cfg.music = m.id; save(); buildMusicChips(); };
    box.appendChild(b);
  }
}

function clamp(v, lo, hi) { return Math.min(hi, Math.max(lo, v)); }

function updateSummary() {
  const n = cfg.sessions, d = cfg.minutes, tot = n * d;
  const h = Math.floor(tot / 60), m = tot % 60;
  const totTxt = h ? `${h} h ${m ? m + ' min' : ''}` : `${m} min`;
  $('summary').textContent = `${n} ${n === 1 ? 'sessão' : 'sessões'} de ${d} min = ${totTxt}`;
  const seg = `🔔 → ${d} min → `;
  $('flow').textContent = n <= 4 ? seg.repeat(n) + '🔔' : `${seg}🔔 → ${d} min → … (${n} sessões, sem intervalo) … → 🔔`;
}

function initSetup() {
  $('sessions').value = cfg.sessions;
  $('minutes').value = cfg.minutes;
  $('musicVol').value = cfg.musicVol;
  $('liveMusic').value = cfg.musicVol;
  $('bellVol').value = cfg.bellVol;
  $('fadeOut').value = cfg.fadeOut;
  $('fadeVal').textContent = `${cfg.fadeOut}s`;
  buildMusicChips();
  buildFxRows($('fxList'), false);
  updateSummary();

  $('sessions').onchange = () => { cfg.sessions = clamp(Math.round(+$('sessions').value) || 1, 1, 30); $('sessions').value = cfg.sessions; save(); updateSummary(); };
  $('minutes').onchange = () => { cfg.minutes = clamp(Math.round(+$('minutes').value) || 1, 1, 240); $('minutes').value = cfg.minutes; save(); updateSummary(); };
  document.querySelectorAll('[data-step]').forEach(b => b.onclick = () => {
    const key = b.dataset.step, d = +b.dataset.d, v = +$(key).value || 1;
    let nv = v + d;
    if (key === 'minutes') nv = d > 0 ? (v < 10 ? v + 1 : Math.floor(v / 5) * 5 + 5) : (v <= 10 ? v - 1 : Math.ceil(v / 5) * 5 - 5);
    $(key).value = nv;
    $(key).onchange();
  });
  $('musicVol').oninput = () => { cfg.musicVol = +$('musicVol').value; $('liveMusic').value = cfg.musicVol; save(); engine.sync(); };
  $('liveMusic').oninput = () => { cfg.musicVol = +$('liveMusic').value; $('musicVol').value = cfg.musicVol; save(); engine.sync(); };
  $('bellVol').oninput = () => { cfg.bellVol = +$('bellVol').value; save(); engine.syncBell(); };
  $('fadeOut').oninput = () => { cfg.fadeOut = +$('fadeOut').value; $('fadeVal').textContent = `${cfg.fadeOut}s`; save(); };
  $('play').onclick = () => engine.start();
  $('pause').onclick = () => engine.togglePause();
  $('stop').onclick = () => engine.stop();
}

/* ---------------------------------------------------------------- Motor de áudio */
const engine = {
  ctx: null, running: false, paused: false, buffers: {}, layers: {},
  t0: 0, D: 0, N: 0, T: 0, master: null, bellGain: null, timer: null, lastBell: null,

  async buf(id) {
    if (this.buffers[id]) return this.buffers[id];
    const r = await fetch(`audio/${id}.wav`);
    if (!r.ok) throw new Error(`não achei audio/${id}.wav`);
    const b = await this.ctx.decodeAudioData(await r.arrayBuffer());
    return (this.buffers[id] = b);
  },

  wantedLayers() {
    const w = {};
    if (cfg.music) w[cfg.music] = gainOf(cfg.musicVol);
    for (const f of FX) if (cfg.fx[f.id].on) w[f.id] = gainOf(cfg.fx[f.id].vol);
    return w;
  },

  async sync() {
    if (!this.running) return;
    const want = this.wantedLayers(), now = this.ctx.currentTime;
    for (const id of Object.keys(this.layers)) {
      if (!(id in want)) {            // desligado ao vivo: some suavemente
        const l = this.layers[id];
        if (!l.src) { delete this.layers[id]; continue; }
        l.gain.gain.setTargetAtTime(0, now, 0.4);
        l.src.stop(now + 2.5);
        delete this.layers[id];
      }
    }
    for (const [id, v] of Object.entries(want)) {
      if (this.layers[id]) { this.layers[id].gain.gain.setTargetAtTime(v, now, 0.15); continue; }
      this.layers[id] = { pending: true };
      const b = await this.buf(id);
      if (!this.running) return;
      const src = this.ctx.createBufferSource(), g = this.ctx.createGain();
      src.buffer = b; src.loop = true;
      g.gain.value = 0;
      src.connect(g).connect(this.master);
      src.start();
      g.gain.setTargetAtTime(this.wantedLayers()[id] ?? 0, this.ctx.currentTime, 0.8);
      this.layers[id] = { src, gain: g };
    }
  },
  syncBell() { if (this.bellGain) this.bellGain.gain.setTargetAtTime(gainOf(cfg.bellVol), this.ctx.currentTime, 0.05); },

  async start() {
    if (this.running) return;
    $('play').disabled = true; $('play').textContent = 'Carregando sons…';
    try {
      this.ctx = new (window.AudioContext || window.webkitAudioContext)({ latencyHint: 'playback' });
      await this.ctx.resume();
      this.buffers = {};
      const bell = await this.buf('bell');
      const ids = Object.keys(this.wantedLayers());
      await Promise.all(ids.map(id => this.buf(id)));

      const ctx = this.ctx;
      this.N = cfg.sessions; this.D = cfg.minutes * 60; this.T = this.N * this.D;
      const T = this.T, t0 = (this.t0 = ctx.currentTime + 0.5);

      this.bellGain = ctx.createGain(); this.bellGain.connect(ctx.destination); this.syncBell();
      this.master = ctx.createGain(); this.master.connect(ctx.destination);

      // sinos: início, entre sessões (sem intervalo) e fim
      for (let k = 0; k <= this.N; k++) {
        const s = ctx.createBufferSource(); s.buffer = bell; s.connect(this.bellGain);
        s.start(t0 + k * this.D);
        if (k === this.N) { this.lastBell = s; s.onended = () => this.finish(); }
      }

      // música/efeitos: entram após o 1º sino, saem em fade-out antes do último
      const inDelay = Math.min(6, T * 0.08), fadeIn = Math.min(10, T * 0.12);
      const fadeOut = Math.min(cfg.fadeOut, T * 0.3), gap = Math.min(3, T * 0.05);
      const curve = (up) => Float32Array.from({ length: 128 }, (_, i) => { const x = i / 127; const v = 0.5 - 0.5 * Math.cos(Math.PI * x); return up ? v : 1 - v; });
      this.master.gain.setValueAtTime(0, 0);
      this.master.gain.setValueCurveAtTime(curve(true), t0 + inDelay, fadeIn);
      this.master.gain.setValueCurveAtTime(curve(false), t0 + T - gap - fadeOut, fadeOut);

      this.running = true; this.paused = false;
      await this.sync();
      this.showRunning();
      this.timer = setInterval(() => this.tick(), 250);
      this.tick();
    } catch (e) {
      console.error(e);
      alert('Não foi possível iniciar o áudio.\nSe abriu o arquivo direto (file://), sirva a pasta com um servidor local (veja o README).\n\n' + e.message);
      this.teardown();
    }
  },

  tick() {
    if (!this.running) return;
    const el = clamp(this.ctx.currentTime - this.t0, 0, this.T);
    const idx = Math.min(this.N - 1, Math.floor(el / this.D));
    const inSession = el - idx * this.D, left = this.D - inSession;
    const ended = this.ctx.currentTime - this.t0 >= this.T;
    $('phase').textContent = this.ctx.currentTime < this.t0 ? 'Preparando…' : ended ? 'Sino final' : `Sessão ${idx + 1} de ${this.N}`;
    $('clock').textContent = fmt(ended ? 0 : left);
    $('total').textContent = this.N > 1 && !ended ? `Total restante ${fmt(this.T - el)}` : '';
    $('ringFg').style.strokeDashoffset = 565.49 * (1 - (ended ? 1 : inSession / this.D));
    document.querySelectorAll('#dots i').forEach((d, i) => { d.className = ended || i < idx ? 'done' : i === idx ? 'cur' : ''; });
  },

  togglePause() {
    if (!this.running) return;
    this.paused = !this.paused;
    this.paused ? this.ctx.suspend() : this.ctx.resume();
    $('pause').textContent = this.paused ? '▶ Retomar' : '⏸ Pausar';
  },

  stop() {
    if (!this.running) return;
    if (this.paused) this.ctx.resume();
    const now = this.ctx.currentTime;
    this.master.gain.cancelScheduledValues(now);
    this.master.gain.setTargetAtTime(0, now, 0.3);
    this.bellGain.gain.cancelScheduledValues(now);
    this.bellGain.gain.setTargetAtTime(0, now, 0.3);
    this.running = false;
    setTimeout(() => this.teardown(), 1500);
  },

  finish() { if (this.running) { this.tick(); this.running = false; setTimeout(() => this.teardown(), 800); } },

  teardown() {
    clearInterval(this.timer);
    this.running = false;
    try { this.ctx && this.ctx.close(); } catch (e) { /* ignora */ }
    this.ctx = null; this.layers = {}; this.master = null; this.bellGain = null;
    $('running').hidden = true; $('setup').hidden = false;
    $('play').disabled = false; $('play').textContent = '▶ Começar meditação';
    $('pause').textContent = '⏸ Pausar';
  },

  showRunning() {
    $('setup').hidden = true; $('running').hidden = false;
    $('dots').innerHTML = '<i></i>'.repeat(this.N);
    buildFxRows($('liveFx'), true);
    $('liveMusic').disabled = !cfg.music;
  },
};

initSetup();
