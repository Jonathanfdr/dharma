#!/usr/bin/env python3
"""Gera todos os áudios do Dharma (sino, músicas e efeitos) por síntese.

Todos os loops são periódicos POR CONSTRUÇÃO (senoides com frequências múltiplas de
1/L, ruído filtrado via FFT circular, eventos com "wrap-around" e reverb por convolução
circular). Por isso a última amostra emenda perfeitamente na primeira: o loop é inaudível.

Uso:  python3 tools/generate_audio.py        (precisa de numpy)
Saída: web/audio/*.wav  e  android/app/src/main/assets/audio/*.wav
"""
import os
import shutil
import wave

import numpy as np

SR = 44100
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_WEB = os.path.join(ROOT, "web", "audio")
OUT_AND = os.path.join(ROOT, "android", "app", "src", "main", "assets", "audio")


# ----------------------------------------------------------------------------- utilitários
def taxis(L):
    return np.arange(int(L * SR)) / SR


def save(name, x, rms=None, peak=0.95):
    x = np.asarray(x, dtype=np.float64)
    x = x - np.mean(x)
    if rms is not None:
        x = x / np.sqrt(np.mean(x ** 2)) * rms
    m = np.max(np.abs(x))
    if m > peak or rms is None:
        x = x / m * peak
    pcm = np.int16(np.clip(x, -1, 1) * 32767)
    os.makedirs(OUT_WEB, exist_ok=True)
    path = os.path.join(OUT_WEB, name + ".wav")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    print(f"  {name:10s} {len(x) / SR:5.1f}s  {os.path.getsize(path) / 1e6:5.2f} MB")


def bandnoise(L, lo, hi, seed, rolloff=0.0):
    """Ruído periódico (FFT circular) filtrado em [lo, hi] Hz, rolloff = expoente de amplitude."""
    n = int(L * SR)
    spec = np.fft.rfft(np.random.default_rng(seed).standard_normal(n))
    f = np.fft.rfftfreq(n, 1 / SR)
    mask = np.ones_like(f)
    if lo > 0:
        mask *= 1 / (1 + (lo / np.maximum(f, 1e-3)) ** 4)
    if hi < SR / 2:
        mask *= 1 / (1 + (f / hi) ** 4)
    mask *= (np.maximum(f, 20) / 1000.0) ** (-rolloff)
    mask[0] = 0
    y = np.fft.irfft(spec * mask, n)
    return y / np.std(y)


def filt(x, lo, hi):
    n = len(x)
    f = np.fft.rfftfreq(n, 1 / SR)
    mask = np.ones_like(f)
    if lo > 0:
        mask *= 1 / (1 + (lo / np.maximum(f, 1e-3)) ** 4)
    if hi < SR / 2:
        mask *= 1 / (1 + (f / hi) ** 4)
    return np.fft.irfft(np.fft.rfft(x) * mask, n)


def slowenv(L, seed, cycles=(1, 2, 3, 5), power=1.0, floor=0.0):
    """Envelope lenta periódica em [floor, 1] (ciclos inteiros em L)."""
    r = np.random.default_rng(seed)
    t = taxis(L)
    s = sum(r.uniform(0.5, 1) / (i + 1) ** 0.4 * np.sin(2 * np.pi * c * t / L + r.uniform(0, 6.28))
            for i, c in enumerate(cycles))
    s = (s - s.min()) / (s.max() - s.min())
    return floor + (1 - floor) * s ** power


def circ_reverb(x, seconds, wet, seed, damp_hz=5000):
    """Reverb por convolução circular: preserva a periodicidade do loop."""
    n = len(x)
    t = np.arange(n) / SR
    ir = np.random.default_rng(seed).standard_normal(n) * np.exp(-t * 6.9 / seconds)
    ir = filt(ir, 60, damp_hz)
    ir[: int(0.012 * SR)] *= np.linspace(0, 1, int(0.012 * SR))
    ir /= np.sqrt(np.sum(ir ** 2))
    y = np.fft.irfft(np.fft.rfft(x) * np.fft.rfft(ir), n)
    return (1 - wet) * x + wet * y * (np.std(x) / np.std(y))


def add_wrapped(buf, start, sig):
    """Soma `sig` em buf a partir de `start`, dando a volta no fim (loop circular)."""
    n = len(buf)
    idx = (start + np.arange(len(sig))) % n
    np.add.at(buf, idx, sig)


def mtof(m):
    return 440.0 * 2 ** ((m - 69) / 12)


# ----------------------------------------------------------------------------- sino (tigela)
def make_bell():
    L = 16.0
    t = taxis(L)
    r = np.random.default_rng(7)
    f0 = 232.0  # tigela tibetana, ~Bb3
    # razões inarmônicas típicas de tigela cantante
    partials = [(1.0, 1.0, 9.0), (2.71, 0.62, 6.5), (5.15, 0.36, 4.2), (8.43, 0.2, 2.8), (12.2, 0.1, 1.6)]
    x = np.zeros_like(t)
    for ratio, amp, tau in partials:
        for det, a2 in ((0.0, 1.0), (0.55 + 0.25 * ratio, 0.8)):  # par com batimento lento
            f = f0 * ratio + det
            ph = r.uniform(0, 6.28)
            beat = 1 + 0.18 * np.sin(2 * np.pi * (0.35 + 0.1 * ratio) * t + ph)
            x += amp * a2 * beat * np.exp(-t / tau) * np.sin(2 * np.pi * f * t + ph)
    # ataque suave de baqueta
    click = filt(np.random.default_rng(3).standard_normal(len(t)), 600, 4500) * np.exp(-t / 0.012)
    x += 0.35 * click / np.std(click) * 0.15
    x *= np.minimum(1, t / 0.004)           # ataque 4 ms
    x *= np.clip((L - t) / 1.5, 0, 1)       # fim suave
    # um pouco de ambiência (não circular: a cauda já é zero no fim)
    tail = np.concatenate([x, np.zeros(int(2 * SR))])
    ir = np.random.default_rng(11).standard_normal(int(1.6 * SR)) * np.exp(-np.arange(int(1.6 * SR)) / SR * 6.9 / 1.6)
    ir = filt(ir, 100, 6000)
    ir /= np.sqrt(np.sum(ir ** 2))
    wet = np.convolve(tail, ir)[: len(tail)]
    y = 0.8 * tail + 0.35 * wet * (np.std(tail) / np.std(wet))
    y = y[: int(L * SR)]
    y *= np.clip((L - taxis(L)) / 1.0, 0, 1)
    save("bell", y, peak=0.9)


# ----------------------------------------------------------------------------- músicas
def make_music(name, L, chords, scale, plucks, seed, bright=1.0, drone=None, wet=0.55, pluck_level=0.5):
    r = np.random.default_rng(seed)
    n = int(L * SR)
    t = taxis(L)
    K = len(chords)
    seg = L / K
    x = np.zeros(n)
    for i, chord in enumerate(chords):
        d = ((t - (i + 0.5) * seg + L / 2) % L) - L / 2
        w = np.where(np.abs(d) < seg, 0.5 * (1 + np.cos(np.pi * d / seg)), 0.0)
        layer = np.zeros(n)
        for m in chord:
            base = mtof(m)
            for cents in (-7, 0, 6):
                f = round(base * 2 ** (cents / 1200) * L) / L          # múltiplo de 1/L => periódico
                lfo_c = int(r.integers(1, 6))
                lfo = 1 + 0.3 * np.sin(2 * np.pi * lfo_c * t / L + r.uniform(0, 6.28))
                ph = r.uniform(0, 6.28)
                tone = (np.sin(2 * np.pi * f * t + ph)
                        + 0.30 * bright * np.sin(2 * np.pi * 2 * f * t + 1.3 * ph)
                        + 0.10 * bright * np.sin(2 * np.pi * 3 * f * t + 2.1 * ph))
                layer += tone * lfo
        x += layer * w
    if drone is not None:
        for m, a in drone:
            f = round(mtof(m) * L) / L
            lfo = 1 + 0.25 * np.sin(2 * np.pi * int(r.integers(1, 4)) * t / L + r.uniform(0, 6.28))
            x += a * lfo * (np.sin(2 * np.pi * f * t) + 0.4 * np.sin(2 * np.pi * 2 * f * t))
    x /= np.std(x)
    # notas soltas (estilo harpa / tigela), com cauda que dá a volta no loop
    if plucks:
        p = np.zeros(n)
        step = L / plucks
        for j in range(plucks):
            when = (j + r.uniform(-0.25, 0.25)) * step
            m = int(r.choice(scale))
            f = mtof(m)
            tt = np.arange(int(5 * SR)) / SR
            env = np.exp(-tt / 1.8) * np.minimum(1, tt / 0.006)
            sig = env * (np.sin(2 * np.pi * f * tt) + 0.28 * np.sin(2 * np.pi * 2.0 * f * tt)
                         + 0.08 * np.sin(2 * np.pi * 3.01 * f * tt)) * r.uniform(0.5, 1.0)
            add_wrapped(p, int(when * SR), sig)
        x += pluck_level * p / np.std(p) * np.std(x) * 0.6
    x = circ_reverb(x, 4.5, wet, seed + 100, damp_hz=4500)
    x = np.tanh(x / np.std(x) * 0.9)
    save(name, x, rms=0.10)


# ----------------------------------------------------------------------------- efeitos
def make_rain():
    L = 20.0
    n = int(L * SR)
    r = np.random.default_rng(21)
    bed = bandnoise(L, 400, 9500, 1, rolloff=0.15) * 0.55
    hiss = bandnoise(L, 3000, 14000, 2) * 0.25
    rumble = bandnoise(L, 60, 300, 3, rolloff=0.3) * 0.18
    imp = np.zeros(n)
    pos = r.integers(0, n, 1400)
    imp[pos] = r.exponential(1.0, 1400)
    drops = filt(imp, 1500, 9000)
    drops = drops / np.std(drops) * 0.55
    x = (bed + hiss + rumble + drops) * (0.9 + 0.1 * slowenv(L, 4, (1, 2, 3)))
    x = np.tanh(x / np.std(x) * 0.8)   # comprime picos dos pingos
    save("rain", x, rms=0.12)


def make_wind():
    L = 30.0
    x = (bandnoise(L, 120, 450, 31, 0.2) * slowenv(L, 32, (1, 2, 3), 1.5, 0.15)
         + bandnoise(L, 350, 1400, 33) * slowenv(L, 34, (2, 3, 5), 1.8, 0.05) * 0.8
         + bandnoise(L, 900, 3200, 35) * slowenv(L, 36, (3, 4, 7), 2.4, 0.0) * 0.5
         + bandnoise(L, 30, 120, 37, 0.3) * 0.4)
    save("wind", x, rms=0.12)


def make_ocean():
    L = 40.0
    t = taxis(L)
    env = slowenv(L, 41, (4, 5, 7, 9), 1.6, 0.12)
    foam_env = np.roll(env, int(0.9 * SR)) ** 1.5
    x = (bandnoise(L, 60, 2200, 42, 0.6) * env
         + bandnoise(L, 1800, 9000, 43) * foam_env * 0.45
         + bandnoise(L, 30, 150, 44, 0.3) * 0.35)
    save("ocean", x, rms=0.12)


def make_stream():
    L = 20.0
    n = int(L * SR)
    r = np.random.default_rng(51)
    bed = bandnoise(L, 300, 6500, 52, 0.3) * 0.5
    mod = bandnoise(L, 2, 18, 53)
    sparkle = bandnoise(L, 1200, 5200, 54) * (1 + 0.5 * np.tanh(mod)) * 0.4
    bub = np.zeros(n)
    for _ in range(260):
        d = int(r.uniform(0.015, 0.045) * SR)
        tt = np.arange(d) / SR
        f = r.uniform(500, 1400) * (1 + 2.2 * tt / (d / SR))
        sig = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-tt / (d / SR / 3))
        add_wrapped(bub, int(r.integers(0, n)), sig * r.uniform(0.2, 1))
    x = bed + sparkle + bub / np.std(bub) * 0.35
    save("stream", x, rms=0.12)


def make_crickets():
    L = 20.0
    t = taxis(L)
    r = np.random.default_rng(61)
    x = np.zeros_like(t)
    for i in range(7):
        fc = r.uniform(4200, 5400)
        k = int(r.integers(22, 40))                  # k chirps em L => periódico
        period = L / k
        phase = r.uniform(0, period)
        u = ((t + phase) % period)
        env = np.zeros_like(t)
        for p in range(3):                           # 3 pulsos por chirp
            c = 0.02 + p * 0.05
            env += np.where(np.abs(u - c) < 0.02, np.cos(np.pi * (u - c) / 0.04) ** 2, 0)
        slow = 0.7 + 0.3 * np.sin(2 * np.pi * int(r.integers(1, 4)) * t / L + r.uniform(0, 6.28))
        fc = round(fc * L) / L
        x += r.uniform(0.4, 1.0) * slow * env * np.sin(2 * np.pi * fc * t)
    x = x / np.std(x) + bandnoise(L, 80, 600, 62, 0.4) * 0.35
    save("crickets", x, rms=0.09)


def make_fire():
    L = 30.0
    n = int(L * SR)
    r = np.random.default_rng(71)
    rumble = bandnoise(L, 40, 260, 72, 0.3) * slowenv(L, 73, (2, 3, 5), 1.0, 0.4)
    hiss = bandnoise(L, 2200, 9000, 74) * (0.5 + 0.5 * np.tanh(bandnoise(L, 3, 25, 75))) * 0.25
    imp = np.zeros(n)
    cnt = int(L * 14)
    pos = r.integers(0, n, cnt)
    imp[pos] = r.exponential(1.0, cnt) ** 1.5
    crack = filt(imp, 700, 6500)
    crack = crack / np.std(crack) * 0.9
    x = rumble * 0.8 + hiss + crack
    x = np.tanh(x / np.std(x) * 0.7)   # comprime picos dos estalos
    save("fire", x, rms=0.12)


def make_chimes():
    L = 40.0
    n = int(L * SR)
    r = np.random.default_rng(81)
    notes = [74, 76, 79, 81, 83, 86, 88]  # D pentatônica
    x = np.zeros(n)
    tcur = 1.0
    while tcur < L - 3:
        burst = int(r.integers(2, 7))
        tb = tcur
        for _ in range(burst):
            f = mtof(int(r.choice(notes)))
            tt = np.arange(int(7 * SR)) / SR
            sig = np.zeros_like(tt)
            for ratio, amp, tau in ((1.0, 1.0, 3.2), (2.76, 0.35, 1.3), (5.4, 0.15, 0.6)):
                sig += amp * np.exp(-tt / tau) * np.sin(2 * np.pi * f * ratio * tt + r.uniform(0, 6.28))
            sig *= np.minimum(1, tt / 0.002) * r.uniform(0.3, 1.0)
            add_wrapped(x, int(tb * SR), sig)
            tb += r.uniform(0.12, 0.7)
        tcur += r.uniform(4, 9)
    x = circ_reverb(x, 2.8, 0.35, 82, 7000)
    save("chimes", x, rms=0.07, peak=0.9)


# ----------------------------------------------------------------------------- principal
def main():
    print("Gerando áudios (alguns segundos)...")
    make_bell()
    # Templo (Ré, quintas abertas)
    make_music("music_templo", 36, [[38, 45, 50, 57, 62], [36, 43, 48, 55, 64], [38, 45, 52, 57, 62], [31, 43, 50, 55, 62]],
               [62, 64, 66, 69, 71, 74], 6, seed=101, bright=0.8, wet=0.6, pluck_level=0.35)
    # Himalaia (Lá menor pentatônica, com notas de tigela)
    make_music("music_himalaia", 36, [[33, 40, 45, 52, 60], [29, 41, 48, 53, 60], [36, 43, 48, 55, 64], [31, 38, 50, 55, 62]],
               [57, 60, 62, 64, 67, 69, 72], 5, seed=202, bright=0.6, wet=0.65, pluck_level=0.55)
    # Lótus (Fá lídio, arpejos suaves)
    make_music("music_lotus", 36, [[41, 48, 52, 57, 64], [43, 50, 55, 59, 66], [45, 52, 57, 60, 67], [38, 50, 57, 62, 69]],
               [65, 67, 69, 72, 74, 76, 79], 9, seed=303, bright=1.0, wet=0.55, pluck_level=0.6)
    # Om (drone profundo, sem melodia)
    make_music("music_om", 36, [[26, 38, 45, 50], [26, 38, 45, 53], [26, 38, 43, 50], [26, 38, 45, 52]],
               [], 0, seed=404, bright=1.2, drone=[(26, 1.2), (38, 0.9), (45, 0.5), (50, 0.35), (57, 0.2)],
               wet=0.45, pluck_level=0)
    make_rain()
    make_wind()
    make_ocean()
    make_stream()
    make_crickets()
    make_fire()
    make_chimes()
    os.makedirs(OUT_AND, exist_ok=True)
    for f in os.listdir(OUT_WEB):
        shutil.copy(os.path.join(OUT_WEB, f), os.path.join(OUT_AND, f))
    print("Pronto:", OUT_WEB, "e", OUT_AND)


if __name__ == "__main__":
    main()
