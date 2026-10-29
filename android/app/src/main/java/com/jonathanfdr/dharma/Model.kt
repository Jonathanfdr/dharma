package com.jonathanfdr.dharma

import android.content.Context

object Sounds {
    val MUSIC = listOf(
        "music_templo" to "Templo",
        "music_himalaia" to "Himalaia",
        "music_lotus" to "Lótus",
        "music_om" to "Om (drone)",
    )
    val FX = listOf(
        "rain" to "🌧 Chuva",
        "wind" to "🍃 Vento",
        "chimes" to "🎐 Sinos de vento",
        "stream" to "💧 Riacho",
        "ocean" to "🌊 Ondas do mar",
        "crickets" to "🌙 Grilos",
        "fire" to "🔥 Fogueira",
    )
}

/** Parte da configuração que pode mudar ao vivo, lida pela thread de áudio. */
data class Mix(
    val music: String?,
    val musicVol: Int,            // 0..100
    val fxVol: Map<String, Int>,  // só efeitos ligados
    val bellVol: Int,             // 0..100
)

/** Estado compartilhado entre Activity, Service e thread de áudio (mesmo processo). */
object SessionState {
    @Volatile var running = false
    @Volatile var paused = false
    @Volatile var stopRequested = false
    @Volatile var completed = false
    @Volatile var sessions = 1
    @Volatile var minutes = 1
    @Volatile var fadeOutSec = 20
    @Volatile var playedFrames = 0L
    @Volatile var mix = Mix(null, 60, emptyMap(), 80)

    val totalFrames get() = sessions.toLong() * minutes * 60 * AudioEngine.SR
    val playedSeconds get() = playedFrames / AudioEngine.SR.toDouble()
    val totalSeconds get() = sessions * minutes * 60
}

class Config(private val ctx: Context) {
    private val p = ctx.getSharedPreferences("dharma", Context.MODE_PRIVATE)
    var sessions = p.getInt("sessions", 3)
    var minutes = p.getInt("minutes", 15)
    var music: String? = if (p.contains("music")) p.getString("music", null)?.ifEmpty { null } else "music_templo"
    var musicVol = p.getInt("musicVol", 60)
    var bellVol = p.getInt("bellVol", 80)
    var fadeOut = p.getInt("fadeOut", 20)
    val fxOn = Sounds.FX.associate { (id, _) -> id to p.getBoolean("fx_${id}_on", false) }.toMutableMap()
    val fxVol = Sounds.FX.associate { (id, _) -> id to p.getInt("fx_${id}_vol", 50) }.toMutableMap()

    fun mix() = Mix(music, musicVol, fxOn.filterValues { it }.keys.associateWith { fxVol[it] ?: 50 }, bellVol)

    fun save() {
        p.edit().apply {
            putInt("sessions", sessions); putInt("minutes", minutes)
            putString("music", music ?: ""); putInt("musicVol", musicVol)
            putInt("bellVol", bellVol); putInt("fadeOut", fadeOut)
            for ((id, _) in Sounds.FX) { putBoolean("fx_${id}_on", fxOn[id] == true); putInt("fx_${id}_vol", fxVol[id] ?: 50) }
        }.apply()
    }
}
