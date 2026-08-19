package com.omni.tank

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class Engine : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var arena: ArenaView
    private lateinit var preferences: SharedPreferences

    private var panel: View? = null
    private val previews = mutableListOf<TankPreviewView>()
    private val geometryCache = HashMap<Int, FloatArray>()
    private val statsCache = HashMap<Int, FloatArray>()
    private val infoCache = HashMap<Int, IntArray>()
    private val nameCache = HashMap<Int, String>()
    private var vibrator: Vibrator? = null

    private var quality = 3
    private var shaderMode = 0
    private var highRefresh = true
    private var particles = true
    private var haptics = true
    private var autoFire = true
    private var sensitivity = 1.0f
    private var selectedTank = 0
    private var garageTier = 1

    private var tankCount = 26
    private var upgradeCount = 6
    private var upgradeCap = 8
    private var cheatToggleCount = 18
    private var cheatActionCount = 8
    private var barrelStride = 9

    private val cheatState = BooleanArray(32)

    private val tankDescriptions = intArrayOf(
        R.string.tank_desc_0, R.string.tank_desc_1, R.string.tank_desc_2, R.string.tank_desc_3,
        R.string.tank_desc_4, R.string.tank_desc_5, R.string.tank_desc_6, R.string.tank_desc_7,
        R.string.tank_desc_8, R.string.tank_desc_9, R.string.tank_desc_10, R.string.tank_desc_11,
        R.string.tank_desc_12, R.string.tank_desc_13, R.string.tank_desc_14, R.string.tank_desc_15,
        R.string.tank_desc_16, R.string.tank_desc_17, R.string.tank_desc_18, R.string.tank_desc_19,
        R.string.tank_desc_20, R.string.tank_desc_21, R.string.tank_desc_22, R.string.tank_desc_23,
        R.string.tank_desc_24, R.string.tank_desc_25
    )

    private val statLabels = intArrayOf(
        R.string.stat_damage, R.string.stat_reload, R.string.stat_bullet_speed,
        R.string.stat_move_speed, R.string.stat_max_health, R.string.stat_regen
    )

    companion object {
        private const val PREFERENCES = "omni_tank"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_QUALITY = "quality"
        private const val KEY_SHADER = "shader"
        private const val KEY_HIGH_REFRESH = "high_refresh"
        private const val KEY_PARTICLES = "particles"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_AUTO_FIRE = "auto_fire"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_TANK = "tank"
        private const val KEY_ACCOUNT_XP = "account_xp"
        private const val KEY_BEST = "best_score"

        private val LANGUAGE_TAGS = arrayOf("tr", "en", "de", "es", "fr")
        private val GARAGE_UNLOCK = intArrayOf(1, 1, 3, 8, 16, 26)

        init {
            System.loadLibrary("omni_tank")
        }
    }

    private external fun nativeGetLayout(): IntArray
    private external fun nativeStep(deltaSeconds: Float, out: FloatArray): Boolean
    private external fun nativeStartMatch(mode: Int, tankId: Int)
    private external fun nativeReset()
    private external fun nativeRespawn()
    private external fun nativeSetInput(moveX: Float, moveY: Float, aimX: Float, aimY: Float, firing: Boolean)
    private external fun nativeSetGraphicsQuality(level: Int)
    private external fun nativeSetCheat(index: Int, enabled: Boolean)
    private external fun nativeCheatAction(index: Int)
    private external fun nativeUpgrade(stat: Int): Boolean
    private external fun nativeEvolve(tankId: Int): Boolean
    private external fun nativeGetTankName(id: Int): String
    private external fun nativeGetTankInfo(id: Int): IntArray
    private external fun nativeGetTankStats(id: Int): FloatArray
    private external fun nativeGetTankGeometry(id: Int): FloatArray
    private external fun nativeGetEngineInfo(): String

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(localized(newBase))
    }

    private fun localized(base: Context): Context {
        val tag = base.getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getString(KEY_LANGUAGE, null) ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return base.createConfigurationContext(configuration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readLayoutConstants()
        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        loadPreferences()

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        applyRefreshRate()

        root = FrameLayout(this)
        arena = ArenaView()
        root.addView(arena, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        nativeSetGraphicsQuality(quality)
        showLobby()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (arena.handleBack()) return
                showLobby()
            }
        })
    }

    private fun readLayoutConstants() {
        val layout = nativeGetLayout()
        tankCount = layout[10]
        upgradeCount = layout[11]
        upgradeCap = layout[12]
        cheatToggleCount = layout[13]
        cheatActionCount = layout[14]
        barrelStride = layout[16]
    }

    private fun loadPreferences() {
        quality = preferences.getInt(KEY_QUALITY, 3)
        shaderMode = preferences.getInt(KEY_SHADER, 0)
        highRefresh = preferences.getBoolean(KEY_HIGH_REFRESH, true)
        particles = preferences.getBoolean(KEY_PARTICLES, true)
        haptics = preferences.getBoolean(KEY_HAPTICS, true)
        autoFire = preferences.getBoolean(KEY_AUTO_FIRE, true)
        sensitivity = preferences.getFloat(KEY_SENSITIVITY, 1.0f)
        selectedTank = preferences.getInt(KEY_TANK, 0).coerceIn(0, tankCount - 1)
    }

    private fun applyRefreshRate() {
        val modes = window.decorView.display?.supportedModes ?: return
        val attributes = window.attributes
        attributes.preferredRefreshRate = if (highRefresh) modes.maxOf { it.refreshRate } else 60f
        window.attributes = attributes
    }

    override fun onResume() {
        super.onResume()
        WindowCompat.getInsetsController(window, window.decorView)
            .hide(WindowInsetsCompat.Type.systemBars())
        previews.forEach { it.resume() }
    }

    override fun onPause() {
        arena.suspendMatch()
        previews.forEach { it.pause() }
        super.onPause()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun theme(): Palette = Palette.of(shaderMode)

    private fun pulse(milliseconds: Long) {
        if (!haptics) return
        vibrator?.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun tankName(id: Int): String = nameCache.getOrPut(id) { nativeGetTankName(id) }

    private fun tankInfo(id: Int): IntArray = infoCache.getOrPut(id) { nativeGetTankInfo(id) }

    private fun tankStats(id: Int): FloatArray = statsCache.getOrPut(id) { nativeGetTankStats(id) }

    private fun tankGeometry(id: Int): FloatArray = geometryCache.getOrPut(id) { nativeGetTankGeometry(id) }

    private fun tankDescription(id: Int): String =
        getString(tankDescriptions[id.coerceIn(0, tankDescriptions.size - 1)])

    private fun accountXp(): Int = preferences.getInt(KEY_ACCOUNT_XP, 0)

    private fun accountLevel(): Int = 1 + sqrt(accountXp() / 240.0).toInt()

    private fun rankName(): String {
        val ranks = resources.getStringArray(R.array.rank_options)
        return ranks[(accountLevel() / 5).coerceIn(0, ranks.size - 1)]
    }

    private fun unlockLevelFor(id: Int): Int {
        val tier = tankInfo(id)[0].coerceIn(0, GARAGE_UNLOCK.size - 1)
        return GARAGE_UNLOCK[tier]
    }

    private fun tankUnlocked(id: Int): Boolean = accountLevel() >= unlockLevelFor(id)

    private fun commitRun(score: Int) {
        val editor = preferences.edit()
        editor.putInt(KEY_ACCOUNT_XP, accountXp() + score)
        if (score > preferences.getInt(KEY_BEST, 0)) editor.putInt(KEY_BEST, score)
        editor.apply()
    }

    private fun text(value: String, size: Float = 13f, color: Int = Color.rgb(224, 232, 240)): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            setPadding(dp(10), dp(3), dp(10), dp(3))
        }

    private fun button(value: String, action: () -> Unit): Button = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.rgb(232, 240, 246))
        setBackgroundColor(Color.argb(232, 20, 34, 48))
        setOnClickListener { pulse(12); action() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(0, dp(3), 0, dp(3)) }
    }

    private fun header(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(14))
        setBackgroundColor(Color.argb(247, 6, 12, 19))
        addView(
            text(title, 21f, theme().accent).apply {
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
            }
        )
    }

    private fun mount(body: LinearLayout) {
        panel?.let(root::removeView)
        previews.forEach { it.pause() }
        previews.clear()

        val scroll = ScrollView(this)
        scroll.addView(body, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        val width = min(dp(560), (resources.displayMetrics.widthPixels * 0.58f).roundToInt())
        val height = (resources.displayMetrics.heightPixels * 0.9f).roundToInt()
        scroll.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)

        panel = scroll
        root.addView(scroll)
        arena.suspendMatch()
    }

    private fun dismissMenu() {
        panel?.let(root::removeView)
        panel = null
        previews.forEach { it.pause() }
        previews.clear()
    }

    private fun showLobby() {
        val body = header(getString(R.string.app_name))
        body.addView(
            text("${rankName()}  ·  ${getString(R.string.account_level)} ${accountLevel()}", 14f, theme().accent)
                .apply { gravity = Gravity.CENTER }
        )
        body.addView(
            text(
                "${getString(R.string.best_score)}: ${preferences.getInt(KEY_BEST, 0)}    " +
                    "${getString(R.string.hull_profile)}: ${tankName(selectedTank)}",
                12f
            ).apply { gravity = Gravity.CENTER }
        )
        body.addView(button(getString(R.string.survival)) { launch(0) })
        body.addView(button(getString(R.string.team_battle)) { launch(1) })
        body.addView(button(getString(R.string.garage)) { showGarage() })
        body.addView(button(getString(R.string.settings)) { showSettings() })
        mount(body)
    }

    private fun launch(mode: Int) {
        val tank = if (tankUnlocked(selectedTank)) selectedTank else 0
        dismissMenu()
        nativeStartMatch(mode, tank)
        nativeSetGraphicsQuality(quality)
        for (index in 0 until cheatToggleCount) nativeSetCheat(index, cheatState[index])
        arena.beginMatch()
    }

    private fun showGarage() {
        val body = header(getString(R.string.garage))
        body.addView(
            text("${rankName()}  ·  ${getString(R.string.account_level)} ${accountLevel()}", 13f, theme().accent)
                .apply { gravity = Gravity.CENTER }
        )

        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        for (tier in 1..5) {
            val selected = tier == garageTier
            val tab = Button(this).apply {
                text = "T$tier"
                isAllCaps = false
                textSize = 13f
                setTextColor(if (selected) Color.rgb(10, 16, 22) else Color.rgb(214, 226, 236))
                setBackgroundColor(if (selected) theme().accent else Color.argb(232, 18, 30, 42))
                setOnClickListener {
                    garageTier = tier
                    showGarage()
                }
            }
            tabs.addView(tab, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        body.addView(tabs, LinearLayout.LayoutParams(-1, dp(42)))

        val requirement = GARAGE_UNLOCK[garageTier]
        if (accountLevel() < requirement) {
            body.addView(
                text(
                    "${getString(R.string.locked)} · ${getString(R.string.unlock_at)} $requirement",
                    12f, Color.rgb(232, 156, 96)
                )
            )
        }

        for (id in 0 until tankCount) {
            if (tankInfo(id)[0] != garageTier) continue
            val marker = when {
                id == selectedTank -> "* "
                !tankUnlocked(id) -> "- "
                else -> ""
            }
            body.addView(button("$marker${tankName(id)}") { showTankDetail(id) })
        }

        body.addView(button(getString(R.string.back)) { showLobby() })
        mount(body)
    }

    private fun showTankDetail(id: Int) {
        val info = tankInfo(id)
        val stats = tankStats(id)
        val body = header(tankName(id))

        body.addView(
            text(
                "${getString(R.string.tier)} ${info[0]}  ·  ${getString(R.string.barrels)} ${info[2]}  ·  " +
                    "${getString(R.string.start_level)} ${info[1]}",
                12f, theme().accent
            ).apply { gravity = Gravity.CENTER }
        )

        body.addView(TankPreviewView(id).also(previews::add), LinearLayout.LayoutParams(-1, dp(150)))

        body.addView(statBar(getString(R.string.stat_damage), stats[0] / 4.2f))
        body.addView(statBar(getString(R.string.stat_reload), stats[1] / 3.6f))
        body.addView(statBar(getString(R.string.stat_bullet_speed), stats[2] / 2.1f))
        body.addView(statBar(getString(R.string.stat_move_speed), stats[3] / 1.5f))
        body.addView(statBar(getString(R.string.stat_max_health), stats[4] / 1.6f))
        body.addView(statBar(getString(R.string.stat_range), stats[6] / 5.0f))

        body.addView(text(tankDescription(id), 12f))

        if (info[3] > 0) {
            val children = StringBuilder()
            for (i in 0 until info[3]) {
                if (i > 0) children.append("  ·  ")
                children.append(tankName(info[4 + i]))
            }
            body.addView(text("${getString(R.string.evolves_into)}: $children", 12f, theme().accent))
        }

        if (tankUnlocked(id)) {
            val caption = if (id == selectedTank) getString(R.string.selected) else getString(R.string.select)
            body.addView(
                button(caption) {
                    selectedTank = id
                    preferences.edit().putInt(KEY_TANK, id).apply()
                    showGarage()
                }
            )
        } else {
            body.addView(
                text(
                    "${getString(R.string.locked)} · ${getString(R.string.unlock_at)} ${unlockLevelFor(id)}",
                    13f, Color.rgb(232, 156, 96)
                )
            )
        }

        body.addView(button(getString(R.string.back)) { showGarage() })
        mount(body)
    }

    private fun statBar(name: String, ratio: Float): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(text(name, 12f), LinearLayout.LayoutParams(0, dp(26), 1f))
        addView(MeterView(ratio), LinearLayout.LayoutParams(dp(150), dp(10)))
    }

    private fun showSettings() {
        val body = header(getString(R.string.settings))

        body.addView(text(getString(R.string.graphics_quality), 12f))
        body.addView(
            spinner(resources.getStringArray(R.array.graphics_quality_options), quality) {
                quality = it
                preferences.edit().putInt(KEY_QUALITY, it).apply()
                nativeSetGraphicsQuality(it)
            },
            LinearLayout.LayoutParams(-1, dp(46))
        )

        body.addView(text(getString(R.string.shader_mode), 12f))
        body.addView(
            spinner(resources.getStringArray(R.array.shader_mode_options), shaderMode) {
                shaderMode = it
                preferences.edit().putInt(KEY_SHADER, it).apply()
                arena.refreshPalette()
            },
            LinearLayout.LayoutParams(-1, dp(46))
        )

        body.addView(toggle(getString(R.string.high_refresh), highRefresh) {
            highRefresh = it
            preferences.edit().putBoolean(KEY_HIGH_REFRESH, it).apply()
            applyRefreshRate()
        })
        body.addView(toggle(getString(R.string.particles), particles) {
            particles = it
            preferences.edit().putBoolean(KEY_PARTICLES, it).apply()
        })
        body.addView(toggle(getString(R.string.haptics), haptics) {
            haptics = it
            preferences.edit().putBoolean(KEY_HAPTICS, it).apply()
        })
        body.addView(toggle(getString(R.string.auto_fire), autoFire) {
            autoFire = it
            preferences.edit().putBoolean(KEY_AUTO_FIRE, it).apply()
        })

        body.addView(text(getString(R.string.sensitivity), 12f))
        val slider = SeekBar(this)
        slider.max = 100
        slider.progress = ((sensitivity - 0.5f) / 1.5f * 100f).roundToInt().coerceIn(0, 100)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                sensitivity = 0.5f + progress / 100f * 1.5f
                preferences.edit().putFloat(KEY_SENSITIVITY, sensitivity).apply()
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        body.addView(slider, LinearLayout.LayoutParams(-1, dp(42)))

        body.addView(text(getString(R.string.language), 12f))
        body.addView(
            spinner(resources.getStringArray(R.array.language_options), languageIndex()) { applyLanguage(it) },
            LinearLayout.LayoutParams(-1, dp(46))
        )

        body.addView(text(nativeGetEngineInfo(), 10f, Color.rgb(118, 136, 150)))
        body.addView(button(getString(R.string.back)) { showLobby() })
        mount(body)
    }

    private fun spinner(options: Array<String>, selection: Int, onPick: (Int) -> Unit): Spinner =
        Spinner(this).apply {
            adapter = ArrayAdapter(this@Engine, android.R.layout.simple_spinner_dropdown_item, options)
            val safe = selection.coerceIn(0, max(0, options.size - 1))
            setSelection(safe)
            onItemSelectedListener = SelectionListener(safe) { onPick(it) }
        }

    private fun toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch =
        Switch(this).apply {
            text = label
            textSize = 13f
            isChecked = checked
            setTextColor(Color.rgb(224, 232, 240))
            setPadding(dp(10), 0, dp(10), 0)
            setOnCheckedChangeListener { _, value -> onChange(value) }
            layoutParams = LinearLayout.LayoutParams(-1, dp(42))
        }

    private fun languageIndex(): Int {
        val tag = preferences.getString(KEY_LANGUAGE, null) ?: resources.configuration.locales[0].language
        val index = LANGUAGE_TAGS.indexOf(tag)
        return if (index >= 0) index else 1
    }

    private fun applyLanguage(index: Int) {
        val tag = LANGUAGE_TAGS[index.coerceIn(0, LANGUAGE_TAGS.size - 1)]
        if (tag == preferences.getString(KEY_LANGUAGE, null)) return
        preferences.edit().putString(KEY_LANGUAGE, tag).apply()
        recreate()
    }

    private class Palette(
        val background: Int,
        val backgroundEdge: Int,
        val grid: Int,
        val ally: Int,
        val allyLight: Int,
        val enemy: Int,
        val enemyLight: Int,
        val barrel: Int,
        val square: Int,
        val triangle: Int,
        val pentagon: Int,
        val allyBullet: Int,
        val enemyBullet: Int,
        val accent: Int,
        val bloom: Boolean
    ) {
        companion object {
            private val MODES = arrayOf(
                Palette(
                    Color.rgb(7, 12, 19), Color.rgb(2, 4, 8), Color.argb(32, 130, 210, 190),
                    Color.rgb(64, 158, 214), Color.rgb(120, 206, 250),
                    Color.rgb(196, 74, 82), Color.rgb(244, 122, 122), Color.rgb(150, 166, 180),
                    Color.rgb(228, 186, 84), Color.rgb(226, 120, 82), Color.rgb(126, 128, 226),
                    Color.rgb(150, 226, 255), Color.rgb(255, 140, 130), Color.rgb(122, 226, 174),
                    true
                ),
                Palette(
                    Color.rgb(26, 28, 32), Color.rgb(14, 15, 18), Color.argb(28, 210, 210, 210),
                    Color.rgb(70, 130, 186), Color.rgb(122, 182, 232),
                    Color.rgb(196, 88, 74), Color.rgb(238, 132, 112), Color.rgb(160, 168, 176),
                    Color.rgb(226, 200, 96), Color.rgb(226, 122, 96), Color.rgb(134, 126, 216),
                    Color.rgb(226, 236, 244), Color.rgb(244, 150, 132), Color.rgb(126, 178, 232),
                    false
                ),
                Palette(
                    Color.rgb(14, 6, 20), Color.rgb(4, 2, 8), Color.argb(28, 226, 122, 60),
                    Color.rgb(240, 152, 58), Color.rgb(252, 200, 110),
                    Color.rgb(72, 120, 224), Color.rgb(132, 180, 252), Color.rgb(176, 128, 96),
                    Color.rgb(250, 214, 96), Color.rgb(250, 148, 70), Color.rgb(214, 96, 222),
                    Color.rgb(255, 216, 130), Color.rgb(140, 190, 255), Color.rgb(250, 168, 72),
                    true
                ),
                Palette(
                    Color.rgb(8, 22, 48), Color.rgb(4, 12, 28), Color.argb(52, 128, 190, 255),
                    Color.rgb(96, 176, 244), Color.rgb(168, 216, 255),
                    Color.rgb(226, 236, 250), Color.rgb(255, 255, 255), Color.rgb(140, 180, 220),
                    Color.rgb(150, 206, 255), Color.rgb(126, 190, 250), Color.rgb(180, 214, 255),
                    Color.rgb(210, 240, 255), Color.rgb(255, 226, 168), Color.rgb(138, 202, 255),
                    false
                ),
                Palette(
                    Color.rgb(13, 13, 13), Color.rgb(2, 2, 2), Color.argb(24, 220, 220, 220),
                    Color.rgb(210, 210, 210), Color.rgb(248, 248, 248),
                    Color.rgb(112, 112, 112), Color.rgb(170, 170, 170), Color.rgb(140, 140, 140),
                    Color.rgb(196, 196, 196), Color.rgb(160, 160, 160), Color.rgb(220, 220, 220),
                    Color.rgb(252, 252, 252), Color.rgb(186, 186, 186), Color.rgb(236, 236, 236),
                    false
                )
            )

            fun of(index: Int): Palette = MODES[index.coerceIn(0, MODES.size - 1)]
        }
    }

    private class TankPainter {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rect = RectF()

        fun draw(
            canvas: Canvas,
            geometry: FloatArray,
            stride: Int,
            radius: Float,
            heading: Float,
            turret: Float,
            recoil: Float,
            body: Int,
            bodyLight: Int,
            barrel: Int,
            flash: Float
        ) {
            val count = geometry[0].roundToInt()
            val kick = -recoil * 9f

            canvas.save()
            canvas.rotate(turret)
            for (index in 0 until count) {
                val base = 1 + index * stride
                canvas.save()
                canvas.rotate(geometry[base])
                canvas.translate(0f, geometry[base + 3])
                val length = geometry[base + 1]
                val width = geometry[base + 2]
                fill.color = if (geometry[base + 8] > 0.5f) darken(barrel, 0.62f) else barrel
                rect.set(kick, -width * 0.5f, length + kick, width * 0.5f)
                canvas.drawRoundRect(rect, 3f, 3f, fill)
                stroke.color = darken(barrel, 0.5f)
                stroke.strokeWidth = 4f
                canvas.drawRoundRect(rect, 3f, 3f, stroke)
                canvas.restore()
            }
            canvas.restore()

            canvas.save()
            canvas.rotate(heading)
            fill.color = blend(body, Color.WHITE, flash * 0.8f)
            canvas.drawCircle(0f, 0f, radius, fill)
            stroke.color = darken(body, 0.58f)
            stroke.strokeWidth = 6f
            canvas.drawCircle(0f, 0f, radius, stroke)
            fill.color = Color.argb(64, Color.red(bodyLight), Color.green(bodyLight), Color.blue(bodyLight))
            canvas.drawCircle(-radius * 0.24f, -radius * 0.26f, radius * 0.44f, fill)
            canvas.restore()
        }
    }

    inner class ArenaView : View(this@Engine) {

        private val layout = nativeGetLayout()
        private val state = FloatArray(layout[0])
        private val headerFloats = layout[1]
        private val maxBullets = layout[2]
        private val bulletFloats = layout[3]
        private val maxBots = layout[4]
        private val botFloats = layout[5]
        private val maxShapes = layout[6]
        private val shapeFloats = layout[7]
        private val maxParticles = layout[8]
        private val particleFloats = layout[9]

        private val bulletBase = headerFloats
        private val botBase = bulletBase + maxBullets * bulletFloats
        private val shapeBase = botBase + maxBots * botFloats
        private val particleBase = shapeBase + maxShapes * shapeFloats

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
        private val rect = RectF()
        private val polygon = Path()
        private val painter = TankPainter()
        private val slot = FloatArray(4)
        private val cheatLabels by lazy { resources.getStringArray(R.array.cheat_options) }
        private val actionLabels by lazy { resources.getStringArray(R.array.cheat_action_options) }

        private var colors = theme()
        private var backdrop: Shader? = null
        private var vignette: Shader? = null

        private var lastNanos = SystemClock.elapsedRealtimeNanos()
        private var ambient = 0f
        private var fps = 60f
        private var cameraX = 0f
        private var cameraY = 0f

        private var overlay = OVERLAY_NONE
        private var running = false
        private var resultHandled = false

        private var movePointer = -1
        private var aimPointer = -1
        private var moveOriginX = 0f
        private var moveOriginY = 0f
        private var moveX = 0f
        private var moveY = 0f
        private var aimOriginX = 0f
        private var aimOriginY = 0f
        private var aimX = 0f
        private var aimY = 0f
        private var holdPointer = -1
        private var holdTime = 0f

        init {
            isFocusable = true
            nativeStep(0f, state)
        }

        fun beginMatch() {
            overlay = OVERLAY_NONE
            resultHandled = false
            lastNanos = SystemClock.elapsedRealtimeNanos()
            cameraX = state[0]
            cameraY = state[1]
            running = true
            refreshPalette()
            requestFocus()
        }

        fun suspendMatch() {
            running = false
            releaseSticks()
        }

        fun refreshPalette() {
            colors = theme()
            backdrop = null
            invalidate()
        }

        fun handleBack(): Boolean {
            if (overlay == OVERLAY_EVOLVE || overlay == OVERLAY_CHEATS) {
                overlay = OVERLAY_NONE
                return true
            }
            if (overlay == OVERLAY_NONE && running) {
                overlay = OVERLAY_PAUSE
                running = false
                releaseSticks()
                return true
            }
            return false
        }

        private fun releaseSticks() {
            movePointer = -1
            aimPointer = -1
            holdPointer = -1
            moveX = 0f
            moveY = 0f
            aimX = 0f
            aimY = 0f
            nativeSetInput(0f, 0f, 0f, 0f, false)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            backdrop = null
            vignette = null
        }

        private fun unit(): Float = min(width, height) * 0.01f

        private fun ensureShaders() {
            if (backdrop != null || width <= 0 || height <= 0) return
            backdrop = RadialGradient(
                width * 0.5f, height * 0.5f, max(width, height) * 0.8f,
                colors.background, colors.backgroundEdge, Shader.TileMode.CLAMP
            )
            vignette = RadialGradient(
                width * 0.5f, height * 0.5f, max(width, height) * 0.62f,
                Color.TRANSPARENT, Color.argb(150, 0, 0, 0), Shader.TileMode.CLAMP
            )
        }

        private fun statButton(index: Int, out: FloatArray) {
            val u = unit()
            out[0] = u * 5.2f
            out[1] = height * 0.29f + index * u * 10.6f
            out[2] = u * 4.4f
        }

        private fun evolveButton(out: FloatArray) {
            val u = unit()
            out[0] = width * 0.5f
            out[1] = height - u * 19f
            out[2] = u * 6.2f
        }

        private fun pauseButton(out: FloatArray) {
            val u = unit()
            out[0] = width - u * 6f
            out[1] = u * 6f
            out[2] = u * 4.2f
        }

        private fun cheatZone(x: Float, y: Float): Boolean {
            val u = unit()
            return x > u * 12f && x < u * 34f && y > height * 0.17f && y < height * 0.28f
        }

        private fun overlayCaptions(): Array<String> = when {
            overlay == OVERLAY_PAUSE -> arrayOf(
                getString(R.string.resume), getString(R.string.restart), getString(R.string.main_menu)
            )
            state[27].roundToInt() == 0 -> arrayOf(
                getString(R.string.respawn), getString(R.string.restart), getString(R.string.main_menu)
            )
            else -> arrayOf(getString(R.string.restart), getString(R.string.main_menu))
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val action = event.actionMasked

            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                val index = event.actionIndex
                val id = event.getPointerId(index)
                val x = event.getX(index)
                val y = event.getY(index)

                if (overlay != OVERLAY_NONE) {
                    handleOverlayTouch(x, y)
                    return true
                }
                if (!running) return true

                pauseButton(slot)
                if (hypot(x - slot[0], y - slot[1]) < slot[2] * 1.3f) {
                    overlay = OVERLAY_PAUSE
                    running = false
                    releaseSticks()
                    return true
                }

                evolveButton(slot)
                if (state[34] > 0.5f && hypot(x - slot[0], y - slot[1]) < slot[2] * 1.2f) {
                    overlay = OVERLAY_EVOLVE
                    pulse(14)
                    return true
                }

                var consumed = false
                for (stat in 0 until upgradeCount) {
                    statButton(stat, slot)
                    if (hypot(x - slot[0], y - slot[1]) > slot[2] * 1.3f) continue
                    if (nativeUpgrade(stat)) pulse(12)
                    consumed = true
                    break
                }
                if (consumed) return true

                if (cheatZone(x, y)) {
                    holdPointer = id
                    holdTime = 0f
                    return true
                }

                if (x < width * 0.5f && movePointer < 0) {
                    movePointer = id
                    moveOriginX = x
                    moveOriginY = y
                } else if (aimPointer < 0) {
                    aimPointer = id
                    aimOriginX = x
                    aimOriginY = y
                }
            }

            if (action == MotionEvent.ACTION_MOVE && overlay == OVERLAY_NONE) {
                val radius = min(width, height) * 0.17f
                for (index in 0 until event.pointerCount) {
                    val id = event.getPointerId(index)
                    val x = event.getX(index)
                    val y = event.getY(index)
                    if (id == movePointer) {
                        moveX = ((x - moveOriginX) / radius * sensitivity).coerceIn(-1f, 1f)
                        moveY = ((y - moveOriginY) / radius * sensitivity).coerceIn(-1f, 1f)
                    } else if (id == aimPointer) {
                        aimX = ((x - aimOriginX) / radius * sensitivity).coerceIn(-1f, 1f)
                        aimY = ((y - aimOriginY) / radius * sensitivity).coerceIn(-1f, 1f)
                    } else if (id == holdPointer && !cheatZone(x, y)) {
                        holdPointer = -1
                    }
                }
            }

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP ||
                action == MotionEvent.ACTION_CANCEL
            ) {
                val id = event.getPointerId(event.actionIndex)
                val cancel = action == MotionEvent.ACTION_CANCEL
                if (id == movePointer || cancel) {
                    movePointer = -1
                    moveX = 0f
                    moveY = 0f
                }
                if (id == aimPointer || cancel) {
                    aimPointer = -1
                    aimX = 0f
                    aimY = 0f
                }
                if (id == holdPointer || cancel) holdPointer = -1
            }

            if (running && overlay == OVERLAY_NONE) {
                val firing = aimPointer >= 0 && (autoFire || hypot(aimX, aimY) > 0.55f)
                nativeSetInput(moveX, moveY, aimX, aimY, firing)
            }
            return true
        }

        private fun handleOverlayTouch(x: Float, y: Float) {
            val u = unit()
            when (overlay) {
                OVERLAY_EVOLVE -> {
                    val count = state[35].roundToInt().coerceIn(0, 4)
                    val cardWidth = width * 0.19f
                    val gap = width * 0.02f
                    val total = count * cardWidth + max(0, count - 1) * gap
                    val top = height * 0.3f
                    val bottom = top + height * 0.4f
                    for (index in 0 until count) {
                        val left = width * 0.5f - total * 0.5f + index * (cardWidth + gap)
                        if (x < left || x > left + cardWidth || y < top || y > bottom) continue
                        if (nativeEvolve(state[36 + index].roundToInt())) {
                            pulse(30)
                            overlay = OVERLAY_NONE
                        }
                        return
                    }
                    overlay = OVERLAY_NONE
                }

                OVERLAY_CHEATS -> {
                    val columns = 4
                    val rows = 7
                    val left = width * 0.06f
                    val top = height * 0.13f
                    val cellWidth = width * 0.88f / columns
                    val cellHeight = height * 0.72f / rows

                    if (y > top + rows * cellHeight || x < left || x > left + width * 0.88f) {
                        overlay = OVERLAY_NONE
                        return
                    }

                    val column = ((x - left) / cellWidth).toInt()
                    val row = ((y - top) / cellHeight).toInt()
                    if (column < 0 || column >= columns || row < 0 || row >= rows) return

                    val cell = row * columns + column
                    if (cell < cheatToggleCount) {
                        cheatState[cell] = !cheatState[cell]
                        nativeSetCheat(cell, cheatState[cell])
                        pulse(14)
                    } else if (cell < cheatToggleCount + cheatActionCount) {
                        nativeCheatAction(cell - cheatToggleCount)
                        pulse(22)
                    }
                }

                OVERLAY_PAUSE, OVERLAY_RESULT -> {
                    val captions = overlayCaptions()
                    val buttonWidth = width * 0.2f
                    val gap = width * 0.02f
                    val total = captions.size * buttonWidth + (captions.size - 1) * gap
                    val top = height * 0.58f
                    if (y < top || y > top + u * 9f) return

                    for (index in captions.indices) {
                        val left = width * 0.5f - total * 0.5f + index * (buttonWidth + gap)
                        if (x < left || x > left + buttonWidth) continue
                        pulse(16)
                        activateOverlayButton(captions[index])
                        return
                    }
                }
            }
        }

        private fun activateOverlayButton(caption: String) {
            when (caption) {
                getString(R.string.resume) -> {
                    overlay = OVERLAY_NONE
                    lastNanos = SystemClock.elapsedRealtimeNanos()
                    running = true
                }
                getString(R.string.respawn) -> {
                    nativeRespawn()
                    overlay = OVERLAY_NONE
                    resultHandled = false
                    lastNanos = SystemClock.elapsedRealtimeNanos()
                    running = true
                }
                getString(R.string.restart) -> {
                    nativeReset()
                    beginMatch()
                }
                else -> post { showLobby() }
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            ensureShaders()

            val now = SystemClock.elapsedRealtimeNanos()
            val delta = ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            lastNanos = now
            ambient += delta

            if (holdPointer >= 0) {
                holdTime += delta
                if (holdTime > 0.85f) {
                    holdPointer = -1
                    overlay = OVERLAY_CHEATS
                    pulse(40)
                }
            }

            if (running || overlay == OVERLAY_EVOLVE || overlay == OVERLAY_CHEATS) {
                nativeStep(delta, state)
                if (delta > 0f) fps += (1f / delta - fps) * 0.08f
                checkResult()
            }

            fill.shader = backdrop
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
            fill.shader = null

            val scale = viewScale()
            val follow = min(1f, delta * 6.5f + 0.02f)
            cameraX += (state[0] - cameraX) * follow
            cameraY += (state[1] - cameraY) * follow

            canvas.save()
            canvas.translate(width * 0.5f, height * 0.5f)
            canvas.scale(scale, scale)
            canvas.translate(-cameraX, -cameraY)
            drawGrid(canvas, scale)
            drawBounds(canvas)
            drawShapes(canvas)
            drawBots(canvas)
            if (particles) drawParticles(canvas)
            drawBullets(canvas)
            if (state[14] > 0.5f) drawPlayer(canvas)
            canvas.restore()

            drawVignette(canvas)
            drawHud(canvas)
            if (running && overlay == OVERLAY_NONE) drawControls(canvas)

            when (overlay) {
                OVERLAY_EVOLVE -> drawEvolveOverlay(canvas)
                OVERLAY_CHEATS -> drawCheatOverlay(canvas)
                OVERLAY_PAUSE -> drawPanelOverlay(canvas, getString(R.string.pause), null)
                OVERLAY_RESULT -> drawPanelOverlay(canvas, resultTitle(), resultDetail())
            }

            postInvalidateOnAnimation()
        }

        private fun checkResult() {
            if (resultHandled) return
            val teamMode = state[27].roundToInt() == 1
            val finished = if (teamMode) state[31] > 0.5f else state[14] < 0.5f && state[15] <= 0f
            if (!finished) return

            resultHandled = true
            running = false
            overlay = OVERLAY_RESULT
            commitRun(state[10].roundToInt())
            releaseSticks()
        }

        private fun resultTitle(): String {
            if (state[27].roundToInt() == 0) return getString(R.string.game_over)
            return when (state[32].roundToInt()) {
                0 -> getString(R.string.victory)
                1 -> getString(R.string.defeat)
                else -> getString(R.string.draw)
            }
        }

        private fun resultDetail(): String =
            "${getString(R.string.score)} ${state[10].roundToInt()}    " +
                "${getString(R.string.kills)} ${state[25].roundToInt()}    " +
                "${getString(R.string.level)} ${state[9].roundToInt()}"

        private fun viewScale(): Float {
            val wide = if (cheatState[13]) 1.9f else 1f
            return height / (1500f * state[11].coerceIn(0.8f, 2.0f) * wide)
        }

        private fun visible(x: Float, y: Float, margin: Float): Boolean {
            val scale = viewScale()
            return abs(x - cameraX) < width / scale * 0.5f + margin &&
                abs(y - cameraY) < height / scale * 0.5f + margin
        }

        private fun drawGrid(canvas: Canvas, scale: Float) {
            val step = 140f
            val halfWide = width / scale * 0.5f + step
            val halfHigh = height / scale * 0.5f + step
            stroke.color = colors.grid
            stroke.strokeWidth = 2.6f / scale

            var x = (cameraX - halfWide) - (cameraX - halfWide) % step
            while (x < cameraX + halfWide) {
                canvas.drawLine(x, cameraY - halfHigh, x, cameraY + halfHigh, stroke)
                x += step
            }
            var y = (cameraY - halfHigh) - (cameraY - halfHigh) % step
            while (y < cameraY + halfHigh) {
                canvas.drawLine(cameraX - halfWide, y, cameraX + halfWide, y, stroke)
                y += step
            }
        }

        private fun drawBounds(canvas: Canvas) {
            val halfWidth = state[12]
            val halfHeight = state[13]
            fill.color = Color.argb(46, 0, 0, 0)
            canvas.drawRect(-halfWidth - 1400f, -halfHeight - 1400f, halfWidth + 1400f, -halfHeight, fill)
            canvas.drawRect(-halfWidth - 1400f, halfHeight, halfWidth + 1400f, halfHeight + 1400f, fill)
            canvas.drawRect(-halfWidth - 1400f, -halfHeight, -halfWidth, halfHeight, fill)
            canvas.drawRect(halfWidth, -halfHeight, halfWidth + 1400f, halfHeight, fill)
            stroke.color = colors.accent
            stroke.strokeWidth = 8f
            rect.set(-halfWidth, -halfHeight, halfWidth, halfHeight)
            canvas.drawRect(rect, stroke)
        }

        private fun drawShapes(canvas: Canvas) {
            val count = state[20].roundToInt().coerceIn(0, maxShapes)
            for (i in 0 until count) {
                val base = shapeBase + i * shapeFloats
                val x = state[base]
                val y = state[base + 1]
                if (!visible(x, y, 200f)) continue

                val rotation = state[base + 2] / 57.2958f
                val size = state[base + 3]
                val sides = max(3, state[base + 4].roundToInt())
                val health = state[base + 5]
                val flash = state[base + 6]
                val color = when (sides) {
                    3 -> colors.triangle
                    5 -> colors.pentagon
                    else -> colors.square
                }

                polygon.reset()
                for (corner in 0 until sides) {
                    val angle = rotation + corner * (2.0 * Math.PI / sides).toFloat()
                    val px = x + cos(angle) * size
                    val py = y + sin(angle) * size
                    if (corner == 0) polygon.moveTo(px, py) else polygon.lineTo(px, py)
                }
                polygon.close()

                fill.color = blend(color, Color.WHITE, flash * 0.7f)
                canvas.drawPath(polygon, fill)
                stroke.color = darken(color, 0.58f)
                stroke.strokeWidth = 5f
                canvas.drawPath(polygon, stroke)

                if (health < 0.995f) {
                    canvas.save()
                    canvas.translate(x, y)
                    drawBar(canvas, size + 20f, size * 1.4f, health)
                    canvas.restore()
                }
            }
        }

        private fun drawBots(canvas: Canvas) {
            val count = state[19].roundToInt().coerceIn(0, maxBots)
            for (i in 0 until count) {
                val base = botBase + i * botFloats
                val x = state[base]
                val y = state[base + 1]
                if (!visible(x, y, 280f)) continue

                val team = state[base + 8].roundToInt()
                val tankId = state[base + 5].roundToInt()
                val body = if (team == 0) colors.ally else colors.enemy
                val bodyLight = if (team == 0) colors.allyLight else colors.enemyLight

                canvas.save()
                canvas.translate(x, y)
                canvas.scale(state[base + 6], state[base + 6])
                painter.draw(canvas, tankGeometry(tankId), barrelStride, 42f,
                    state[base + 2], state[base + 3], state[base + 10],
                    body, bodyLight, colors.barrel, state[base + 7])
                drawBar(canvas, 56f, 56f, state[base + 4])
                label.color = Color.argb(190, 224, 234, 242)
                label.textSize = 21f
                canvas.drawText("${tankName(tankId)} ${state[base + 9].roundToInt()}", -52f, -60f, label)
                canvas.restore()
            }
        }

        private fun drawBar(canvas: Canvas, offset: Float, halfWidth: Float, ratio: Float) {
            fill.color = Color.argb(190, 10, 14, 20)
            rect.set(-halfWidth, offset, halfWidth, offset + 10f)
            canvas.drawRoundRect(rect, 5f, 5f, fill)
            fill.color = if (ratio > 0.4f) Color.rgb(96, 208, 128) else Color.rgb(224, 96, 88)
            rect.set(-halfWidth, offset, -halfWidth + halfWidth * 2f * ratio.coerceIn(0f, 1f), offset + 10f)
            canvas.drawRoundRect(rect, 5f, 5f, fill)
        }

        private fun drawBullets(canvas: Canvas) {
            val count = state[18].roundToInt().coerceIn(0, maxBullets)
            val useGlow = colors.bloom && quality >= 2
            if (useGlow) glow.maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)

            for (i in 0 until count) {
                val base = bulletBase + i * bulletFloats
                val x = state[base]
                val y = state[base + 1]
                if (!visible(x, y, 90f)) continue

                val radius = state[base + 2]
                val life = state[base + 4].coerceIn(0f, 1f)
                val color = if (state[base + 3].roundToInt() == 0) colors.allyBullet else colors.enemyBullet

                if (useGlow) {
                    glow.color = Color.argb((100 * life).roundToInt().coerceIn(0, 100),
                        Color.red(color), Color.green(color), Color.blue(color))
                    canvas.drawCircle(x, y, radius * 2f, glow)
                }
                fill.color = darken(color, 0.6f)
                canvas.drawCircle(x, y, radius * 1.35f, fill)
                fill.color = color
                canvas.drawCircle(x, y, radius, fill)
            }
            if (useGlow) glow.maskFilter = null
        }

        private fun drawParticles(canvas: Canvas) {
            val count = state[21].roundToInt().coerceIn(0, maxParticles)
            for (i in 0 until count) {
                val base = particleBase + i * particleFloats
                val x = state[base]
                val y = state[base + 1]
                if (!visible(x, y, 80f)) continue

                val life = state[base + 2].coerceIn(0f, 1f)
                val color = when (state[base + 4].roundToInt()) {
                    1 -> colors.accent
                    2 -> colors.enemy
                    3 -> colors.allyBullet
                    4 -> Color.rgb(126, 226, 160)
                    6 -> colors.square
                    7 -> colors.enemyBullet
                    else -> Color.rgb(238, 156, 96)
                }
                fill.color = Color.argb((life * 200f).roundToInt().coerceIn(0, 200),
                    Color.red(color), Color.green(color), Color.blue(color))
                canvas.drawCircle(x, y, state[base + 3] * (0.4f + life * 0.9f), fill)
            }
        }

        private fun drawPlayer(canvas: Canvas) {
            val stealth = state[40].coerceIn(0.12f, 1f)
            canvas.save()
            canvas.translate(state[0], state[1])

            if (stealth < 0.98f) {
                fill.color = Color.argb((stealth * 80f).roundToInt().coerceIn(0, 80), 200, 220, 240)
                canvas.drawCircle(0f, 0f, 54f, fill)
            }

            painter.draw(canvas, tankGeometry(state[17].roundToInt()), barrelStride, 44f,
                state[2], state[3], state[22], colors.ally, colors.allyLight, colors.barrel, 0f)

            if (state[47] > 0.01f) {
                stroke.color = Color.argb(180, Color.red(colors.accent), Color.green(colors.accent),
                    Color.blue(colors.accent))
                stroke.strokeWidth = 6f
                canvas.drawCircle(0f, 0f, 64f + sin(ambient * 10f) * 4f, stroke)
            }
            canvas.restore()
        }

        private fun drawVignette(canvas: Canvas) {
            fill.shader = vignette
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
            fill.shader = null

            val damage = state[24].coerceIn(0f, 1f)
            if (damage > 0.01f) {
                fill.color = Color.argb((damage * 110f).roundToInt().coerceIn(0, 110), 190, 40, 46)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
            }
        }

        private fun drawHud(canvas: Canvas) {
            val u = unit()
            fill.shader = null

            fill.color = Color.argb(214, 6, 12, 19)
            rect.set(u * 2f, u * 2f, u * 34f, u * 15f)
            canvas.drawRoundRect(rect, u, u, fill)

            label.color = colors.accent
            label.textSize = u * 3f
            canvas.drawText(
                "${getString(R.string.level)} ${state[9].roundToInt()}  ${tankName(state[17].roundToInt())}",
                u * 3.5f, u * 6.4f, label
            )
            label.color = Color.rgb(214, 226, 236)
            label.textSize = u * 2.2f
            canvas.drawText(
                "${getString(R.string.score)} ${state[10].roundToInt()}   " +
                    "${getString(R.string.kills)} ${state[25].roundToInt()}",
                u * 3.5f, u * 10f, label
            )

            val xpRatio = if (state[8] > 0f) (state[7] / state[8]).coerceIn(0f, 1f) else 0f
            fill.color = Color.argb(180, 18, 26, 34)
            rect.set(u * 3.5f, u * 11.6f, u * 32.5f, u * 13.4f)
            canvas.drawRoundRect(rect, u * 0.8f, u * 0.8f, fill)
            fill.color = colors.accent
            rect.set(u * 3.5f, u * 11.6f, u * 3.5f + u * 29f * xpRatio, u * 13.4f)
            canvas.drawRoundRect(rect, u * 0.8f, u * 0.8f, fill)

            if (state[27].roundToInt() == 1) drawTeamBanner(canvas, u) else drawWaveBanner(canvas, u)

            drawStatColumn(canvas, u)
            drawHealth(canvas, u)
            drawMinimap(canvas, u)
            drawPauseControl(canvas, u)

            if (state[34] > 0.5f && overlay == OVERLAY_NONE) {
                evolveButton(slot)
                fill.color = colors.accent
                canvas.drawCircle(slot[0], slot[1], slot[2] * (1f + sin(ambient * 6f) * 0.05f), fill)
                label.color = Color.rgb(10, 16, 22)
                label.textSize = u * 2.1f
                val caption = getString(R.string.evolve)
                canvas.drawText(caption, slot[0] - caption.length * u * 0.58f, slot[1] + u * 0.8f, label)
            }

            label.color = Color.argb(140, 220, 232, 242)
            label.textSize = u * 1.8f
            canvas.drawText("${getString(R.string.fps)} ${fps.roundToInt()}", u * 2f, height - u * 1.4f, label)
        }

        private fun drawTeamBanner(canvas: Canvas, u: Float) {
            val seconds = state[30].roundToInt()
            fill.color = Color.argb(214, 6, 12, 19)
            rect.set(width * 0.5f - u * 20f, u * 1.6f, width * 0.5f + u * 20f, u * 9f)
            canvas.drawRoundRect(rect, u, u, fill)

            label.textSize = u * 3.2f
            label.color = colors.allyLight
            canvas.drawText("${state[28].roundToInt()}", width * 0.5f - u * 18f, u * 6.6f, label)
            label.color = colors.enemyLight
            canvas.drawText("${state[29].roundToInt()}", width * 0.5f + u * 9f, u * 6.6f, label)
            label.color = Color.rgb(226, 236, 244)
            label.textSize = u * 2.6f
            canvas.drawText(
                "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                width * 0.5f - u * 3.2f, u * 6.4f, label
            )
        }

        private fun drawWaveBanner(canvas: Canvas, u: Float) {
            fill.color = Color.argb(214, 6, 12, 19)
            rect.set(width * 0.5f - u * 12f, u * 1.6f, width * 0.5f + u * 12f, u * 7.4f)
            canvas.drawRoundRect(rect, u, u, fill)
            label.color = colors.accent
            label.textSize = u * 2.6f
            val caption = "${getString(R.string.wave)} ${state[33].roundToInt()}"
            canvas.drawText(caption, width * 0.5f - caption.length * u * 0.7f, u * 5.6f, label)
        }

        private fun drawStatColumn(canvas: Canvas, u: Float) {
            val points = state[16].roundToInt()
            for (stat in 0 until upgradeCount) {
                statButton(stat, slot)
                val level = state[41 + stat].roundToInt()
                val ready = points > 0 && level < upgradeCap

                fill.color = Color.argb(if (ready) 224 else 148, 8, 16, 24)
                canvas.drawCircle(slot[0], slot[1], slot[2], fill)
                stroke.color = if (ready) colors.accent else Color.argb(105, 150, 170, 186)
                stroke.strokeWidth = slot[2] * 0.12f
                canvas.drawCircle(slot[0], slot[1], slot[2], stroke)

                label.color = if (ready) colors.accent else Color.argb(160, 200, 214, 226)
                label.textSize = u * 2f
                canvas.drawText("$level", slot[0] - u * 0.6f, slot[1] + u * 0.7f, label)

                label.textSize = u * 1.5f
                label.color = Color.argb(185, 214, 226, 236)
                canvas.drawText(getString(statLabels[stat]), slot[0] + slot[2] * 1.35f, slot[1] + u * 0.5f, label)
            }

            if (points > 0) {
                label.color = colors.accent
                label.textSize = u * 2.2f
                canvas.drawText("+$points", u * 3f, height * 0.29f - u * 6f, label)
            }
        }

        private fun drawHealth(canvas: Canvas, u: Float) {
            val barWidth = width * 0.3f
            val left = width * 0.5f - barWidth * 0.5f
            val top = height - u * 8f
            val ratio = if (state[6] > 0f) (state[5] / state[6]).coerceIn(0f, 1f) else 0f

            fill.color = Color.argb(196, 8, 14, 21)
            rect.set(left - u, top - u, left + barWidth + u, top + u * 4f)
            canvas.drawRoundRect(rect, u, u, fill)
            fill.color = Color.argb(190, 20, 28, 36)
            rect.set(left, top, left + barWidth, top + u * 3f)
            canvas.drawRoundRect(rect, u * 0.9f, u * 0.9f, fill)
            fill.color = if (ratio > 0.35f) Color.rgb(92, 208, 126) else Color.rgb(226, 92, 84)
            rect.set(left, top, left + barWidth * ratio, top + u * 3f)
            canvas.drawRoundRect(rect, u * 0.9f, u * 0.9f, fill)

            label.color = Color.WHITE
            label.textSize = u * 2f
            canvas.drawText("${state[5].roundToInt()} / ${state[6].roundToInt()}", left, top - u * 1.4f, label)
        }

        private fun drawMinimap(canvas: Canvas, u: Float) {
            val halfWidth = state[12]
            val halfHeight = state[13]
            if (halfWidth <= 0f || halfHeight <= 0f) return

            val size = u * 19f
            val left = width - size - u * 2f
            val top = u * 12f
            val mapHeight = size * (halfHeight / halfWidth)

            fill.color = Color.argb(180, 6, 12, 19)
            rect.set(left, top, left + size, top + mapHeight)
            canvas.drawRoundRect(rect, u * 0.6f, u * 0.6f, fill)
            stroke.color = Color.argb(110, Color.red(colors.accent), Color.green(colors.accent),
                Color.blue(colors.accent))
            stroke.strokeWidth = u * 0.22f
            canvas.drawRoundRect(rect, u * 0.6f, u * 0.6f, stroke)

            val count = state[19].roundToInt().coerceIn(0, maxBots)
            for (i in 0 until count) {
                val base = botBase + i * botFloats
                fill.color = if (state[base + 8].roundToInt() == 0) colors.allyLight else colors.enemyLight
                canvas.drawCircle(
                    left + (state[base] + halfWidth) / (halfWidth * 2f) * size,
                    top + (state[base + 1] + halfHeight) / (halfHeight * 2f) * mapHeight,
                    u * 0.3f, fill
                )
            }

            fill.color = colors.accent
            canvas.drawCircle(
                left + (state[0] + halfWidth) / (halfWidth * 2f) * size,
                top + (state[1] + halfHeight) / (halfHeight * 2f) * mapHeight,
                u * 0.5f, fill
            )
        }

        private fun drawPauseControl(canvas: Canvas, u: Float) {
            pauseButton(slot)
            fill.color = Color.argb(190, 8, 14, 21)
            canvas.drawCircle(slot[0], slot[1], slot[2], fill)
            fill.color = Color.rgb(226, 236, 244)
            rect.set(slot[0] - u * 1.3f, slot[1] - u * 1.6f, slot[0] - u * 0.4f, slot[1] + u * 1.6f)
            canvas.drawRect(rect, fill)
            rect.set(slot[0] + u * 0.4f, slot[1] - u * 1.6f, slot[0] + u * 1.3f, slot[1] + u * 1.6f)
            canvas.drawRect(rect, fill)
        }

        private fun drawControls(canvas: Canvas) {
            val radius = min(width, height) * 0.17f
            drawStick(canvas, movePointer >= 0, moveOriginX, moveOriginY, moveX, moveY, radius,
                width * 0.22f, height * 0.72f, getString(R.string.move))
            drawStick(canvas, aimPointer >= 0, aimOriginX, aimOriginY, aimX, aimY, radius,
                width * 0.8f, height * 0.72f, getString(R.string.aim))
        }

        private fun drawStick(
            canvas: Canvas, active: Boolean, originX: Float, originY: Float,
            valueX: Float, valueY: Float, radius: Float,
            restX: Float, restY: Float, caption: String
        ) {
            val cx = if (active) originX else restX
            val cy = if (active) originY else restY
            val alpha = if (active) 62 else 26

            fill.color = Color.argb(alpha, 210, 228, 240)
            canvas.drawCircle(cx, cy, radius, fill)
            stroke.color = Color.argb(alpha + 34, 220, 234, 244)
            stroke.strokeWidth = radius * 0.03f
            canvas.drawCircle(cx, cy, radius, stroke)

            fill.color = Color.argb(if (active) 200 else 80,
                Color.red(colors.accent), Color.green(colors.accent), Color.blue(colors.accent))
            canvas.drawCircle(cx + valueX * radius * 0.72f, cy + valueY * radius * 0.72f, radius * 0.3f, fill)

            if (!active) {
                label.color = Color.argb(110, 226, 238, 246)
                label.textSize = radius * 0.2f
                canvas.drawText(caption, cx - radius * 0.22f, cy + radius * 0.07f, label)
            }
        }

        private fun drawEvolveOverlay(canvas: Canvas) {
            val u = unit()
            fill.color = Color.argb(170, 3, 7, 12)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)

            label.color = colors.accent
            label.textSize = u * 3.4f
            val title = getString(R.string.choose_evolution)
            canvas.drawText(title, width * 0.5f - title.length * u * 0.9f, height * 0.22f, label)

            val count = state[35].roundToInt().coerceIn(0, 4)
            val cardWidth = width * 0.19f
            val gap = width * 0.02f
            val total = count * cardWidth + max(0, count - 1) * gap
            val top = height * 0.3f
            val cardHeight = height * 0.4f

            for (index in 0 until count) {
                val id = state[36 + index].roundToInt()
                val left = width * 0.5f - total * 0.5f + index * (cardWidth + gap)

                fill.color = Color.argb(238, 10, 18, 26)
                rect.set(left, top, left + cardWidth, top + cardHeight)
                canvas.drawRoundRect(rect, u * 1.2f, u * 1.2f, fill)
                stroke.color = colors.accent
                stroke.strokeWidth = u * 0.28f
                canvas.drawRoundRect(rect, u * 1.2f, u * 1.2f, stroke)

                canvas.save()
                canvas.translate(left + cardWidth * 0.5f, top + cardHeight * 0.44f)
                val scale = cardWidth / 400f
                canvas.scale(scale, scale)
                painter.draw(canvas, tankGeometry(id), barrelStride, 44f, -90f, -90f, 0f,
                    colors.ally, colors.allyLight, colors.barrel, 0f)
                canvas.restore()

                label.color = Color.rgb(232, 240, 248)
                label.textSize = u * 2.1f
                val name = tankName(id)
                canvas.drawText(name, left + cardWidth * 0.5f - name.length * u * 0.55f,
                    top + cardHeight * 0.86f, label)
                label.color = colors.accent
                label.textSize = u * 1.7f
                canvas.drawText("T${tankInfo(id)[0]}", left + cardWidth * 0.5f - u * 1.1f,
                    top + cardHeight * 0.95f, label)
            }
        }

        private fun drawCheatOverlay(canvas: Canvas) {
            val u = unit()
            fill.color = Color.argb(228, 3, 7, 12)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)

            label.color = Color.rgb(240, 118, 118)
            label.textSize = u * 2.9f
            canvas.drawText(getString(R.string.cheat_menu), width * 0.06f, height * 0.075f, label)
            label.color = Color.argb(160, 210, 224, 236)
            label.textSize = u * 1.7f
            canvas.drawText(getString(R.string.cheat_hint), width * 0.06f, height * 0.112f, label)

            val columns = 4
            val rows = 7
            val left = width * 0.06f
            val top = height * 0.13f
            val cellWidth = width * 0.88f / columns
            val cellHeight = height * 0.72f / rows
            val total = cheatToggleCount + cheatActionCount

            for (cell in 0 until min(total, columns * rows)) {
                val cellLeft = left + (cell % columns) * cellWidth
                val cellTop = top + (cell / columns) * cellHeight
                val isToggle = cell < cheatToggleCount
                val on = isToggle && cheatState[cell]

                fill.color = when {
                    on -> Color.argb(232, 26, 74, 46)
                    isToggle -> Color.argb(226, 14, 22, 30)
                    else -> Color.argb(232, 54, 26, 26)
                }
                rect.set(cellLeft + u * 0.5f, cellTop + u * 0.5f,
                    cellLeft + cellWidth - u * 0.5f, cellTop + cellHeight - u * 0.5f)
                canvas.drawRoundRect(rect, u * 0.8f, u * 0.8f, fill)

                stroke.color = if (on) Color.rgb(120, 226, 150) else Color.argb(88, 150, 168, 184)
                stroke.strokeWidth = u * 0.16f
                canvas.drawRoundRect(rect, u * 0.8f, u * 0.8f, stroke)

                val name = if (isToggle) {
                    cheatLabels.getOrElse(cell) { "?" }
                } else {
                    actionLabels.getOrElse(cell - cheatToggleCount) { "?" }
                }

                label.color = if (on) Color.rgb(150, 240, 176) else Color.rgb(220, 230, 240)
                label.textSize = u * 1.7f
                canvas.drawText(name, cellLeft + u * 1.4f, cellTop + cellHeight * 0.46f, label)

                label.color = Color.argb(170, 190, 206, 220)
                label.textSize = u * 1.45f
                canvas.drawText(
                    if (isToggle) {
                        if (on) getString(R.string.on) else getString(R.string.off)
                    } else {
                        getString(R.string.run_action)
                    },
                    cellLeft + u * 1.4f, cellTop + cellHeight * 0.78f, label
                )
            }

            label.color = Color.argb(190, 220, 232, 242)
            label.textSize = u * 2f
            canvas.drawText(getString(R.string.close), width * 0.06f, height * 0.94f, label)
        }

        private fun drawPanelOverlay(canvas: Canvas, title: String, detail: String?) {
            val u = unit()
            fill.color = Color.argb(198, 3, 7, 12)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)

            label.color = colors.accent
            label.textSize = u * 4.6f
            canvas.drawText(title, width * 0.5f - title.length * u * 1.25f, height * 0.34f, label)

            if (detail != null) {
                label.color = Color.rgb(224, 234, 242)
                label.textSize = u * 2.3f
                canvas.drawText(detail, width * 0.5f - detail.length * u * 0.6f, height * 0.44f, label)
            }

            val captions = overlayCaptions()
            val buttonWidth = width * 0.2f
            val gap = width * 0.02f
            val total = captions.size * buttonWidth + (captions.size - 1) * gap
            val top = height * 0.58f

            for (index in captions.indices) {
                val left = width * 0.5f - total * 0.5f + index * (buttonWidth + gap)
                fill.color = Color.argb(238, 14, 26, 38)
                rect.set(left, top, left + buttonWidth, top + u * 9f)
                canvas.drawRoundRect(rect, u, u, fill)
                stroke.color = colors.accent
                stroke.strokeWidth = u * 0.2f
                canvas.drawRoundRect(rect, u, u, stroke)

                label.color = Color.rgb(232, 240, 248)
                label.textSize = u * 2.1f
                val caption = captions[index]
                canvas.drawText(caption, left + buttonWidth * 0.5f - caption.length * u * 0.58f,
                    top + u * 5.4f, label)
            }
        }
    }

    inner class TankPreviewView(private val tankId: Int) : View(this@Engine) {

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rect = RectF()
        private val painter = TankPainter()
        private var time = 0f
        private var lastNanos = SystemClock.elapsedRealtimeNanos()
        private var animating = true

        fun pause() {
            animating = false
        }

        fun resume() {
            animating = true
            lastNanos = SystemClock.elapsedRealtimeNanos()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val now = SystemClock.elapsedRealtimeNanos()
            time += ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            lastNanos = now

            val colors = theme()
            val corner = dp(10).toFloat()
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            fill.shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
                Color.rgb(12, 20, 30), Color.rgb(5, 9, 15), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, corner, corner, fill)
            fill.shader = null

            stroke.color = Color.argb(70, Color.red(colors.accent), Color.green(colors.accent),
                Color.blue(colors.accent))
            stroke.strokeWidth = 2f
            canvas.drawRoundRect(rect, corner, corner, stroke)

            canvas.save()
            canvas.translate(width * 0.5f, height * 0.5f)
            val scale = min(width, height) / 320f
            canvas.scale(scale, scale)
            painter.draw(canvas, tankGeometry(tankId), barrelStride, 44f,
                sin(time * 0.6f) * 14f, sin(time * 0.9f) * 26f,
                max(0f, sin(time * 3.2f)) * 0.4f,
                colors.ally, colors.allyLight, colors.barrel, 0f)
            canvas.restore()

            if (animating) postInvalidateOnAnimation()
        }
    }

    inner class MeterView(private val ratio: Float) : View(this@Engine) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bounds = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = height * 0.5f
            paint.color = Color.argb(180, 22, 32, 42)
            bounds.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(bounds, radius, radius, paint)
            paint.color = theme().accent
            bounds.set(0f, 0f, width * ratio.coerceIn(0.03f, 1f), height.toFloat())
            canvas.drawRoundRect(bounds, radius, radius, paint)
        }
    }

    private class SelectionListener(
        private var current: Int,
        private val callback: (Int) -> Unit
    ) : android.widget.AdapterView.OnItemSelectedListener {

        override fun onItemSelected(
            parent: android.widget.AdapterView<*>?,
            view: View?,
            position: Int,
            id: Long
        ) {
            if (position == current) return
            current = position
            callback(position)
        }

        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }
}

private const val OVERLAY_NONE = 0
private const val OVERLAY_PAUSE = 1
private const val OVERLAY_EVOLVE = 2
private const val OVERLAY_CHEATS = 3
private const val OVERLAY_RESULT = 4

private fun blend(from: Int, to: Int, amount: Float): Int {
    val t = amount.coerceIn(0f, 1f)
    return Color.rgb(
        (Color.red(from) + (Color.red(to) - Color.red(from)) * t).roundToInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * t).roundToInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).roundToInt()
    )
}

private fun darken(color: Int, factor: Float): Int = Color.rgb(
    (Color.red(color) * factor).roundToInt().coerceIn(0, 255),
    (Color.green(color) * factor).roundToInt().coerceIn(0, 255),
    (Color.blue(color) * factor).roundToInt().coerceIn(0, 255)
)
