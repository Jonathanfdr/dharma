package com.jonathanfdr.dharma

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

/**
 * Motor de áudio: uma thread que mistura tudo em PCM e escreve num AudioTrack.
 *
 * O relógio da meditação é a contagem de amostras escritas: sinos, entrada e saída
 * suave da música são calculados por posição (frame), então ficam exatos, e os loops
 * são índices circulares sobre arquivos periódicos, o que torna a emenda inaudível.
 * Roda dentro de um Foreground Service com WakeLock, por isso continua com a tela bloqueada.
 */
class AudioEngine(
    private val context: Context,
    private val onEnd: (completed: Boolean) -> Unit,
) : Thread("dharma-audio") {

    companion object {
        const val SR = 44100
        private const val CHUNK = 1024
        private const val TAG = "AudioEngine"
        private fun gainOf(vol: Int) = (vol / 100f).let { it * it }
    }

    private class Layer(val data: ShortArray) { var pos = 0; var gain = 0f }

    private val cache = ConcurrentHashMap<String, ShortArray>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val loader = Executors.newSingleThreadExecutor()

    private fun readWav(name: String): ShortArray {
        val bytes = context.assets.open("audio/$name.wav").use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12
        while (p + 8 <= bytes.size) {                       // procura o chunk "data"
            val id = String(bytes, p, 4, Charsets.US_ASCII)
            val size = bb.getInt(p + 4)
            if (id == "data") {
                val n = min(size, bytes.size - p - 8) / 2
                return ShortArray(n).also { bb.position(p + 8); bb.asShortBuffer().get(it) }
            }
            p += 8 + size + (size and 1)
        }
        error("WAV sem chunk data: $name")
    }

    private fun ensureLoaded(name: String) {
        if (cache.containsKey(name) || !loading.add(name)) return
        loader.execute {
            try { cache[name] = readWav(name) } catch (e: Exception) { Log.e(TAG, "falha ao carregar $name", e) }
            finally { loading.remove(name) }
        }
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var track: AudioTrack? = null
        var completed = false
        try {
            val bell = readWav("bell")
            val st = SessionState
            val dFrames = st.minutes * 60L * SR
            val total = st.sessions * dFrames
            val endFrame = total + bell.size
            val totalSec = total / SR.toDouble()

            // linha do tempo da música/efeitos (segundos)
            val inDelay = min(6.0, totalSec * 0.08)
            val fadeIn = min(10.0, totalSec * 0.12)
            val fadeOut = min(st.fadeOutSec.toDouble(), totalSec * 0.3)
            val gap = min(3.0, totalSec * 0.05)
            val outStart = totalSec - gap - fadeOut

            val bufBytes = SR * 4 / 2                         // ~0,5 s de buffer estéreo 16 bits
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(SR).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(max(bufBytes, AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            val layers = HashMap<String, Layer>()
            val mono = FloatArray(CHUNK)
            val out = ShortArray(CHUNK * 2)
            var pos = 0L
            var bellG = gainOf(st.mix.bellVol)
            var stopGain = 1f
            track.play()

            while (pos < endFrame) {
                if (st.paused && !st.stopRequested) {
                    track.pause()
                    while (st.paused && !st.stopRequested) sleep(40)
                    track.play()
                }
                if (st.stopRequested) {
                    stopGain -= CHUNK / (SR * 1.5f)
                    if (stopGain <= 0f) break
                }
                val mix = st.mix

                // --- camadas ambiente (música + efeitos), com transição de ganho de ~1 s
                val wanted = HashMap<String, Float>()
                mix.music?.let { wanted[it] = gainOf(mix.musicVol) }
                for ((id, v) in mix.fxVol) wanted[id] = gainOf(v)
                for (name in wanted.keys) {
                    ensureLoaded(name)
                    if (name !in layers) cache[name]?.let { layers[name] = Layer(it) }
                }
                java.util.Arrays.fill(mono, 0f)
                val step = CHUNK / SR.toFloat()
                for ((name, l) in layers) {
                    val target = wanted[name] ?: 0f
                    val g0 = l.gain
                    val g1 = g0 + (target - g0).coerceIn(-step, step)
                    l.gain = g1
                    if (g0 == 0f && g1 == 0f) continue
                    val d = l.data; var p = l.pos; val inc = (g1 - g0) / CHUNK
                    var g = g0
                    for (i in 0 until CHUNK) {
                        mono[i] += d[p] * g
                        g += inc
                        if (++p == d.size) p = 0
                    }
                    l.pos = p
                }

                // --- envelope (entrada/saída suave) + sinos + saída
                val bellTarget = gainOf(mix.bellVol)
                val bInc = (bellTarget - bellG) / CHUNK
                val sg0 = stopGain + if (st.stopRequested) CHUNK / (SR * 1.5f) else 0f
                val sgInc = (stopGain - sg0) / CHUNK
                for (i in 0 until CHUNK) {
                    val f = pos + i
                    val t = f / SR.toDouble()
                    var env = 1.0
                    if (t < inDelay + fadeIn) env = 0.5 - 0.5 * cos(PI * ((t - inDelay) / fadeIn).coerceIn(0.0, 1.0))
                    if (t > outStart) env *= 0.5 + 0.5 * cos(PI * ((t - outStart) / fadeOut).coerceIn(0.0, 1.0))
                    var s = mono[i] * env.toFloat() / 32768f

                    val k = f / dFrames                       // sinos em 0, D, 2D, ..., N·D
                    if (k <= st.sessions) {
                        val off = (f - k * dFrames).toInt()
                        if (off < bell.size) s += bell[off] * bellG / 32768f
                    }
                    bellG += bInc
                    s *= (sg0 + sgInc * i).coerceIn(0f, 1f)
                    val a = abs(s)                            // limitador suave
                    if (a > 0.7f) s = (if (s < 0) -1 else 1) * (0.7f + 0.3f * tanh((a - 0.7f) / 0.3f))
                    val v = (s * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    out[2 * i] = v; out[2 * i + 1] = v
                }
                val w = track.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
                if (w < 0) { Log.e(TAG, "erro no write: $w"); break }
                pos += CHUNK
                st.playedFrames = min(max(0L, pos - SR / 2), endFrame)   // ~ o que já saiu do buffer
            }
            completed = pos >= endFrame
            if (completed) {                                  // deixa o buffer terminar de tocar
                track.stop()
                val deadline = System.currentTimeMillis() + 2000
                while (track.playbackHeadPosition < pos && System.currentTimeMillis() < deadline) sleep(30)
            }
        } catch (e: InterruptedException) {
            // encerrado
        } catch (e: Exception) {
            Log.e(TAG, "erro no motor", e)
        } finally {
            try { track?.stop() } catch (_: Exception) {}
            track?.release()
            loader.shutdownNow()
            onEnd(completed)
        }
    }
}
