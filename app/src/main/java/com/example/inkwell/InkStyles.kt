package com.example.inkwell

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArrayList

/**
 * mode 0: ink darkens a pale liquid · 1: ink glows in a dark liquid · 2: pale ink clouds a dark liquid
 */
data class InkStyle(
    val id: String,
    val name: String,
    val mode: Int,
    val dark: Boolean,
    val top: String,
    val bottom: String,
    val inks: List<Pair<String, String>>,
    val weight: Float,
    val curl: Float,
    val linger: Float,
    val size: Float,
    val speed: Float,
    val shade: Boolean,
    val glow: Boolean,
    val caustic: Boolean,
    val prism: Boolean = false,
)

val STYLES = listOf(
    InkStyle("porcelain", "Porcelain", 0, false, "#f5f3ef", "#e7e3dc",
        listOf("Prussian" to "#1f3354", "Slate" to "#4a6670", "Plum" to "#4e3a52", "Graphite" to "#2a2a2c"),
        .6f, 16f, .86f, 1f, .48f, true, true, true),
    InkStyle("sumi", "Sumi", 0, false, "#f2eee5", "#e4ddd0",
        listOf("Sumi" to "#111111", "Usuzumi" to "#6e6a63", "Shu" to "#9e3a2c"),
        .38f, 28f, .93f, .7f, .36f, true, false, false),
    InkStyle("tide", "Tide", 0, false, "#eef3f2", "#d9e4e3",
        listOf("Deep teal" to "#164e57", "Sea glass" to "#4f8a85", "Kelp" to "#3e5a3a", "Ink blue" to "#1b3a5e"),
        .55f, 20f, .86f, 1.05f, .45f, true, true, true),
    InkStyle("abyss", "Abyss", 1, true, "#0a1f28", "#02070a",
        listOf("Aqua" to "#5fe3d4", "Cerulean" to "#4c9dff", "Periwinkle" to "#8e86ff"),
        .3f, 22f, .82f, 1f, .42f, true, true, true),
    InkStyle("crema", "Crema", 2, true, "#3d281b", "#160c06",
        listOf("Cream" to "#efdfc4", "Milk" to "#f8f3ea", "Caramel" to "#c79463"),
        .8f, 12f, .95f, 1.35f, .4f, true, false, false),
    InkStyle("incense", "Incense", 1, true, "#19191c", "#0b0b0d",
        listOf("Ash" to "#cfccd2", "Sandal" to "#c9b18f", "Haze" to "#97a3b6"),
        -.55f, 32f, .78f, .8f, .48f, false, true, false),
    InkStyle("aurora", "Aurora", 1, true, "#0a0f24", "#03050d",
        listOf("Prism" to "#7fe3c0"),
        -.18f, 18f, .85f, 1.1f, .4f, true, true, false, prism = true),
)

/** A style with the user's tweaks applied. */
class Look(
    val s: InkStyle,
    val weight: Float,
    val curl: Float,
    val linger: Float,
    val size: Float,
    val speed: Float,
    val shade: Boolean,
    val glow: Boolean,
    val caustic: Boolean,
)

/** Shared by the wallpaper service and the settings preview; both run in the same process. */
class InkSettings private constructor(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("inkwell", Context.MODE_PRIVATE)

    @Volatile
    var version = 0
        private set

    val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val onChange = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        version++
        listeners.forEach { it() }
    }

    init {
        sp.registerOnSharedPreferenceChangeListener(onChange)
    }

    /** The manually chosen style; only used while [auto] is off. */
    var styleIdx: Int
        get() = sp.getInt("style", 0).coerceIn(0, STYLES.lastIndex)
        private set(v) {
            sp.edit().putInt("style", (v + STYLES.size) % STYLES.size).putInt("ink", 0).apply()
        }

    /** Let the time of day choose the style. */
    var auto: Boolean
        get() = sp.getBoolean("auto", true)
        set(v) = sp.edit().putBoolean("auto", v).apply()

    /** The style actually showing: by the clock when [auto] is on. */
    fun effectiveIdx(): Int {
        if (!auto) return styleIdx
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (h) {
            in 5..9 -> 0   // Porcelain: pale morning
            in 10..15 -> 2 // Tide: bright day
            in 16..18 -> 1 // Sumi: afternoon ink
            in 19..20 -> 4 // Crema: warm evening
            in 21..23 -> 3 // Abyss: night
            in 0..1 -> 6   // Aurora: small hours
            else -> 5      // Incense: before dawn
        }
    }

    /** Pick a style by hand, which switches [auto] off. */
    fun pickStyle(i: Int) {
        sp.edit().putBoolean("auto", false).putInt("style", (i + STYLES.size) % STYLES.size).putInt("ink", 0).apply()
    }

    var inkIdx: Int
        get() = sp.getInt("ink", 0).coerceIn(0, STYLES[effectiveIdx()].inks.lastIndex)
        set(v) = sp.edit().putInt("ink", v).apply()

    var glass: Boolean
        get() = sp.getBoolean("glass", true)
        set(v) = sp.edit().putBoolean("glass", v).apply()

    var rain: Boolean
        get() = sp.getBoolean("rain", true)
        set(v) = sp.edit().putBoolean("rain", v).apply()

    fun look(): Look {
        val s = STYLES[effectiveIdx()]
        fun f(k: String, d: Float) = sp.getFloat("t_${s.id}_$k", d)
        fun b(k: String, d: Boolean) = sp.getFloat("t_${s.id}_$k", if (d) 1f else 0f) > .5f
        return Look(
            s, f("weight", s.weight), f("curl", s.curl), f("linger", s.linger), f("size", s.size), f("speed", s.speed),
            b("shade", s.shade), b("glow", s.glow), b("caustic", s.caustic),
        )
    }

    fun setTweak(key: String, v: Float) {
        sp.edit().putFloat("t_${STYLES[effectiveIdx()].id}_$key", v).apply()
    }

    fun resetTweaks() {
        val prefix = "t_${STYLES[effectiveIdx()].id}_"
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith(prefix) }.forEach { e.remove(it) }
        e.apply()
    }

    companion object {
        @Volatile
        private var inst: InkSettings? = null
        fun get(context: Context): InkSettings =
            inst ?: synchronized(this) { inst ?: InkSettings(context).also { inst = it } }
    }
}
