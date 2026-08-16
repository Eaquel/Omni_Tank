package com.omni.tank

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class Engine : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var gameView: NativeGameView
    private var panel: View? = null
    private var lastMenu: (() -> Unit)? = null
    private var quality = 3
    private var highRefresh = true
    private var shadows = true
    private var particles = true
    private var haptics = true
    private var selectedMode = 0
    private var selectedHull = 0
    private var selectedAbility = 0

    companion object {
        init { System.loadLibrary("omni_tank") }
    }

    private external fun nativeStep(deltaSeconds: Float): FloatArray
    private external fun nativeReset()
    private external fun nativeGetEngineInfo(): String
    private external fun nativeSetMode(mode: Int)
    private external fun nativeSetGraphicsQuality(level: Int)
    private external fun nativeSetDeveloperFlag(flag: Int, enabled: Boolean)
    private external fun nativeSetInput(moveX: Float, moveY: Float, aimX: Float, aimY: Float)
    private external fun nativeTriggerAbility(index: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        root = FrameLayout(this)
        gameView = NativeGameView()
        root.addView(gameView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        showHome()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (gameView.running) {
                    gameView.running = false
                    showPause()
                } else {
                    lastMenu?.invoke() ?: showHome()
                }
            }
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun text(value: String, size: Float = 14f): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.rgb(226, 234, 241))
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(5), dp(10), dp(5))
    }

    private fun button(value: String, action: () -> Unit): Button = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 14f
        minHeight = dp(48)
        minWidth = dp(120)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(0, dp(4), 0, dp(4)) }
    }

    private fun panel(title: String, width: Int = 430): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        setBackgroundColor(Color.argb(244, 7, 13, 21))
        elevation = dp(8).toFloat()
        addView(text(title, 25f).apply { gravity = Gravity.CENTER; setTypeface(typeface, android.graphics.Typeface.BOLD) }, LinearLayout.LayoutParams(-1, dp(58)))
        layoutParams = FrameLayout.LayoutParams(min(dp(width), resources.displayMetrics.widthPixels - dp(24)), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
    }

    private fun mount(view: View, back: (() -> Unit)? = ::showHome) {
        panel?.let(root::removeView)
        panel = view
        lastMenu = back
        root.addView(view)
    }

    private fun showHome() {
        gameView.running = false
        val p = panel(getString(R.string.app_name), 460)
        p.addView(text(getString(R.string.objective_text), 14f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, dp(58)))
        p.addView(button(getString(R.string.play)) { showModes() })
        p.addView(button(getString(R.string.online)) { showOnline() })
        p.addView(button(getString(R.string.garage)) { showGarage() })
        p.addView(button(getString(R.string.skills)) { showAbilities() })
        p.addView(button(getString(R.string.customize)) { showCustomize() })
        p.addView(button(getString(R.string.shop)) { showShop() })
        p.addView(button(getString(R.string.settings)) { showSettings() })
        if (BuildConfig.DEBUG) p.addView(button(getString(R.string.developer)) { showDeveloper() })
        mount(p, ::showHome)
    }

    private fun showModes() {
        val p = panel(getString(R.string.mode_title))
        val options = resources.getStringArray(R.array.game_mode_options)
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        spinner.setSelection(selectedMode)
        spinner.setOnItemSelectedListener(SimpleSelectionListener { selectedMode = it; nativeSetMode(it) })
        p.addView(text(getString(R.string.modes)))
        p.addView(spinner, LinearLayout.LayoutParams(-1, dp(54)))
        p.addView(text(getString(R.string.mode_description), 13f))
        p.addView(button(getString(R.string.start)) { startGame() })
        p.addView(button(getString(R.string.back)) { showHome() })
        mount(p, ::showHome)
    }

    private fun showOnline() {
        val p = panel(getString(R.string.online_title))
        p.addView(text(getString(R.string.online_status), 17f))
        p.addView(text(getString(R.string.online_ready_detail), 13f))
        p.addView(text(getString(R.string.online_client), 13f))
        p.addView(text(getString(R.string.online_backend_required), 12f))
        p.addView(button(getString(R.string.online_training)) { selectedMode = 3; nativeSetMode(3); startGame() })
        p.addView(button(getString(R.string.back)) { showHome() })
        mount(p, ::showHome)
    }

    private fun showGarage() {
        val p = panel(getString(R.string.garage_title))
        p.addView(text(getString(R.string.tank_preview), 18f).apply { gravity = Gravity.CENTER })
        p.addView(text(getString(R.string.tank_name), 21f).apply { gravity = Gravity.CENTER })
        p.addView(statRow(getString(R.string.mobility), 86))
        p.addView(statRow(getString(R.string.armor), 79))
        p.addView(statRow(getString(R.string.energy), 92))
        p.addView(statRow(getString(R.string.level), 12))
        p.addView(text("${getString(R.string.rank)}: ${getString(R.string.rank_value)}   ${getString(R.string.xp)}: ${getString(R.string.xp_value)}", 13f))
        p.addView(button(getString(R.string.customize)) { showCustomize() })
        p.addView(button(getString(R.string.back)) { showHome() })
        mount(p, ::showHome)
    }

    private fun statRow(name: String, value: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(text(name, 13f), LinearLayout.LayoutParams(0, dp(40), 1f))
        addView(text(value.toString(), 13f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(48), dp(40)))
    }

    private fun showAbilities() {
        val p = panel(getString(R.string.abilities_title))
        val options = resources.getStringArray(R.array.ability_options)
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        spinner.setSelection(selectedAbility)
        spinner.setOnItemSelectedListener(SimpleSelectionListener { selectedAbility = it })
        p.addView(text(getString(R.string.ability_select), 15f))
        p.addView(spinner, LinearLayout.LayoutParams(-1, dp(54)))
        p.addView(text(getString(R.string.ability_aegis), 13f))
        p.addView(text(getString(R.string.ability_overdrive), 13f))
        p.addView(text(getString(R.string.ability_repair), 13f))
        p.addView(text(getString(R.string.ability_scan), 13f))
        p.addView(button(getString(R.string.preview_ability)) { nativeTriggerAbility(selectedAbility) })
        p.addView(button(getString(R.string.back)) { showHome() })
        mount(p, ::showHome)
    }

    private fun showCustomize() {
        val p = panel(getString(R.string.customize_title))
        p.addView(text(getString(R.string.hull_profile)))
        val profile = Spinner(this)
        profile.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resources.getStringArray(R.array.hull_profile_options))
        profile.setSelection(selectedHull)
        profile.setOnItemSelectedListener(SimpleSelectionListener { selectedHull = it })
        p.addView(profile, LinearLayout.LayoutParams(-1, dp(54)))
        p.addView(text(getString(R.string.mobility)))
        val mobility = SeekBar(this)
        mobility.max = 100
        mobility.progress = 72
        p.addView(mobility, LinearLayout.LayoutParams(-1, dp(50)))
        p.addView(text(getString(R.string.paint_profile)))
        p.addView(button(getString(R.string.apply)) { showGarage() })
        p.addView(button(getString(R.string.back)) { showHome() })
        mount(p, ::showHome)
    }

    private fun showShop() {
        val p = panel(getString(R.string.shop_title))
        p.addView(text("${getString(R.string.field_skin)}     ${getString(R.string.owned)}", 14f))
        p.addView(text("${getString(R.string.desert_frame)}      ${getString(R.string.credits_2400)} ${getString(R.string.credits)}", 14f))
        p.addView(text("${getString(R.string.arctic_frame)}      ${getString(R.string.credits_3200)} ${getString(R.string.credits)}", 14f))
        p.addView(text("${getString(R.string.pilot_badge)}        ${getString(R.string.credits_900)} ${getString(R.string.credits)}", 14f))
        p.addView(button(getString(R.string.back)) { showHome() })
        mount(p, ::showHome)
    }

    private fun showSettings() {
        val p = panel(getString(R.string.settings_title), 460)
        p.addView(text(getString(R.string.graphics_quality)))
        val qualitySpinner = Spinner(this)
        qualitySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resources.getStringArray(R.array.graphics_quality_options))
        qualitySpinner.setSelection(quality)
        qualitySpinner.setOnItemSelectedListener(SimpleSelectionListener { quality = it; nativeSetGraphicsQuality(it) })
        p.addView(qualitySpinner, LinearLayout.LayoutParams(-1, dp(54)))
        p.addView(Switch(this).apply { text = getString(R.string.high_refresh); isChecked = highRefresh; setOnCheckedChangeListener { _, v -> highRefresh = v } }, LinearLayout.LayoutParams(-1, dp(50)))
        p.addView(Switch(this).apply { text = getString(R.string.shadows); isChecked = shadows; setOnCheckedChangeListener { _, v -> shadows = v } }, LinearLayout.LayoutParams(-1, dp(50)))
        p.addView(Switch(this).apply { text = getString(R.string.particles); isChecked = particles; setOnCheckedChangeListener { _, v -> particles = v } }, LinearLayout.LayoutParams(-1, dp(50)))
        p.addView(Switch(this).apply { text = getString(R.string.haptics); isChecked = haptics; setOnCheckedChangeListener { _, v -> haptics = v } }, LinearLayout.LayoutParams(-1, dp(50)))
        p.addView(text(getString(R.string.language)))
        val languages = resources.getStringArray(R.array.language_options)
        val languageSpinner = Spinner(this)
        languageSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages)
        languageSpinner.setSelection(languageIndex())
        languageSpinner.setOnItemSelectedListener(SimpleSelectionListener { if (it != languageIndex()) applyLanguage(it) })
        p.addView(languageSpinner, LinearLayout.LayoutParams(-1, dp(54)))
        p.addView(button(getString(R.string.back)) { showHome() })
        val scroll = ScrollView(this)
        scroll.addView(p)
        mount(scroll, ::showHome)
    }

    private fun languageIndex(): Int = when (resources.configuration.locales[0].language) {
        "tr" -> 0
        "en" -> 1
        "de" -> 2
        "es" -> 3
        "fr" -> 4
        else -> 1
    }

    private fun applyLanguage(index: Int) {
        val tags = arrayOf("tr", "en", "de", "es", "fr")
        val locale = java.util.Locale.forLanguageTag(tags[index])
        val config = resources.configuration
        config.setLocale(locale)
        createConfigurationContext(config)
        recreate()
    }

    private fun showDeveloper() {
        val p = panel(getString(R.string.developer), 460)
        p.addView(text(getString(R.string.developer_note), 12f))
        p.addView(text("${getString(R.string.engine_info)}: ${nativeGetEngineInfo()}", 12f))
        p.addView(Switch(this).apply { text = getString(R.string.dev_invulnerability); setOnCheckedChangeListener { _, v -> nativeSetDeveloperFlag(0, v) } }, LinearLayout.LayoutParams(-1, dp(50)))
        p.addView(Switch(this).apply { text = getString(R.string.dev_infinite_energy); setOnCheckedChangeListener { _, v -> nativeSetDeveloperFlag(1, v) } }, LinearLayout.LayoutParams(-1, dp(50)))
        p.addView(Switch(this).apply { text = getString(R.string.dev_show_bounds); setOnCheckedChangeListener { _, v -> nativeSetDeveloperFlag(2, v) } }, LinearLayout.LayoutParams(-1, dp(50)))
        p.addView(button(getString(R.string.clear_state)) { nativeReset(); showDeveloper() })
        p.addView(button(getString(R.string.back)) { showHome() })
        mount(p, ::showHome)
    }

    private fun showPause() {
        val p = panel(getString(R.string.pause))
        p.addView(button(getString(R.string.resume)) { startGame() })
        p.addView(button(getString(R.string.reset)) { nativeReset(); startGame() })
        p.addView(button(getString(R.string.back)) { showHome() })
        mount(p, ::showHome)
    }

    private fun startGame() {
        panel?.let(root::removeView)
        panel = null
        gameView.running = true
        gameView.requestFocus()
        gameView.invalidate()
    }

    override fun onResume() {
        super.onResume()
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onPause() {
        gameView.running = false
        super.onPause()
    }

    private inner class NativeGameView : View(this@Engine) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var lastNs = SystemClock.elapsedRealtimeNanos()
        private var state = FloatArray(12 + 12 * 5 + 32 * 3)
        private var fps = 0f
        var running = false

        init {
            isFocusable = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val w = width.toFloat()
            val h = height.toFloat()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val moveX = ((event.x - w * 0.23f) / (w * 0.23f)).coerceIn(-1f, 1f)
                    val moveY = ((event.y - h * 0.78f) / (h * 0.22f)).coerceIn(-1f, 1f)
                    val aimX = ((event.x - w * 0.72f) / (w * 0.28f)).coerceIn(-1f, 1f)
                    val aimY = ((event.y - h * 0.76f) / (h * 0.24f)).coerceIn(-1f, 1f)
                    nativeSetInput(moveX, moveY, aimX, aimY)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    nativeSetInput(0f, 0f, 0f, 0f)
                    return true
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val now = SystemClock.elapsedRealtimeNanos()
            val dt = ((now - lastNs) / 1_000_000_000f).coerceIn(0f, 0.05f)
            lastNs = now
            fps = if (dt > 0f) 1f / dt else 0f
            if (running) state = nativeStep(dt)
            drawWorld(canvas)
            if (running) postInvalidateOnAnimation()
        }

        private fun drawWorld(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w * 0.5f
            val cy = h * 0.5f
            val zoom = state[9].coerceIn(0.92f, 1.08f)
            c.drawColor(Color.rgb(5, 10, 16))
            drawArena(c, cx, cy, w, h)
            val scale = min(w, h) / 980f * zoom
            c.save()
            c.translate(cx, cy)
            c.scale(scale, scale)
            drawEntities(c)
            drawParticles(c)
            drawTank(c)
            c.restore()
            drawHud(c, w, h)
            drawControls(c, w, h)
        }

        private fun drawArena(c: Canvas, cx: Float, cy: Float, w: Float, h: Float) {
            gridPaint.color = Color.argb(38, 170, 195, 210)
            gridPaint.strokeWidth = 1f
            val step = 58f
            var x = ((cx - state[0] * 12f) % step + step) % step
            while (x < w) { c.drawLine(x, 0f, x, h, gridPaint); x += step }
            var y = ((cy - state[1] * 12f) % step + step) % step
            while (y < h) { c.drawLine(0f, y, w, y, gridPaint); y += step }
            paint.color = Color.argb(18, 90, 170, 120)
            c.drawCircle(cx, cy, min(w, h) * 0.42f, paint)
        }

        private fun drawEntities(c: Canvas) {
            var k = 12
            repeat(12) {
                val x = state[k++]
                val y = state[k++]
                val rotation = state[k++]
                val scale = state[k++]
                k++
                c.save()
                c.translate(x, y)
                c.rotate(rotation * 57.2958f)
                c.scale(scale, scale)
                paint.color = Color.argb(120, 54, 130, 75)
                c.drawCircle(0f, 0f, 28f, paint)
                paint.color = Color.rgb(83, 165, 91)
                c.drawCircle(0f, 0f, 20f, paint)
                paint.color = Color.argb(120, 210, 235, 190)
                c.drawCircle(-5f, -6f, 7f, paint)
                c.restore()
            }
        }

        private fun drawParticles(c: Canvas) {
            if (!particles) return
            var k = 12 + 12 * 5
            repeat(32) {
                val x = state[k++]
                val y = state[k++]
                val a = state[k++]
                paint.color = Color.argb((a * 130f).roundToInt().coerceIn(0, 130), 126, 190, 120)
                c.drawCircle(x, y, 2.5f + it % 4, paint)
            }
        }

        private fun drawTank(c: Canvas) {
            c.save()
            c.rotate(state[2])
            paint.setShadowLayer(if (shadows) 20f else 0f, 0f, 10f, Color.argb(120, 0, 0, 0))
            paint.color = Color.rgb(45, 88, 59)
            c.drawRoundRect(-122f, -70f, 122f, 70f, 22f, 22f, paint)
            paint.clearShadowLayer()
            paint.color = Color.rgb(68, 130, 78)
            c.drawRoundRect(-91f, -50f, 91f, 50f, 18f, 18f, paint)
            paint.color = Color.rgb(52, 105, 65)
            c.drawRoundRect(-111f, -80f, -78f, 80f, 12f, 12f, paint)
            c.drawRoundRect(78f, -80f, 111f, 80f, 12f, 12f, paint)
            c.rotate(state[3])
            paint.color = Color.rgb(87, 150, 91)
            c.drawRoundRect(-16f, -108f, 16f, 43f, 9f, 9f, paint)
            paint.color = Color.rgb(43, 86, 51)
            c.drawCircle(0f, 0f, 46f, paint)
            paint.color = Color.rgb(70, 132, 75)
            c.drawCircle(0f, 0f, 34f, paint)
            c.restore()
            if (state[10] > 0.02f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 5f + state[10] * 3f
                paint.color = Color.argb(65 + (state[10] * 75f).roundToInt(), 110, 210, 150)
                c.drawCircle(0f, 0f, 132f, paint)
                paint.style = Paint.Style.FILL
            }
        }

        private fun drawHud(c: Canvas, w: Float, h: Float) {
            paint.color = Color.argb(218, 7, 13, 21)
            c.drawRoundRect(18f, 18f, min(w - 18f, 320f), 94f, 18f, 18f, paint)
            paint.color = Color.WHITE
            paint.textSize = 19f
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            c.drawText(getString(R.string.app_name), 34f, 48f, paint)
            paint.textSize = 11f
            paint.typeface = android.graphics.Typeface.DEFAULT
            c.drawText(getString(R.string.native_hud), 34f, 70f, paint)
            val barW = min(250f, w * 0.36f)
            paint.color = Color.argb(190, 22, 30, 40)
            c.drawRoundRect(20f, h - 96f, 20f + barW, h - 80f, 8f, 8f, paint)
            paint.color = Color.rgb(76, 160, 92)
            c.drawRoundRect(20f, h - 96f, 20f + barW * state[8] / 100f, h - 80f, 8f, 8f, paint)
            paint.color = Color.WHITE
            paint.textSize = 12f
            c.drawText("${getString(R.string.energy)} ${state[7].roundToInt()}   ${getString(R.string.level)} 12   ${getString(R.string.fps)} ${fps.roundToInt()}", 20f, h - 108f, paint)
            paint.color = Color.argb(210, 7, 13, 21)
            c.drawRoundRect(w - 190f, 18f, w - 18f, 70f, 16f, 16f, paint)
            paint.color = Color.WHITE
            c.drawText(getString(R.string.ready), w - 170f, 48f, paint)
        }

        private fun drawControls(c: Canvas, w: Float, h: Float) {
            paint.color = Color.argb(70, 210, 230, 240)
            c.drawCircle(w * 0.18f, h * 0.78f, min(w, h) * 0.10f, paint)
            paint.color = Color.argb(80, 100, 180, 120)
            c.drawCircle(w * 0.82f, h * 0.78f, min(w, h) * 0.10f, paint)
            paint.color = Color.WHITE
            paint.textSize = 11f
            c.drawText(getString(R.string.move), w * 0.18f - 18f, h * 0.78f + 4f, paint)
            c.drawText(getString(R.string.aim), w * 0.82f - 14f, h * 0.78f + 4f, paint)
            val cooldown = max(0f, 1f - state[11])
            paint.color = Color.argb(170, 8, 14, 22)
            c.drawRoundRect(w * 0.5f - 70f, h - 82f, w * 0.5f + 70f, h - 46f, 12f, 12f, paint)
            paint.color = Color.rgb(95, 180, 112)
            c.drawRoundRect(w * 0.5f - 70f, h - 82f, w * 0.5f - 70f + 140f * cooldown, h - 46f, 12f, 12f, paint)
            paint.color = Color.WHITE
            c.drawText(getString(R.string.ability_ready), w * 0.5f - 40f, h - 58f, paint)
        }
    }

    private class SimpleSelectionListener(private val callback: (Int) -> Unit) : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = callback(position)
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }
}
