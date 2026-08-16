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
import android.graphics.SweepGradient
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

class Engine : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var arena: ArenaView
    private lateinit var preferences: SharedPreferences

    private var panel: View? = null
    private val previews = mutableListOf<PreviewView>()
    private var vibrator: Vibrator? = null

    private var quality = 3
    private var highRefresh = true
    private var shadows = true
    private var particles = true
    private var haptics = true
    private var autoFire = true
    private var sensitivity = 1.0f
    private var shaderMode = 0
    private var selectedMode = 0
    private var selectedHull = 0
    private var selectedAbility = 0

    companion object {
        private const val PREFERENCES = "omni_tank"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_QUALITY = "quality"
        private const val KEY_SHADER = "shader"
        private const val KEY_HIGH_REFRESH = "high_refresh"
        private const val KEY_SHADOWS = "shadows"
        private const val KEY_PARTICLES = "particles"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_AUTO_FIRE = "auto_fire"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_MODE = "mode"
        private const val KEY_HULL = "hull"
        private const val KEY_ABILITY = "ability"
        private const val KEY_BEST = "best_score"

        private val LANGUAGE_TAGS = arrayOf("tr", "en", "de", "es", "fr")

        init { System.loadLibrary("omni_tank") }
    }

    private external fun nativeGetLayout(): IntArray
    private external fun nativeStep(deltaSeconds: Float, out: FloatArray): Boolean
    private external fun nativeReset()
    private external fun nativeRespawn()
    private external fun nativeGetEngineInfo(): String
    private external fun nativeSetMode(mode: Int)
    private external fun nativeSetHull(hull: Int)
    private external fun nativeSetGraphicsQuality(level: Int)
    private external fun nativeSetDeveloperFlag(flag: Int, enabled: Boolean)
    private external fun nativeSetInput(moveX: Float, moveY: Float, aimX: Float, aimY: Float, firing: Boolean)
    private external fun nativeTriggerAbility(index: Int): Boolean
    private external fun nativeUpgrade(stat: Int): Boolean
    private external fun nativeGetUpgrades(): IntArray
    private external fun nativeGetAbilityCooldowns(): FloatArray

    // ---------------------------------------------------------------- lifecycle

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
        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        loadPreferences()

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VibratorManager::class.java))?.defaultVibrator
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

        nativeSetMode(selectedMode)
        nativeSetHull(selectedHull)
        nativeSetGraphicsQuality(quality)

        showHome()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (arena.running) showPause() else showHome()
            }
        })
    }

    private fun loadPreferences() {
        quality = preferences.getInt(KEY_QUALITY, 3)
        shaderMode = preferences.getInt(KEY_SHADER, 0)
        highRefresh = preferences.getBoolean(KEY_HIGH_REFRESH, true)
        shadows = preferences.getBoolean(KEY_SHADOWS, true)
        particles = preferences.getBoolean(KEY_PARTICLES, true)
        haptics = preferences.getBoolean(KEY_HAPTICS, true)
        autoFire = preferences.getBoolean(KEY_AUTO_FIRE, true)
        sensitivity = preferences.getFloat(KEY_SENSITIVITY, 1.0f)
        selectedMode = preferences.getInt(KEY_MODE, 0)
        selectedHull = preferences.getInt(KEY_HULL, 0)
        selectedAbility = preferences.getInt(KEY_ABILITY, 0)
    }

    private fun applyRefreshRate() {
        val modes = window.decorView.display?.supportedModes ?: return
        val attributes = window.attributes
        attributes.preferredRefreshRate = if (highRefresh) {
            modes.maxOf { it.refreshRate }
        } else {
            60f
        }
        window.attributes = attributes
    }

    override fun onResume() {
        super.onResume()
        WindowCompat.getInsetsController(window, window.decorView)
            .hide(WindowInsetsCompat.Type.systemBars())
        previews.forEach { it.resume() }
        arena.invalidate()
    }

    override fun onPause() {
        if (arena.running) {
            arena.running = false
            showPause()
        }
        previews.forEach { it.pause() }
        super.onPause()
    }

    // ------------------------------------------------------------------- helpers

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun currentPalette(): Palette = Palette.of(shaderMode)

    private fun pulse(milliseconds: Long) {
        if (!haptics) return
        vibrator?.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun text(value: String, size: Float = 14f, color: Int = Color.rgb(226, 234, 241)): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }

    private fun button(value: String, action: () -> Unit): Button = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 14f
        minHeight = dp(42)
        setTextColor(Color.rgb(232, 240, 246))
        setBackgroundColor(Color.argb(230, 24, 38, 52))
        setOnClickListener { pulse(12); action() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(0, dp(3), 0, dp(3)) }
    }

    private fun panel(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setBackgroundColor(Color.argb(246, 6, 12, 19))
        addView(
            text(title, 22f, currentPalette().accent).apply {
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(-1, dp(44))
        )
    }

    /** Wraps a menu body in a scroll container sized for a landscape screen. */
    private fun mount(body: LinearLayout) {
        panel?.let(root::removeView)
        previews.forEach { it.pause() }
        previews.clear()

        val scroll = ScrollView(this)
        scroll.isFillViewport = false
        scroll.addView(body, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        val width = min(dp(520), (resources.displayMetrics.widthPixels * 0.56f).roundToInt())
        val height = (resources.displayMetrics.heightPixels * 0.88f).roundToInt()
        scroll.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)

        panel = scroll
        root.addView(scroll)
        arena.running = false
        arena.invalidate()
    }

    private fun spinner(options: Array<String>, selection: Int, onPick: (Int) -> Unit): Spinner =
        Spinner(this).apply {
            adapter = ArrayAdapter(this@Engine, android.R.layout.simple_spinner_dropdown_item, options)
            setSelection(selection.coerceIn(0, max(0, options.size - 1)))
            onItemSelectedListener = SelectionListener(selection) { onPick(it) }
        }

    private fun statRow(name: String, value: Int, maximum: Int): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(name, 13f), LinearLayout.LayoutParams(0, dp(30), 1f))
            addView(BarView(value.toFloat() / maximum.toFloat()), LinearLayout.LayoutParams(dp(120), dp(12)))
            addView(
                text("$value", 13f).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(dp(46), dp(30))
            )
        }

    // --------------------------------------------------------------------- menus

    private fun showHome() {
        val body = panel(getString(R.string.app_name))
        body.addView(text(getString(R.string.objective_text), 13f).apply { gravity = Gravity.CENTER })
        body.addView(
            text(
                "${getString(R.string.best_score)}: ${preferences.getInt(KEY_BEST, 0)}",
                13f,
                currentPalette().accent
            ).apply { gravity = Gravity.CENTER }
        )
        body.addView(button(getString(R.string.play)) { startGame(true) })
        body.addView(button(getString(R.string.modes)) { showModes() })
        body.addView(button(getString(R.string.online)) { showOnline() })
        body.addView(button(getString(R.string.garage)) { showGarage() })
        body.addView(button(getString(R.string.skills)) { showAbilities() })
        body.addView(button(getString(R.string.customize)) { showCustomize() })
        body.addView(button(getString(R.string.shop)) { showShop() })
        body.addView(button(getString(R.string.settings)) { showSettings() })
        if (BuildConfig.DEBUG) body.addView(button(getString(R.string.developer)) { showDeveloper() })
        mount(body)
    }

    private fun showModes() {
        val body = panel(getString(R.string.mode_title))
        body.addView(text(getString(R.string.mode_description), 13f))
        body.addView(
            spinner(resources.getStringArray(R.array.game_mode_options), selectedMode) {
                selectedMode = it
                preferences.edit().putInt(KEY_MODE, it).apply()
                nativeSetMode(it)
            },
            LinearLayout.LayoutParams(-1, dp(48))
        )
        body.addView(text(getString(R.string.controls_hint), 12f))
        body.addView(button(getString(R.string.start)) { startGame(true) })
        body.addView(button(getString(R.string.back)) { showHome() })
        mount(body)
    }

    private fun showOnline() {
        val body = panel(getString(R.string.online_title))
        body.addView(text(getString(R.string.online_status), 16f, currentPalette().accent))
        body.addView(text(getString(R.string.online_ready_detail), 12f))
        body.addView(text(getString(R.string.online_client), 12f))
        body.addView(text(getString(R.string.online_backend_required), 12f))
        body.addView(button(getString(R.string.online_training)) {
            selectedMode = 3
            nativeSetMode(3)
            startGame(true)
        })
        body.addView(button(getString(R.string.back)) { showHome() })
        mount(body)
    }

    private fun showGarage() {
        val upgrades = nativeGetUpgrades()
        val body = panel(getString(R.string.garage_title))
        body.addView(
            text(getString(R.string.tank_name), 19f, currentPalette().accent).apply { gravity = Gravity.CENTER }
        )
        body.addView(PreviewView(PREVIEW_TANK).also(previews::add), LinearLayout.LayoutParams(-1, dp(140)))
        body.addView(
            text(
                "${getString(R.string.level)} ${upgrades[7]}   " +
                    "${getString(R.string.rank)}: ${getString(R.string.rank_value)}   " +
                    "${getString(R.string.best_score)}: ${preferences.getInt(KEY_BEST, 0)}",
                12f
            )
        )
        body.addView(
            text("${getString(R.string.stat_points)}: ${upgrades[6]}", 14f, currentPalette().accent)
        )

        val labels = arrayOf(
            R.string.upgrade_damage,
            R.string.upgrade_reload,
            R.string.upgrade_bullet_speed,
            R.string.upgrade_move_speed,
            R.string.upgrade_max_health,
            R.string.upgrade_regen
        )
        for (index in labels.indices) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.addView(text(getString(labels[index]), 13f), LinearLayout.LayoutParams(0, dp(38), 1f))
            row.addView(BarView(upgrades[index] / 8f), LinearLayout.LayoutParams(dp(96), dp(12)))
            val plus = Button(this).apply {
                text = if (upgrades[index] >= 8) getString(R.string.upgrade_max) else "+"
                isAllCaps = false
                textSize = 13f
                isEnabled = upgrades[6] > 0 && upgrades[index] < 8
                setTextColor(Color.rgb(232, 240, 246))
                setBackgroundColor(Color.argb(230, 26, 46, 38))
                setOnClickListener {
                    if (nativeUpgrade(index)) {
                        pulse(14)
                        showGarage()
                    }
                }
            }
            row.addView(plus, LinearLayout.LayoutParams(dp(66), dp(38)))
            body.addView(row)
        }

        if (upgrades[6] == 0) body.addView(text(getString(R.string.no_stat_points), 11f))
        body.addView(button(getString(R.string.customize)) { showCustomize() })
        body.addView(button(getString(R.string.back)) { showHome() })
        mount(body)
    }

    private fun showAbilities() {
        val body = panel(getString(R.string.abilities_title))
        val descriptions = arrayOf(
            R.string.ability_aegis,
            R.string.ability_overdrive,
            R.string.ability_repair,
            R.string.ability_scan
        )
        val detail = text(getString(descriptions[selectedAbility]), 13f)
        val preview = PreviewView(PREVIEW_ABILITY).also(previews::add)
        preview.abilityIndex = selectedAbility

        body.addView(text(getString(R.string.ability_select), 14f))
        body.addView(
            spinner(resources.getStringArray(R.array.ability_options), selectedAbility) {
                selectedAbility = it
                preferences.edit().putInt(KEY_ABILITY, it).apply()
                preview.abilityIndex = it
                detail.text = getString(descriptions[it])
            },
            LinearLayout.LayoutParams(-1, dp(48))
        )
        body.addView(text(getString(R.string.preview_title), 13f, currentPalette().accent))
        body.addView(preview, LinearLayout.LayoutParams(-1, dp(160)))
        body.addView(detail)
        body.addView(text(getString(R.string.preview_hint), 11f))
        body.addView(button(getString(R.string.back)) { showHome() })
        mount(body)
    }

    private fun showCustomize() {
        val body = panel(getString(R.string.customize_title))
        val preview = PreviewView(PREVIEW_TANK).also(previews::add)
        preview.hullIndex = selectedHull
        preview.shaderIndex = shaderMode

        body.addView(text(getString(R.string.hull_profile), 13f))
        body.addView(
            spinner(resources.getStringArray(R.array.hull_profile_options), selectedHull) {
                selectedHull = it
                preferences.edit().putInt(KEY_HULL, it).apply()
                nativeSetHull(it)
                preview.hullIndex = it
            },
            LinearLayout.LayoutParams(-1, dp(48))
        )
        body.addView(preview, LinearLayout.LayoutParams(-1, dp(150)))

        val profiles = arrayOf(intArrayOf(78, 74, 80), intArrayOf(94, 58, 72), intArrayOf(58, 96, 88))
        val stats = profiles[selectedHull.coerceIn(0, 2)]
        body.addView(statRow(getString(R.string.mobility), stats[0], 100))
        body.addView(statRow(getString(R.string.armor), stats[1], 100))
        body.addView(statRow(getString(R.string.energy), stats[2], 100))

        body.addView(text(getString(R.string.paint_profile), 13f))
        body.addView(
            spinner(resources.getStringArray(R.array.shader_mode_options), shaderMode) {
                shaderMode = it
                preferences.edit().putInt(KEY_SHADER, it).apply()
                preview.shaderIndex = it
                arena.onPaletteChanged()
            },
            LinearLayout.LayoutParams(-1, dp(48))
        )
        body.addView(button(getString(R.string.apply)) { showGarage() })
        body.addView(button(getString(R.string.back)) { showHome() })
        mount(body)
    }

    private fun showShop() {
        val body = panel(getString(R.string.shop_title))
        body.addView(text("${getString(R.string.field_skin)}   ${getString(R.string.owned)}", 13f))
        body.addView(text("${getString(R.string.desert_frame)}   ${getString(R.string.credits_2400)} ${getString(R.string.credits)}", 13f))
        body.addView(text("${getString(R.string.arctic_frame)}   ${getString(R.string.credits_3200)} ${getString(R.string.credits)}", 13f))
        body.addView(text("${getString(R.string.pilot_badge)}   ${getString(R.string.credits_900)} ${getString(R.string.credits)}", 13f))
        body.addView(text(getString(R.string.coming_soon), 11f))
        body.addView(button(getString(R.string.back)) { showHome() })
        mount(body)
    }

    private fun showSettings() {
        val body = panel(getString(R.string.settings_title))

        body.addView(text(getString(R.string.graphics_quality), 13f))
        body.addView(
            spinner(resources.getStringArray(R.array.graphics_quality_options), quality) {
                quality = it
                preferences.edit().putInt(KEY_QUALITY, it).apply()
                nativeSetGraphicsQuality(it)
            },
            LinearLayout.LayoutParams(-1, dp(48))
        )

        body.addView(text(getString(R.string.shader_mode), 13f))
        body.addView(
            spinner(resources.getStringArray(R.array.shader_mode_options), shaderMode) {
                shaderMode = it
                preferences.edit().putInt(KEY_SHADER, it).apply()
                arena.onPaletteChanged()
            },
            LinearLayout.LayoutParams(-1, dp(48))
        )

        body.addView(toggle(getString(R.string.high_refresh), highRefresh) {
            highRefresh = it
            preferences.edit().putBoolean(KEY_HIGH_REFRESH, it).apply()
            applyRefreshRate()
        })
        body.addView(toggle(getString(R.string.shadows), shadows) {
            shadows = it
            preferences.edit().putBoolean(KEY_SHADOWS, it).apply()
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

        body.addView(text(getString(R.string.sensitivity), 13f))
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
        body.addView(slider, LinearLayout.LayoutParams(-1, dp(44)))

        body.addView(text(getString(R.string.language), 13f))
        body.addView(
            spinner(resources.getStringArray(R.array.language_options), languageIndex()) {
                applyLanguage(it)
            },
            LinearLayout.LayoutParams(-1, dp(48))
        )
        body.addView(button(getString(R.string.back)) { showHome() })
        mount(body)
    }

    private fun toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch =
        Switch(this).apply {
            text = label
            textSize = 13f
            isChecked = checked
            setTextColor(Color.rgb(226, 234, 241))
            setPadding(dp(10), 0, dp(10), 0)
            setOnCheckedChangeListener { _, value -> onChange(value) }
            layoutParams = LinearLayout.LayoutParams(-1, dp(44))
        }

    private fun languageIndex(): Int {
        val stored = preferences.getString(KEY_LANGUAGE, null)
        val tag = stored ?: resources.configuration.locales[0].language
        val index = LANGUAGE_TAGS.indexOf(tag)
        return if (index >= 0) index else 1
    }

    private fun applyLanguage(index: Int) {
        val tag = LANGUAGE_TAGS[index.coerceIn(0, LANGUAGE_TAGS.size - 1)]
        if (tag == preferences.getString(KEY_LANGUAGE, null)) return
        preferences.edit().putString(KEY_LANGUAGE, tag).apply()
        recreate()
    }

    private fun showDeveloper() {
        val body = panel(getString(R.string.developer))
        body.addView(text(getString(R.string.developer_note), 11f))
        body.addView(text("${getString(R.string.engine_info)}: ${nativeGetEngineInfo()}", 11f))
        body.addView(toggle(getString(R.string.dev_invulnerability), false) { nativeSetDeveloperFlag(0, it) })
        body.addView(toggle(getString(R.string.dev_infinite_energy), false) { nativeSetDeveloperFlag(1, it) })
        body.addView(toggle(getString(R.string.dev_show_bounds), arena.showBounds) { arena.showBounds = it })
        body.addView(button(getString(R.string.clear_state)) { nativeReset(); showDeveloper() })
        body.addView(button(getString(R.string.back)) { showHome() })
        mount(body)
    }

    private fun showPause() {
        val body = panel(getString(R.string.pause))
        body.addView(
            text(
                "${getString(R.string.score)}: ${arena.score()}   " +
                    "${getString(R.string.kills)}: ${arena.kills()}   " +
                    "${getString(R.string.wave)}: ${arena.wave()}",
                13f
            )
        )
        body.addView(button(getString(R.string.resume)) { startGame(false) })
        body.addView(button(getString(R.string.upgrades)) { showGarage() })
        body.addView(button(getString(R.string.settings)) { showSettings() })
        body.addView(button(getString(R.string.reset)) { nativeReset(); startGame(true) })
        body.addView(button(getString(R.string.main_menu)) { showHome() })
        mount(body)
    }

    private fun showGameOver() {
        val score = arena.score()
        if (score > preferences.getInt(KEY_BEST, 0)) {
            preferences.edit().putInt(KEY_BEST, score).apply()
        }
        pulse(60)

        val body = panel(getString(R.string.game_over))
        body.addView(
            text("${getString(R.string.final_score)}: $score", 17f, currentPalette().accent)
                .apply { gravity = Gravity.CENTER }
        )
        body.addView(
            text(
                "${getString(R.string.kills)}: ${arena.kills()}   " +
                    "${getString(R.string.wave)}: ${arena.wave()}   " +
                    "${getString(R.string.level)}: ${arena.level()}",
                13f
            ).apply { gravity = Gravity.CENTER }
        )
        body.addView(
            text("${getString(R.string.best_score)}: ${preferences.getInt(KEY_BEST, 0)}", 13f)
                .apply { gravity = Gravity.CENTER }
        )
        body.addView(button(getString(R.string.respawn)) { nativeRespawn(); startGame(false) })
        body.addView(button(getString(R.string.upgrades)) { showGarage() })
        body.addView(button(getString(R.string.reset)) { nativeReset(); startGame(true) })
        body.addView(button(getString(R.string.main_menu)) { showHome() })
        mount(body)
    }

    private fun startGame(fresh: Boolean) {
        if (fresh) {
            nativeReset()
            nativeSetMode(selectedMode)
            nativeSetHull(selectedHull)
            nativeSetGraphicsQuality(quality)
        }
        panel?.let(root::removeView)
        panel = null
        previews.forEach { it.pause() }
        previews.clear()
        arena.onPaletteChanged()
        arena.resumeGame()
    }

    // ------------------------------------------------------------------ palettes

    private class Palette(
        val background: Int,
        val backgroundEdge: Int,
        val grid: Int,
        val hull: Int,
        val hullLight: Int,
        val tread: Int,
        val turret: Int,
        val enemy: Int,
        val enemyLight: Int,
        val square: Int,
        val triangle: Int,
        val pentagon: Int,
        val playerBullet: Int,
        val enemyBullet: Int,
        val accent: Int,
        val bloom: Boolean
    ) {
        companion object {
            private val MODES = arrayOf(
                Palette( // Neon
                    Color.rgb(6, 11, 18), Color.rgb(2, 4, 8), Color.argb(34, 132, 214, 190),
                    Color.rgb(46, 122, 84), Color.rgb(96, 196, 128), Color.rgb(28, 74, 54),
                    Color.rgb(74, 168, 112), Color.rgb(178, 62, 78), Color.rgb(232, 108, 116),
                    Color.rgb(228, 186, 84), Color.rgb(226, 120, 82), Color.rgb(126, 128, 226),
                    Color.rgb(150, 240, 176), Color.rgb(255, 136, 128), Color.rgb(122, 226, 174),
                    true
                ),
                Palette( // Classic
                    Color.rgb(24, 26, 30), Color.rgb(14, 15, 18), Color.argb(30, 210, 210, 210),
                    Color.rgb(70, 120, 176), Color.rgb(116, 172, 224), Color.rgb(48, 82, 120),
                    Color.rgb(96, 148, 200), Color.rgb(198, 88, 74), Color.rgb(238, 128, 110),
                    Color.rgb(226, 200, 96), Color.rgb(226, 122, 96), Color.rgb(134, 126, 216),
                    Color.rgb(226, 232, 238), Color.rgb(244, 150, 132), Color.rgb(126, 178, 232),
                    false
                ),
                Palette( // Thermal
                    Color.rgb(14, 6, 20), Color.rgb(4, 2, 8), Color.argb(30, 226, 122, 60),
                    Color.rgb(188, 78, 32), Color.rgb(248, 158, 62), Color.rgb(112, 42, 20),
                    Color.rgb(236, 122, 44), Color.rgb(74, 120, 220), Color.rgb(128, 176, 250),
                    Color.rgb(250, 214, 96), Color.rgb(250, 148, 70), Color.rgb(214, 96, 222),
                    Color.rgb(255, 214, 122), Color.rgb(140, 190, 255), Color.rgb(250, 168, 72),
                    true
                ),
                Palette( // Blueprint
                    Color.rgb(8, 22, 48), Color.rgb(4, 12, 28), Color.argb(56, 128, 190, 255),
                    Color.rgb(30, 78, 140), Color.rgb(96, 168, 236), Color.rgb(22, 54, 98),
                    Color.rgb(120, 190, 248), Color.rgb(196, 214, 240), Color.rgb(232, 242, 255),
                    Color.rgb(150, 206, 255), Color.rgb(126, 190, 250), Color.rgb(180, 214, 255),
                    Color.rgb(210, 236, 255), Color.rgb(255, 226, 168), Color.rgb(138, 202, 255),
                    false
                ),
                Palette( // Noir
                    Color.rgb(12, 12, 12), Color.rgb(2, 2, 2), Color.argb(26, 220, 220, 220),
                    Color.rgb(72, 72, 72), Color.rgb(150, 150, 150), Color.rgb(44, 44, 44),
                    Color.rgb(122, 122, 122), Color.rgb(168, 168, 168), Color.rgb(226, 226, 226),
                    Color.rgb(196, 196, 196), Color.rgb(160, 160, 160), Color.rgb(220, 220, 220),
                    Color.rgb(250, 250, 250), Color.rgb(206, 206, 206), Color.rgb(236, 236, 236),
                    false
                )
            )

            fun of(index: Int): Palette = MODES[index.coerceIn(0, MODES.size - 1)]
        }
    }

    // ------------------------------------------------------------------ arena view

    inner class ArenaView : View(this@Engine) {

        private val layout = nativeGetLayout()
        private val state = FloatArray(layout[0])
        private val headerFloats = layout[1]
        private val maxBullets = layout[2]
        private val bulletFloats = layout[3]
        private val maxEnemies = layout[4]
        private val enemyFloats = layout[5]
        private val maxShapes = layout[6]
        private val shapeFloats = layout[7]
        private val maxParticles = layout[8]
        private val particleFloats = layout[9]

        private val bulletBase = headerFloats
        private val enemyBase = bulletBase + maxBullets * bulletFloats
        private val shapeBase = enemyBase + maxEnemies * enemyFloats
        private val particleBase = shapeBase + maxShapes * shapeFloats

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
        private val rect = RectF()
        private val polygon = Path()
        private val cooldowns = FloatArray(4) { 1f }

        private var palette = currentPalette()
        private var backgroundShader: Shader? = null
        private var vignetteShader: Shader? = null
        private var bodyShader: Shader? = null
        private var turretShader: Shader? = null
        private var shieldShader: Shader? = null

        private var lastNanos = SystemClock.elapsedRealtimeNanos()
        private var ambient = 0f
        private var fps = 60f
        private var cameraX = 0f
        private var cameraY = 0f
        private var defeatHandled = false

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

        var running = false
            set(value) {
                field = value
                if (!value) nativeSetInput(0f, 0f, 0f, 0f, false)
                invalidate()
            }

        var showBounds = false

        init {
            isFocusable = true
            isClickable = true
            setLayerType(LAYER_TYPE_HARDWARE, null)
            nativeStep(0f, state)
        }

        fun resumeGame() {
            defeatHandled = false
            lastNanos = SystemClock.elapsedRealtimeNanos()
            running = true
            requestFocus()
        }

        fun onPaletteChanged() {
            palette = currentPalette()
            backgroundShader = null
            bodyShader = null
            turretShader = null
            shieldShader = null
            invalidate()
        }

        fun score(): Int = state[12].roundToInt()
        fun kills(): Int = state[21].roundToInt()
        fun wave(): Int = state[20].roundToInt()
        fun level(): Int = state[9].roundToInt()

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            backgroundShader = null
            vignetteShader = null
        }

        private fun ensureShaders() {
            if (backgroundShader == null && width > 0 && height > 0) {
                backgroundShader = RadialGradient(
                    width * 0.5f, height * 0.5f, max(width, height) * 0.78f,
                    palette.background, palette.backgroundEdge, Shader.TileMode.CLAMP
                )
                vignetteShader = RadialGradient(
                    width * 0.5f, height * 0.5f, max(width, height) * 0.62f,
                    Color.TRANSPARENT, Color.argb(150, 0, 0, 0), Shader.TileMode.CLAMP
                )
            }
            if (bodyShader == null) {
                bodyShader = LinearGradient(
                    0f, -72f, 0f, 72f, palette.hullLight, palette.hull, Shader.TileMode.CLAMP
                )
                turretShader = RadialGradient(
                    -14f, -14f, 62f, palette.turret, palette.hull, Shader.TileMode.CLAMP
                )
                shieldShader = SweepGradient(
                    0f, 0f,
                    intArrayOf(palette.accent, Color.TRANSPARENT, palette.accent, Color.TRANSPARENT, palette.accent),
                    null
                )
            }
        }

        // ------------------------------------------------------------- input

        private fun abilityButtonCenter(index: Int, out: FloatArray) {
            val radius = buttonRadius()
            out[0] = width - radius * 1.5f - index * radius * 2.4f
            out[1] = height - radius * 1.6f
        }

        private fun buttonRadius(): Float = min(width, height) * 0.072f

        private fun stickRadius(): Float = min(width, height) * 0.17f

        private fun pauseHit(x: Float, y: Float): Boolean {
            val size = min(width, height) * 0.07f
            return x > width - size * 1.9f && y < size * 1.9f
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!running) return false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val index = event.actionIndex
                    val id = event.getPointerId(index)
                    val x = event.getX(index)
                    val y = event.getY(index)

                    if (pauseHit(x, y)) {
                        post { showPause() }
                        return true
                    }

                    val slot = abilityHit(x, y)
                    if (slot >= 0) {
                        if (nativeTriggerAbility(slot)) pulse(18)
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

                MotionEvent.ACTION_MOVE -> {
                    for (index in 0 until event.pointerCount) {
                        val id = event.getPointerId(index)
                        val x = event.getX(index)
                        val y = event.getY(index)
                        val radius = stickRadius()

                        if (id == movePointer) {
                            moveX = ((x - moveOriginX) / radius * sensitivity).coerceIn(-1f, 1f)
                            moveY = ((y - moveOriginY) / radius * sensitivity).coerceIn(-1f, 1f)
                        } else if (id == aimPointer) {
                            aimX = ((x - aimOriginX) / radius * sensitivity).coerceIn(-1f, 1f)
                            aimY = ((y - aimOriginY) / radius * sensitivity).coerceIn(-1f, 1f)
                        }
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    val id = event.getPointerId(event.actionIndex)
                    if (id == movePointer || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        movePointer = -1
                        moveX = 0f
                        moveY = 0f
                    }
                    if (id == aimPointer || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        aimPointer = -1
                        aimX = 0f
                        aimY = 0f
                    }
                }
            }

            val firing = aimPointer >= 0 && (autoFire || hypot(aimX, aimY) > 0.55f)
            nativeSetInput(moveX, moveY, aimX, aimY, firing)
            return true
        }

        private fun abilityHit(x: Float, y: Float): Int {
            val center = FloatArray(2)
            val radius = buttonRadius()
            for (index in 0 until 4) {
                abilityButtonCenter(index, center)
                if (hypot(x - center[0], y - center[1]) <= radius * 1.15f) return index
            }
            return -1
        }

        // ------------------------------------------------------------- rendering

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            ensureShaders()

            val now = SystemClock.elapsedRealtimeNanos()
            val delta = ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            lastNanos = now
            ambient += delta

            if (running) {
                nativeStep(delta, state)
                if (delta > 0f) fps += (1f / delta - fps) * 0.08f
                val cooldownValues = nativeGetAbilityCooldowns()
                System.arraycopy(cooldownValues, 0, cooldowns, 0, min(cooldowns.size, cooldownValues.size))

                if (state[19] < 0.5f && state[33] <= 0f && !defeatHandled) {
                    defeatHandled = true
                    running = false
                    post { showGameOver() }
                }
            }

            drawBackdrop(canvas)

            val scale = viewScale()
            cameraX += (state[0] - cameraX) * min(1f, delta * 6.4f + 0.02f)
            cameraY += (state[1] - cameraY) * min(1f, delta * 6.4f + 0.02f)

            canvas.save()
            canvas.translate(width * 0.5f, height * 0.5f)
            canvas.scale(scale, scale)
            canvas.translate(-cameraX, -cameraY)
            drawGrid(canvas, scale)
            drawArenaBounds(canvas)
            drawShapes(canvas)
            drawEnemies(canvas)
            if (particles) drawParticles(canvas)
            drawBullets(canvas)
            if (state[19] > 0.5f) drawPlayer(canvas)
            canvas.restore()

            drawVignette(canvas)
            drawHud(canvas)
            if (running) drawControls(canvas)

            postInvalidateOnAnimation()
        }

        private fun viewScale(): Float {
            val zoom = state[13].coerceIn(0.8f, 1.6f)
            return height / (1450f * zoom)
        }

        private fun drawBackdrop(canvas: Canvas) {
            fill.shader = backgroundShader
            fill.style = Paint.Style.FILL
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
            fill.shader = null
        }

        private fun drawVignette(canvas: Canvas) {
            fill.shader = vignetteShader
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
            fill.shader = null

            val damage = state[32].coerceIn(0f, 1f)
            if (damage > 0.01f) {
                fill.color = Color.argb((damage * 110f).roundToInt().coerceIn(0, 110), 190, 40, 46)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
            }
            if (state[16] > 0.01f) {
                fill.color = Color.argb((state[16] * 40f).roundToInt().coerceIn(0, 40), 90, 200, 255)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
            }
        }

        private fun drawGrid(canvas: Canvas, scale: Float) {
            val step = 128f
            val halfWide = width / scale * 0.5f + step
            val halfHigh = height / scale * 0.5f + step
            stroke.color = palette.grid
            stroke.strokeWidth = 1.4f / scale * 2f

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

        private fun drawArenaBounds(canvas: Canvas) {
            val halfWidth = state[17]
            val halfHeight = state[18]
            stroke.color = palette.accent
            stroke.strokeWidth = 8f
            rect.set(-halfWidth, -halfHeight, halfWidth, halfHeight)
            canvas.drawRect(rect, stroke)

            fill.color = Color.argb(26, Color.red(palette.accent), Color.green(palette.accent), Color.blue(palette.accent))
            canvas.drawRect(-halfWidth - 900f, -halfHeight - 900f, halfWidth + 900f, -halfHeight, fill)
            canvas.drawRect(-halfWidth - 900f, halfHeight, halfWidth + 900f, halfHeight + 900f, fill)
            canvas.drawRect(-halfWidth - 900f, -halfHeight, -halfWidth, halfHeight, fill)
            canvas.drawRect(halfWidth, -halfHeight, halfWidth + 900f, halfHeight, fill)
        }

        private fun buildPolygon(sides: Int, size: Float, rotation: Float) {
            polygon.reset()
            val count = max(3, sides)
            for (i in 0 until count) {
                val angle = rotation + i * (2.0 * Math.PI / count).toFloat()
                val px = cos(angle) * size
                val py = sin(angle) * size
                if (i == 0) polygon.moveTo(px, py) else polygon.lineTo(px, py)
            }
            polygon.close()
        }

        private fun drawShapes(canvas: Canvas) {
            val count = state[28].roundToInt().coerceIn(0, maxShapes)
            for (i in 0 until count) {
                val base = shapeBase + i * shapeFloats
                val x = state[base]
                val y = state[base + 1]
                if (!visible(x, y, 220f)) continue

                val rotation = state[base + 2] / 57.2958f
                val size = state[base + 3]
                val sides = state[base + 4].roundToInt()
                val health = state[base + 5]
                val flash = state[base + 6]

                val color = when (sides) {
                    3 -> palette.triangle
                    5 -> palette.pentagon
                    else -> palette.square
                }

                canvas.save()
                canvas.translate(x, y)
                buildPolygon(sides, size, rotation)

                if (shadows) {
                    fill.color = Color.argb(70, 0, 0, 0)
                    canvas.save()
                    canvas.translate(6f, 10f)
                    canvas.drawPath(polygon, fill)
                    canvas.restore()
                }

                fill.color = blend(color, Color.WHITE, flash * 0.7f)
                canvas.drawPath(polygon, fill)
                stroke.color = darken(color, 0.55f)
                stroke.strokeWidth = 5f
                canvas.drawPath(polygon, stroke)

                if (health < 0.999f) drawHealthBar(canvas, size + 22f, size * 1.5f, health)
                canvas.restore()
            }
        }

        private fun drawEnemies(canvas: Canvas) {
            val count = state[27].roundToInt().coerceIn(0, maxEnemies)
            for (i in 0 until count) {
                val base = enemyBase + i * enemyFloats
                val x = state[base]
                val y = state[base + 1]
                if (!visible(x, y, 260f)) continue

                val heading = state[base + 2]
                val turret = state[base + 3]
                val health = state[base + 4]
                val kind = state[base + 5].roundToInt()
                val scale = state[base + 6]
                val flash = state[base + 7]

                canvas.save()
                canvas.translate(x, y)
                canvas.scale(scale, scale)

                if (shadows) {
                    fill.color = Color.argb(80, 0, 0, 0)
                    rect.set(-46f, -34f, 46f, 34f)
                    canvas.save()
                    canvas.rotate(heading)
                    canvas.translate(5f, 12f)
                    canvas.drawRoundRect(rect, 14f, 14f, fill)
                    canvas.restore()
                }

                canvas.save()
                canvas.rotate(heading)
                fill.color = darken(palette.enemy, 0.7f)
                rect.set(-52f, -40f, 52f, 40f)
                canvas.drawRoundRect(rect, 12f, 12f, fill)
                fill.color = blend(palette.enemy, Color.WHITE, flash * 0.85f)
                rect.set(-42f, -30f, 42f, 30f)
                canvas.drawRoundRect(rect, 10f, 10f, fill)
                canvas.restore()

                canvas.save()
                canvas.rotate(turret)
                fill.color = darken(palette.enemy, 0.62f)
                rect.set(-10f, -12f, 66f, 12f)
                canvas.drawRoundRect(rect, 5f, 5f, fill)
                if (kind == 2) {
                    rect.set(-10f, -26f, 52f, -14f)
                    canvas.drawRoundRect(rect, 5f, 5f, fill)
                    rect.set(-10f, 14f, 52f, 26f)
                    canvas.drawRoundRect(rect, 5f, 5f, fill)
                }
                canvas.restore()

                fill.color = blend(palette.enemyLight, Color.WHITE, flash)
                canvas.drawCircle(0f, 0f, if (kind == 1) 22f else 26f, fill)
                stroke.color = darken(palette.enemy, 0.5f)
                stroke.strokeWidth = 4f
                canvas.drawCircle(0f, 0f, if (kind == 1) 22f else 26f, stroke)

                drawHealthBar(canvas, 60f, 74f, health)
                canvas.restore()
            }
        }

        private fun drawHealthBar(canvas: Canvas, offset: Float, halfWidth: Float, ratio: Float) {
            fill.color = Color.argb(190, 10, 14, 20)
            rect.set(-halfWidth, offset, halfWidth, offset + 11f)
            canvas.drawRoundRect(rect, 5f, 5f, fill)
            fill.color = if (ratio > 0.4f) Color.rgb(96, 208, 128) else Color.rgb(224, 96, 88)
            rect.set(-halfWidth, offset, -halfWidth + halfWidth * 2f * ratio.coerceIn(0f, 1f), offset + 11f)
            canvas.drawRoundRect(rect, 5f, 5f, fill)
        }

        private fun drawBullets(canvas: Canvas) {
            val count = state[26].roundToInt().coerceIn(0, maxBullets)
            val useGlow = palette.bloom && quality >= 2

            if (useGlow) {
                glow.maskFilter = BlurMaskFilter(22f, BlurMaskFilter.Blur.NORMAL)
            }

            for (i in 0 until count) {
                val base = bulletBase + i * bulletFloats
                val x = state[base]
                val y = state[base + 1]
                if (!visible(x, y, 90f)) continue

                val radius = state[base + 2]
                val owner = state[base + 3].roundToInt()
                val life = state[base + 4].coerceIn(0f, 1f)
                val color = if (owner == 0) palette.playerBullet else palette.enemyBullet

                if (useGlow) {
                    glow.color = Color.argb((110 * life).roundToInt().coerceIn(0, 110),
                        Color.red(color), Color.green(color), Color.blue(color))
                    canvas.drawCircle(x, y, radius * 2.1f, glow)
                }

                fill.color = darken(color, 0.55f)
                canvas.drawCircle(x, y, radius * 1.45f, fill)
                fill.color = color
                canvas.drawCircle(x, y, radius, fill)
            }

            if (useGlow) glow.maskFilter = null
        }

        private fun drawParticles(canvas: Canvas) {
            val count = state[29].roundToInt().coerceIn(0, maxParticles)
            for (i in 0 until count) {
                val base = particleBase + i * particleFloats
                val x = state[base]
                val y = state[base + 1]
                if (!visible(x, y, 80f)) continue

                val life = state[base + 2].coerceIn(0f, 1f)
                val size = state[base + 3]
                val color = when (state[base + 4].roundToInt()) {
                    1 -> palette.accent
                    2 -> palette.enemy
                    3 -> palette.playerBullet
                    4 -> Color.rgb(126, 226, 160)
                    5 -> Color.rgb(120, 200, 255)
                    6 -> palette.square
                    else -> Color.rgb(238, 156, 96)
                }
                fill.color = Color.argb((life * 210f).roundToInt().coerceIn(0, 210),
                    Color.red(color), Color.green(color), Color.blue(color))
                canvas.drawCircle(x, y, size * (0.4f + life * 0.9f), fill)
            }
        }

        private fun drawPlayer(canvas: Canvas) {
            val x = state[0]
            val y = state[1]
            val heading = state[2]
            val turret = state[3]
            val recoil = state[30]
            val barrels = state[37].roundToInt().coerceAtLeast(1)

            canvas.save()
            canvas.translate(x, y)

            if (shadows) {
                fill.shader = null
                fill.color = Color.argb(110, 0, 0, 0)
                canvas.save()
                canvas.rotate(heading)
                canvas.translate(6f, 14f)
                rect.set(-58f, -46f, 58f, 46f)
                canvas.drawRoundRect(rect, 16f, 16f, fill)
                canvas.restore()
            }

            canvas.save()
            canvas.rotate(heading)

            // Treads with an animated pattern so movement reads at a glance.
            fill.color = palette.tread
            rect.set(-56f, -54f, 56f, -36f)
            canvas.drawRoundRect(rect, 8f, 8f, fill)
            rect.set(-56f, 36f, 56f, 54f)
            canvas.drawRoundRect(rect, 8f, 8f, fill)

            fill.color = darken(palette.tread, 0.65f)
            val offset = (ambient * state[4] * 0.05f) % 16f
            var tread = -56f + offset
            while (tread < 56f) {
                rect.set(tread, -54f, tread + 5f, -36f)
                canvas.drawRect(rect, fill)
                rect.set(tread, 36f, tread + 5f, 54f)
                canvas.drawRect(rect, fill)
                tread += 16f
            }

            fill.shader = bodyShader
            rect.set(-52f, -40f, 52f, 40f)
            canvas.drawRoundRect(rect, 14f, 14f, fill)
            fill.shader = null
            stroke.color = darken(palette.hull, 0.55f)
            stroke.strokeWidth = 5f
            canvas.drawRoundRect(rect, 14f, 14f, stroke)
            canvas.restore()

            // Turret and barrels.
            canvas.save()
            canvas.rotate(turret)
            val kick = -recoil * 12f
            fill.color = darken(palette.turret, 0.7f)
            if (barrels == 1) {
                rect.set(-12f + kick, -15f, 96f + kick, 15f)
                canvas.drawRoundRect(rect, 6f, 6f, fill)
            } else {
                rect.set(-12f + kick, -30f, 88f + kick, -6f)
                canvas.drawRoundRect(rect, 6f, 6f, fill)
                rect.set(-12f + kick, 6f, 88f + kick, 30f)
                canvas.drawRoundRect(rect, 6f, 6f, fill)
            }

            val flash = state[31]
            if (flash > 0.02f) {
                fill.color = Color.argb((flash * 220f).roundToInt().coerceIn(0, 220), 255, 236, 176)
                canvas.drawCircle(100f + kick, 0f, 16f * flash + 6f, fill)
            }

            fill.shader = turretShader
            canvas.drawCircle(0f, 0f, 34f, fill)
            fill.shader = null
            stroke.color = darken(palette.hull, 0.5f)
            stroke.strokeWidth = 5f
            canvas.drawCircle(0f, 0f, 34f, stroke)
            canvas.restore()

            // Ability auras.
            if (state[14] > 0.01f) {
                stroke.shader = shieldShader
                stroke.strokeWidth = 7f + state[14] * 6f
                canvas.save()
                canvas.rotate(ambient * 90f)
                canvas.drawCircle(0f, 0f, 86f, stroke)
                canvas.restore()
                stroke.shader = null
            }
            if (state[15] > 0.01f) {
                stroke.color = Color.argb((state[15] * 170f).roundToInt().coerceIn(0, 170), 255, 196, 96)
                stroke.strokeWidth = 5f
                canvas.drawCircle(0f, 0f, 74f + sin(ambient * 9f) * 5f, stroke)
            }
            if (state[16] > 0.01f) {
                stroke.color = Color.argb((state[16] * 130f).roundToInt().coerceIn(0, 130), 120, 200, 255)
                stroke.strokeWidth = 4f
                val sweep = (ambient * 320f) % 900f
                canvas.drawCircle(0f, 0f, 90f + sweep, stroke)
            }

            if (showBounds) {
                stroke.color = Color.argb(160, 255, 90, 90)
                stroke.strokeWidth = 3f
                canvas.drawCircle(0f, 0f, 46f, stroke)
            }
            canvas.restore()
        }

        private fun visible(x: Float, y: Float, margin: Float): Boolean {
            val scale = viewScale()
            val halfWide = width / scale * 0.5f + margin
            val halfHigh = height / scale * 0.5f + margin
            return abs(x - cameraX) < halfWide && abs(y - cameraY) < halfHigh
        }

        // ------------------------------------------------------------- hud

        private fun drawHud(canvas: Canvas) {
            val unit = min(width, height) * 0.01f
            fill.shader = null

            // Level and experience.
            fill.color = Color.argb(214, 6, 12, 19)
            rect.set(unit * 2f, unit * 2f, unit * 34f, unit * 12.5f)
            canvas.drawRoundRect(rect, unit, unit, fill)

            label.color = palette.accent
            label.textSize = unit * 3.4f
            canvas.drawText(
                "${getString(R.string.level)} ${state[9].roundToInt()}",
                unit * 4f, unit * 6.4f, label
            )
            label.color = Color.rgb(214, 226, 236)
            label.textSize = unit * 2.4f
            canvas.drawText(
                "${getString(R.string.score)} ${state[12].roundToInt()}   " +
                    "${getString(R.string.kills)} ${state[21].roundToInt()}   " +
                    "${getString(R.string.wave)} ${state[20].roundToInt()}",
                unit * 4f, unit * 10.6f, label
            )

            val xpRatio = if (state[11] > 0f) (state[10] / state[11]).coerceIn(0f, 1f) else 0f
            fill.color = Color.argb(180, 18, 26, 34)
            rect.set(unit * 2f, unit * 13f, unit * 34f, unit * 15f)
            canvas.drawRoundRect(rect, unit * 0.8f, unit * 0.8f, fill)
            fill.color = palette.accent
            rect.set(unit * 2f, unit * 13f, unit * 2f + unit * 32f * xpRatio, unit * 15f)
            canvas.drawRoundRect(rect, unit * 0.8f, unit * 0.8f, fill)

            if (state[25] > 0.5f) {
                label.color = Color.rgb(250, 214, 120)
                label.textSize = unit * 2.3f
                canvas.drawText(
                    "${getString(R.string.stat_points)}: ${state[25].roundToInt()}",
                    unit * 2f, unit * 18f, label
                )
            }

            // Health and energy.
            val barWidth = width * 0.34f
            val barLeft = width * 0.5f - barWidth * 0.5f
            val barTop = height - unit * 9f
            val healthRatio = if (state[6] > 0f) (state[5] / state[6]).coerceIn(0f, 1f) else 0f
            val energyRatio = if (state[8] > 0f) (state[7] / state[8]).coerceIn(0f, 1f) else 0f

            fill.color = Color.argb(196, 8, 14, 21)
            rect.set(barLeft - unit, barTop - unit, barLeft + barWidth + unit, barTop + unit * 6.4f)
            canvas.drawRoundRect(rect, unit, unit, fill)

            fill.color = Color.argb(190, 20, 28, 36)
            rect.set(barLeft, barTop, barLeft + barWidth, barTop + unit * 2.4f)
            canvas.drawRoundRect(rect, unit * 0.9f, unit * 0.9f, fill)
            fill.color = if (healthRatio > 0.35f) Color.rgb(92, 208, 126) else Color.rgb(226, 92, 84)
            rect.set(barLeft, barTop, barLeft + barWidth * healthRatio, barTop + unit * 2.4f)
            canvas.drawRoundRect(rect, unit * 0.9f, unit * 0.9f, fill)

            fill.color = Color.argb(190, 20, 28, 36)
            rect.set(barLeft, barTop + unit * 3.2f, barLeft + barWidth, barTop + unit * 5f)
            canvas.drawRoundRect(rect, unit * 0.7f, unit * 0.7f, fill)
            fill.color = Color.rgb(96, 170, 240)
            rect.set(barLeft, barTop + unit * 3.2f, barLeft + barWidth * energyRatio, barTop + unit * 5f)
            canvas.drawRoundRect(rect, unit * 0.7f, unit * 0.7f, fill)

            label.color = Color.WHITE
            label.textSize = unit * 2.1f
            canvas.drawText(
                "${state[5].roundToInt()} / ${state[6].roundToInt()}",
                barLeft, barTop - unit * 1.6f, label
            )

            drawMinimap(canvas, unit)
            drawPauseButton(canvas, unit)

            label.color = Color.argb(150, 220, 232, 242)
            label.textSize = unit * 1.9f
            canvas.drawText(
                "${getString(R.string.fps)} ${fps.roundToInt()}   " +
                    "${getString(R.string.enemies)} ${state[27].roundToInt()}",
                unit * 2f, height - unit * 1.6f, label
            )

            if (!running) {
                label.color = Color.argb(190, 226, 236, 244)
                label.textSize = unit * 2.4f
                canvas.drawText(getString(R.string.controls_hint), unit * 2f, height - unit * 4.4f, label)
            }
        }

        private fun drawMinimap(canvas: Canvas, unit: Float) {
            val size = unit * 20f
            val left = width - size - unit * 2f
            val top = unit * 15f
            val halfWidth = state[17]
            val halfHeight = state[18]
            if (halfWidth <= 0f || halfHeight <= 0f) return

            val mapHeight = size * (halfHeight / halfWidth)
            fill.color = Color.argb(180, 6, 12, 19)
            rect.set(left, top, left + size, top + mapHeight)
            canvas.drawRoundRect(rect, unit * 0.6f, unit * 0.6f, fill)
            stroke.color = Color.argb(120, Color.red(palette.accent), Color.green(palette.accent), Color.blue(palette.accent))
            stroke.strokeWidth = unit * 0.25f
            canvas.drawRoundRect(rect, unit * 0.6f, unit * 0.6f, stroke)

            val toX = { wx: Float -> left + (wx + halfWidth) / (halfWidth * 2f) * size }
            val toY = { wy: Float -> top + (wy + halfHeight) / (halfHeight * 2f) * mapHeight }

            if (quality >= 2) {
                fill.color = Color.argb(120, 200, 190, 130)
                val shapeCount = state[28].roundToInt().coerceIn(0, maxShapes)
                for (i in 0 until shapeCount step 2) {
                    val base = shapeBase + i * shapeFloats
                    canvas.drawCircle(toX(state[base]), toY(state[base + 1]), unit * 0.16f, fill)
                }
            }

            fill.color = palette.enemyLight
            val enemyCount = state[27].roundToInt().coerceIn(0, maxEnemies)
            for (i in 0 until enemyCount) {
                val base = enemyBase + i * enemyFloats
                canvas.drawCircle(toX(state[base]), toY(state[base + 1]), unit * 0.32f, fill)
            }

            fill.color = palette.accent
            canvas.drawCircle(toX(state[0]), toY(state[1]), unit * 0.5f, fill)
        }

        private fun drawPauseButton(canvas: Canvas, unit: Float) {
            val size = min(width, height) * 0.07f
            val cx = width - size
            val cy = size
            fill.color = Color.argb(190, 8, 14, 21)
            canvas.drawCircle(cx, cy, size * 0.62f, fill)
            fill.color = Color.rgb(226, 236, 244)
            rect.set(cx - size * 0.2f, cy - size * 0.24f, cx - size * 0.06f, cy + size * 0.24f)
            canvas.drawRect(rect, fill)
            rect.set(cx + size * 0.06f, cy - size * 0.24f, cx + size * 0.2f, cy + size * 0.24f)
            canvas.drawRect(rect, fill)
        }

        private fun drawControls(canvas: Canvas) {
            val radius = stickRadius()

            drawStick(canvas, movePointer >= 0, moveOriginX, moveOriginY, moveX, moveY, radius,
                width * 0.17f, height * 0.68f, getString(R.string.move))
            drawStick(canvas, aimPointer >= 0, aimOriginX, aimOriginY, aimX, aimY, radius,
                width * 0.83f, height * 0.68f, getString(R.string.aim))

            val center = FloatArray(2)
            val buttonSize = buttonRadius()
            for (index in 0 until 4) {
                abilityButtonCenter(index, center)
                val ready = cooldowns[index] >= 0.999f

                fill.color = Color.argb(if (ready) 210 else 140, 8, 16, 24)
                canvas.drawCircle(center[0], center[1], buttonSize, fill)

                stroke.color = if (ready) palette.accent else Color.argb(120, 150, 170, 186)
                stroke.strokeWidth = buttonSize * 0.1f
                canvas.drawCircle(center[0], center[1], buttonSize, stroke)

                if (!ready) {
                    stroke.color = palette.accent
                    rect.set(
                        center[0] - buttonSize, center[1] - buttonSize,
                        center[0] + buttonSize, center[1] + buttonSize
                    )
                    canvas.drawArc(rect, -90f, 360f * cooldowns[index], false, stroke)
                }

                drawAbilityGlyph(canvas, index, center[0], center[1], buttonSize * 0.5f, ready)
            }
        }

        private fun drawAbilityGlyph(canvas: Canvas, index: Int, cx: Float, cy: Float, size: Float, ready: Boolean) {
            val color = if (ready) palette.accent else Color.argb(130, 180, 195, 210)
            stroke.color = color
            stroke.strokeWidth = size * 0.28f
            fill.color = color

            when (index) {
                0 -> {
                    polygon.reset()
                    polygon.moveTo(cx, cy - size)
                    polygon.lineTo(cx + size * 0.8f, cy - size * 0.35f)
                    polygon.lineTo(cx, cy + size)
                    polygon.lineTo(cx - size * 0.8f, cy - size * 0.35f)
                    polygon.close()
                    canvas.drawPath(polygon, stroke)
                }
                1 -> {
                    for (i in -1..1 step 2) {
                        val offset = i * size * 0.42f
                        polygon.reset()
                        polygon.moveTo(cx + offset - size * 0.3f, cy - size * 0.6f)
                        polygon.lineTo(cx + offset + size * 0.3f, cy)
                        polygon.lineTo(cx + offset - size * 0.3f, cy + size * 0.6f)
                        canvas.drawPath(polygon, stroke)
                    }
                }
                2 -> {
                    rect.set(cx - size * 0.28f, cy - size * 0.9f, cx + size * 0.28f, cy + size * 0.9f)
                    canvas.drawRect(rect, fill)
                    rect.set(cx - size * 0.9f, cy - size * 0.28f, cx + size * 0.9f, cy + size * 0.28f)
                    canvas.drawRect(rect, fill)
                }
                else -> {
                    stroke.strokeWidth = size * 0.2f
                    canvas.drawCircle(cx, cy, size * 0.4f, stroke)
                    canvas.drawCircle(cx, cy, size * 0.85f, stroke)
                }
            }
        }

        private fun drawStick(
            canvas: Canvas,
            active: Boolean,
            originX: Float,
            originY: Float,
            valueX: Float,
            valueY: Float,
            radius: Float,
            restX: Float,
            restY: Float,
            caption: String
        ) {
            val cx = if (active) originX else restX
            val cy = if (active) originY else restY
            val alpha = if (active) 70 else 34

            fill.color = Color.argb(alpha, 210, 228, 240)
            canvas.drawCircle(cx, cy, radius, fill)
            stroke.color = Color.argb(alpha + 40, 220, 234, 244)
            stroke.strokeWidth = radius * 0.035f
            canvas.drawCircle(cx, cy, radius, stroke)

            val knobX = cx + valueX * radius * 0.72f
            val knobY = cy + valueY * radius * 0.72f
            fill.color = Color.argb(if (active) 200 else 90,
                Color.red(palette.accent), Color.green(palette.accent), Color.blue(palette.accent))
            canvas.drawCircle(knobX, knobY, radius * 0.31f, fill)

            if (!active) {
                label.color = Color.argb(120, 226, 238, 246)
                label.textSize = radius * 0.22f
                canvas.drawText(caption, cx - radius * 0.24f, cy + radius * 0.08f, label)
            }
        }
    }

    // ---------------------------------------------------------------- preview view

    /** Self-contained animated preview used by the garage and the ability screen. */
    inner class PreviewView(private val kind: Int) : View(this@Engine) {

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rect = RectF()
        private val path = Path()
        private var time = 0f
        private var lastNanos = SystemClock.elapsedRealtimeNanos()
        private var animating = true

        var abilityIndex = 0
            set(value) {
                field = value
                time = 0f
                invalidate()
            }

        var hullIndex = 0
            set(value) {
                field = value
                invalidate()
            }

        var shaderIndex = 0
            set(value) {
                field = value
                invalidate()
            }

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
            val delta = ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            lastNanos = now
            time += delta

            val colors = Palette.of(if (kind == PREVIEW_TANK) shaderIndex else shaderMode)

            fill.color = Color.argb(230, 8, 14, 22)
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, dp(10).toFloat(), dp(10).toFloat(), fill)

            stroke.color = Color.argb(70, Color.red(colors.accent), Color.green(colors.accent), Color.blue(colors.accent))
            stroke.strokeWidth = 2f
            canvas.drawRoundRect(rect, dp(10).toFloat(), dp(10).toFloat(), stroke)

            val cx = width * 0.5f
            val cy = height * 0.5f
            val scale = min(width, height) / 260f

            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(scale, scale)

            if (kind == PREVIEW_ABILITY) drawAbilityStage(canvas, colors)
            drawTank(canvas, colors)
            if (kind == PREVIEW_ABILITY) drawAbilityOverlay(canvas, colors)

            canvas.restore()

            if (animating) postInvalidateOnAnimation()
        }

        private fun drawTank(canvas: Canvas, colors: Palette) {
            val barrels = if (hullIndex == 2) 2 else 1
            val bob = sin(time * 1.6f) * 3f
            val turret = sin(time * 0.8f) * 22f

            canvas.save()
            canvas.translate(0f, bob)

            fill.color = colors.tread
            rect.set(-56f, -54f, 56f, -36f)
            canvas.drawRoundRect(rect, 8f, 8f, fill)
            rect.set(-56f, 36f, 56f, 54f)
            canvas.drawRoundRect(rect, 8f, 8f, fill)

            fill.color = darken(colors.tread, 0.65f)
            var tread = -56f + (time * 26f) % 16f
            while (tread < 56f) {
                canvas.drawRect(tread, -54f, tread + 5f, -36f, fill)
                canvas.drawRect(tread, 36f, tread + 5f, 54f, fill)
                tread += 16f
            }

            fill.shader = LinearGradient(0f, -40f, 0f, 40f, colors.hullLight, colors.hull, Shader.TileMode.CLAMP)
            rect.set(if (hullIndex == 1) -46f else -52f, -40f, if (hullIndex == 1) 46f else 52f, 40f)
            canvas.drawRoundRect(rect, 14f, 14f, fill)
            fill.shader = null
            stroke.color = darken(colors.hull, 0.55f)
            stroke.strokeWidth = 5f
            canvas.drawRoundRect(rect, 14f, 14f, stroke)

            canvas.save()
            canvas.rotate(turret)
            fill.color = darken(colors.turret, 0.7f)
            if (barrels == 1) {
                rect.set(-12f, -15f, 96f, 15f)
                canvas.drawRoundRect(rect, 6f, 6f, fill)
            } else {
                rect.set(-12f, -30f, 88f, -6f)
                canvas.drawRoundRect(rect, 6f, 6f, fill)
                rect.set(-12f, 6f, 88f, 30f)
                canvas.drawRoundRect(rect, 6f, 6f, fill)
            }
            fill.color = colors.turret
            canvas.drawCircle(0f, 0f, 34f, fill)
            stroke.color = darken(colors.hull, 0.5f)
            canvas.drawCircle(0f, 0f, 34f, stroke)
            canvas.restore()
            canvas.restore()
        }

        private fun drawAbilityStage(canvas: Canvas, colors: Palette) {
            // Two sparring targets so the effect has something to act on.
            val sweep = sin(time * 0.9f) * 40f
            for (side in intArrayOf(-1, 1)) {
                fill.color = darken(colors.enemy, 0.75f)
                rect.set(side * 150f - 26f + sweep, -24f, side * 150f + 26f + sweep, 24f)
                canvas.drawRoundRect(rect, 8f, 8f, fill)
                fill.color = colors.enemyLight
                canvas.drawCircle(side * 150f + sweep, 0f, 14f, fill)
            }
        }

        private fun drawAbilityOverlay(canvas: Canvas, colors: Palette) {
            val cycle = (time % 3f) / 3f

            when (abilityIndex) {
                0 -> {
                    val radius = 78f + sin(time * 4f) * 5f
                    stroke.shader = SweepGradient(
                        0f, 0f,
                        intArrayOf(colors.accent, Color.TRANSPARENT, colors.accent, Color.TRANSPARENT, colors.accent),
                        null
                    )
                    stroke.strokeWidth = 9f
                    canvas.save()
                    canvas.rotate(time * 110f)
                    canvas.drawCircle(0f, 0f, radius, stroke)
                    canvas.restore()
                    stroke.shader = null

                    fill.color = Color.argb(46, Color.red(colors.accent), Color.green(colors.accent), Color.blue(colors.accent))
                    canvas.drawCircle(0f, 0f, radius, fill)
                }

                1 -> {
                    for (i in 0 until 8) {
                        val progress = ((cycle + i / 8f) % 1f)
                        val alpha = ((1f - progress) * 200f).roundToInt().coerceIn(0, 200)
                        fill.color = Color.argb(alpha, 255, 198, 96)
                        val trail = -70f - progress * 120f
                        canvas.drawCircle(trail, sin(i.toFloat()) * 22f, 9f * (1f - progress) + 3f, fill)
                    }
                    stroke.color = Color.argb(190, 255, 206, 118)
                    stroke.strokeWidth = 5f
                    canvas.drawCircle(0f, 0f, 66f + sin(time * 10f) * 4f, stroke)
                }

                2 -> {
                    for (i in 0 until 6) {
                        val progress = ((cycle + i / 6f) % 1f)
                        val alpha = ((1f - progress) * 220f).roundToInt().coerceIn(0, 220)
                        fill.color = Color.argb(alpha, 128, 232, 156)
                        val rise = 60f - progress * 130f
                        val offset = sin((i + time) * 1.4f) * 44f
                        path.reset()
                        path.moveTo(offset - 9f, rise)
                        path.lineTo(offset + 9f, rise)
                        path.lineTo(offset + 9f, rise - 26f)
                        path.lineTo(offset + 22f, rise - 26f)
                        path.lineTo(offset, rise - 46f)
                        path.lineTo(offset - 22f, rise - 26f)
                        path.lineTo(offset - 9f, rise - 26f)
                        path.close()
                        canvas.drawPath(path, fill)
                    }
                }

                else -> {
                    for (i in 0 until 3) {
                        val progress = ((cycle + i / 3f) % 1f)
                        val alpha = ((1f - progress) * 170f).roundToInt().coerceIn(0, 170)
                        stroke.color = Color.argb(alpha, 122, 200, 255)
                        stroke.strokeWidth = 5f
                        canvas.drawCircle(0f, 0f, 40f + progress * 170f, stroke)
                    }
                    stroke.color = Color.argb(190, 122, 200, 255)
                    canvas.save()
                    canvas.rotate(time * 190f)
                    canvas.drawLine(0f, 0f, 150f, 0f, stroke)
                    canvas.restore()
                }
            }
        }
    }

    /** Thin horizontal meter used inside the menus. */
    inner class BarView(private val ratio: Float) : View(this@Engine) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bounds = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = height * 0.5f
            paint.color = Color.argb(180, 22, 32, 42)
            bounds.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(bounds, radius, radius, paint)

            paint.color = currentPalette().accent
            bounds.set(0f, 0f, width * ratio.coerceIn(0f, 1f), height.toFloat())
            canvas.drawRoundRect(bounds, radius, radius, paint)
        }
    }

    /** Ignores the selection callback the spinner fires while it is being populated. */
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

private const val PREVIEW_TANK = 0
private const val PREVIEW_ABILITY = 1

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
