package com.jonathanfdr.dharma

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.ceil
import kotlin.math.floor

private const val BG1 = 0xFF2A1B3D.toInt()
private const val BG2 = 0xFF14112B.toInt()
private const val GOLD = 0xFFE3B45B.toInt()
private const val GOLD2 = 0xFFB9812F.toInt()
private const val TEXT = 0xFFF1E9DA.toInt()
private const val MUTED = 0xFFA99FB8.toInt()
private const val CARD = 0x14FFFFFF
private const val LINE = 0x1FFFFFFF

class MainActivity : Activity() {

    private lateinit var cfg: Config
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var setupPanel: LinearLayout
    private lateinit var runPanel: LinearLayout
    private lateinit var sessionsEdit: EditText
    private lateinit var minutesEdit: EditText
    private lateinit var summary: TextView
    private lateinit var flow: TextView
    private lateinit var ring: RingView
    private lateinit var phase: TextView
    private lateinit var clock: TextView
    private lateinit var totalTv: TextView
    private lateinit var pauseBtn: Button
    private lateinit var fadeSeek: SeekBar
    private lateinit var fadeLabel: TextView
    private lateinit var musicBox: LinearLayout
    private var wasRunning = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        buildUi()
        refreshSummary()
        if (SessionState.running) pushMix()
    }

    override fun onResume() {
        super.onResume()
        ui.post(poll)
    }

    override fun onPause() {
        ui.removeCallbacks(poll)
        super.onPause()
    }

    // ------------------------------------------------------------------ atualização periódica
    private val poll = object : Runnable {
        override fun run() {
            val running = SessionState.running
            if (wasRunning && !running) {
                if (SessionState.completed) Toast.makeText(this@MainActivity, "Meditação concluída 🙏", Toast.LENGTH_LONG).show()
            }
            wasRunning = running
            setupPanel.visibility = if (running) View.GONE else View.VISIBLE
            runPanel.visibility = if (running) View.VISIBLE else View.GONE
            fadeSeek.isEnabled = !running
            if (running) renderRunning()
            ui.postDelayed(this, 250)
        }
    }

    private fun fmt(sec: Double): String {
        val s = ceil(sec).toInt().coerceAtLeast(0)
        val h = s / 3600; val m = s / 60 % 60; val x = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%02d:%02d".format(m, x)
    }

    private fun renderRunning() {
        val st = SessionState
        val d = st.minutes * 60.0
        val played = st.playedSeconds
        val ended = played >= st.totalSeconds - 0.01
        val idx = floor(played / d).toInt().coerceIn(0, st.sessions - 1)
        val inSession = played - idx * d
        phase.text = when {
            st.stopRequested -> "Encerrando…"
            ended -> "Sino final"
            st.paused -> "Pausado"
            else -> "Sessão ${idx + 1} de ${st.sessions}"
        }
        clock.text = fmt(if (ended) 0.0 else d - inSession)
        totalTv.text = if (st.sessions > 1 && !ended) "Total restante ${fmt(st.totalSeconds - played)}" else ""
        ring.progress = if (ended) 1f else (inSession / d).toFloat()
        pauseBtn.text = if (st.paused) "▶  Retomar" else "⏸  Pausar"
    }

    // ------------------------------------------------------------------ ações
    private fun pushMix() { SessionState.mix = cfg.mix() }

    private fun startSession() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)   // opcional: a sessão toca mesmo se negado
        }
        pushMix()
        MeditationService.start(this, cfg.sessions, cfg.minutes, cfg.fadeOut)
    }

    private fun refreshSummary() {
        val n = cfg.sessions; val d = cfg.minutes; val tot = n * d
        val totTxt = if (tot >= 60) "${tot / 60} h" + (if (tot % 60 > 0) " ${tot % 60} min" else "") else "$tot min"
        summary.text = "$n ${if (n == 1) "sessão" else "sessões"} de $d min = $totTxt"
        val seg = "🔔 → $d min → "
        flow.text = if (n <= 4) seg.repeat(n) + "🔔" else "$seg🔔 → $d min → … ($n sessões, sem intervalo) … → 🔔"
    }

    // ------------------------------------------------------------------ construção da interface
    private fun tv(text: String, sp: Float, color: Int = TEXT, bold: Boolean = false, center: Boolean = false) = TextView(this).apply {
        this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.SERIF
        if (center) gravity = Gravity.CENTER
    }

    private fun card(vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = dpf(18f); setStroke(dp(1), LINE) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
        children.forEach { addView(it) }
    }

    private fun heading(t: String) = tv(t.uppercase(), 12f, GOLD).apply {
        letterSpacing = 0.14f; setPadding(0, 0, 0, dp(12))
    }

    private fun button(text: String, bg: Int, fg: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text; setTextColor(fg); isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); typeface = Typeface.SERIF
        background = GradientDrawable().apply { setColor(bg); cornerRadius = dpf(16f); setStroke(dp(1), LINE) }
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun seek(value: Int, max: Int = 100, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max; progress = value
        progressTintList = ColorStateList.valueOf(GOLD); thumbTintList = ColorStateList.valueOf(GOLD)
        progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun labeledSeek(label: String, sb: SeekBar, labelWidth: Int = 120) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(tv(label, 14f, MUTED).apply { layoutParams = LinearLayout.LayoutParams(dp(labelWidth), -2) })
        addView(sb, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun stepper(initial: Int, min: Int, max: Int, onStep: (Int, Boolean) -> Int, onValue: (Int) -> Unit): Pair<LinearLayout, EditText> {
        val edit = EditText(this).apply {
            setText(initial.toString()); setTextColor(TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); typeface = Typeface.SERIF
            gravity = Gravity.CENTER; inputType = InputType.TYPE_CLASS_NUMBER; background = null; setSingleLine()
            filters = arrayOf(android.text.InputFilter.LengthFilter(3))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { s?.toString()?.toIntOrNull()?.let { onValue(it.coerceIn(min, max)) } }
            })
            setOnFocusChangeListener { v, focus ->
                if (!focus) { val n = ((v as EditText).text.toString().toIntOrNull() ?: min).coerceIn(min, max); v.setText(n.toString()); onValue(n) }
            }
        }
        fun stepBtn(label: String, up: Boolean) = Button(this).apply {
            text = label; setTextColor(GOLD); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); background = null; stateListAnimator = null
            setOnClickListener {
                val cur = edit.text.toString().toIntOrNull() ?: min
                val n = onStep(cur, up).coerceIn(min, max)
                edit.setText(n.toString()); onValue(n); edit.clearFocus()
            }
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(0x33000000); cornerRadius = dpf(14f); setStroke(dp(1), LINE) }
            addView(stepBtn("−", false), LinearLayout.LayoutParams(dp(48), dp(52)))
            addView(edit, LinearLayout.LayoutParams(0, -2, 1f))
            addView(stepBtn("+", true), LinearLayout.LayoutParams(dp(48), dp(52)))
        }
        return row to edit
    }

    private fun field(label: String, content: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(tv(label, 12f, MUTED).apply { setPadding(0, 0, 0, dp(6)) })
        addView(content)
    }

    private fun buildMusicChips() {
        musicBox.removeAllViews()
        for ((id, name) in listOf<Pair<String?, String>>(null to "Nenhuma") + Sounds.MUSIC) {
            val on = cfg.music == id
            musicBox.addView(Button(this).apply {
                text = name; isAllCaps = false; typeface = Typeface.SERIF; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(if (on) 0xFF20150A.toInt() else TEXT); stateListAnimator = null; minHeight = 0; minimumHeight = 0
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dpf(99f)
                    if (on) setColor(GOLD) else { setColor(0x33000000); setStroke(dp(1), LINE) }
                }
                setOnClickListener { cfg.music = id; cfg.save(); pushMix(); buildMusicChips() }
                layoutParams = LinearLayout.LayoutParams(-2, dp(40)).apply { rightMargin = dp(8) }
            })
        }
    }

    private fun buildUi() {
        val root = ScrollView(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG1, BG2))
            isFillViewport = true
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(28), dp(16), dp(32)) }
        root.addView(col, FrameLayout.LayoutParams(-1, -2))

        // cabeçalho
        col.addView(tv("☸", 44f, GOLD, center = true))
        col.addView(tv("DHARMA", 24f, TEXT, center = true).apply { letterSpacing = 0.25f })
        col.addView(tv("Sessões de meditação com sino de tigela", 13f, MUTED, center = true).apply { setPadding(0, dp(4), 0, dp(18)) })

        // --- painel de configuração (antes de começar)
        setupPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val (sessStep, sEdit) = stepper(cfg.sessions, 1, 30, { v, up -> if (up) v + 1 else v - 1 }) { cfg.sessions = it; cfg.save(); refreshSummary() }
        val (minStep, mEdit) = stepper(cfg.minutes, 1, 240, { v, up ->
            if (up) (if (v < 10) v + 1 else (v / 5) * 5 + 5) else (if (v <= 10) v - 1 else ((v + 4) / 5) * 5 - 5)
        }) { cfg.minutes = it; cfg.save(); refreshSummary() }
        sessionsEdit = sEdit; minutesEdit = mEdit
        summary = tv("", 15f, TEXT, center = true).apply { setPadding(0, dp(14), 0, dp(2)) }
        flow = tv("", 12f, MUTED, center = true)
        setupPanel.addView(card(
            LinearLayout(this).apply {
                addView(field("Sessões", sessStep), LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(7) })
                addView(field("Minutos por sessão", minStep), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(7) })
            }, summary, flow))
        setupPanel.addView(button("▶   Começar meditação", GOLD, 0xFF20150A.toInt()) { currentFocus?.clearFocus(); startSession() },
            LinearLayout.LayoutParams(-1, dp(60)).apply { bottomMargin = dp(14) })
        col.addView(setupPanel)

        // --- painel durante a sessão
        runPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        ring = RingView(this)
        phase = tv("", 13f, GOLD, center = true).apply { letterSpacing = 0.12f }
        clock = tv("00:00", 52f, TEXT, center = true).apply { typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL) }
        totalTv = tv("", 13f, MUTED, center = true)
        val ringBox = FrameLayout(this).apply {
            addView(ring, FrameLayout.LayoutParams(dp(250), dp(250), Gravity.CENTER))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(phase); addView(clock); addView(totalTv)
            }, FrameLayout.LayoutParams(dp(250), dp(250), Gravity.CENTER))
        }
        runPanel.addView(ringBox, LinearLayout.LayoutParams(-1, dp(260)).apply { bottomMargin = dp(14) })
        pauseBtn = button("⏸  Pausar", 0x14FFFFFF, TEXT) { MeditationService.togglePause(this) }
        runPanel.addView(LinearLayout(this).apply {
            addView(pauseBtn, LinearLayout.LayoutParams(0, dp(54), 1f).apply { rightMargin = dp(6) })
            addView(button("■  Encerrar", 0x14FFFFFF, 0xFFF0B4AD.toInt()) { MeditationService.stop(this@MainActivity) },
                LinearLayout.LayoutParams(0, dp(54), 1f).apply { leftMargin = dp(6) })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        col.addView(runPanel)

        // --- música
        musicBox = LinearLayout(this)
        buildMusicChips()
        col.addView(card(
            heading("Música de fundo"),
            HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(musicBox) },
            labeledSeek("Volume", seek(cfg.musicVol) { cfg.musicVol = it; cfg.save(); pushMix() }).apply { setPadding(0, dp(10), 0, 0) }))

        // --- efeitos
        val fx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((id, name) in Sounds.FX) {
            val sb = seek(cfg.fxVol[id] ?: 50) { cfg.fxVol[id] = it; cfg.save(); pushMix() }.apply { isEnabled = cfg.fxOn[id] == true }
            val sw = Switch(this).apply {
                isChecked = cfg.fxOn[id] == true
                val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                thumbTintList = ColorStateList(st, intArrayOf(GOLD, 0xFF8A8296.toInt()))
                trackTintList = ColorStateList(st, intArrayOf(GOLD2, 0x44FFFFFF))
                setOnCheckedChangeListener { _: CompoundButton, on: Boolean -> cfg.fxOn[id] = on; sb.isEnabled = on; cfg.save(); pushMix() }
            }
            fx.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(tv(name, 15f).apply { layoutParams = LinearLayout.LayoutParams(dp(128), -2) })
                addView(sw, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
                addView(sb, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        col.addView(card(heading("Efeitos ambientes"), fx))

        // --- ajustes
        fadeLabel = tv("", 14f, MUTED)
        fadeSeek = seek((cfg.fadeOut - 5) / 5, 11) { cfg.fadeOut = 5 + it * 5; cfg.save(); fadeLabel.text = "Fade-out final  ${cfg.fadeOut}s" }
        fadeLabel.text = "Fade-out final  ${cfg.fadeOut}s"
        col.addView(card(
            heading("Ajustes"),
            labeledSeek("Volume do sino", seek(cfg.bellVol) { cfg.bellVol = it; cfg.save(); pushMix() }, 130),
            fadeLabel.apply { setPadding(0, dp(10), 0, 0) }, fadeSeek,
            tv("A música e os efeitos entram suavemente depois do primeiro sino e somem em fade-out antes do último sino. Funciona com a tela bloqueada.", 12f, MUTED).apply { setPadding(0, dp(10), 0, 0) }))

        col.addView(tv("Feche os olhos. Respire.", 13f, MUTED, center = true))
        setContentView(root)
    }
}

/** Anel de progresso da sessão atual. */
class RingView(ctx: Context) : View(ctx) {
    var progress = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x1AFFFFFF; strokeCap = Paint.Cap.ROUND }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = GOLD; strokeCap = Paint.Cap.ROUND }
    private val r = RectF()
    override fun onDraw(c: Canvas) {
        val w = resources.displayMetrics.density * 6f
        bg.strokeWidth = w; fg.strokeWidth = w
        val pad = w
        r.set(pad, pad, width - pad, height - pad)
        c.drawArc(r, 0f, 360f, false, bg)
        c.drawArc(r, -90f, 360f * progress, false, fg)
    }
}
