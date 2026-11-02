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
    InkStyle("blush", "Blush", 0, false, "#f6eeee", "#ead9da",
        listOf("Wine" to "#7a2e46", "Plum" to "#4e2f5a", "Coral" to "#b5543f"),
        .5f, 16f, .88f, 1f, .45f, true, true, true),
    InkStyle("ember", "Ember", 1, true, "#1a0d09", "#080302",
        listOf("Flame" to "#ff7a3d", "Amber" to "#ffbf5e", "Crimson" to "#ff4d5a"),
        -.2f, 20f, .84f, 1f, .42f, true, true, false),
    InkStyle("orchid", "Orchid", 1, true, "#150b20", "#05020a",
        listOf("Magenta" to "#e86be0", "Violet" to "#9a7bff", "Pink" to "#ff9ad5"),
        .2f, 22f, .84f, 1.05f, .42f, true, true, true),
    InkStyle("moonstone", "Moonstone", 2, true, "#2a3138", "#0f1316",
        listOf("Pearl" to "#e9edf0", "Silver" to "#aab4bd", "Ice" to "#bfe3f2"),
        .6f, 14f, .94f, 1.3f, .4f, true, false, false),
)

/**
 * How a drop of ink behaves and what it draws. Swirl is the original; the others
 * damp the vortices so the shape stays readable instead of curling up.
 */
class InkForm(val name: String, val curlMul: Float, val weightMul: Float)

val FORMS = listOf(
    InkForm("Swirl", 1f, 1f),
    InkForm("Bloom", .15f, .45f),
    InkForm("Ring", .12f, .6f),
    InkForm("Burst", .08f, .3f),
    InkForm("Thread", .1f, 1f),
    InkForm("Mist", .45f, .3f),
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

    var styleIdx: Int
        get() = sp.getInt("style", 0).coerceIn(0, STYLES.lastIndex)
        set(v) {
            val i = (v + STYLES.size) % STYLES.size
            if (i != styleIdx) sp.edit().putInt("style", i).putInt("ink", 0).apply()
        }

    var inkIdx: Int
        get() = sp.getInt("ink", 0).coerceIn(0, STYLES[styleIdx].inks.lastIndex)
        set(v) = sp.edit().putInt("ink", v).apply()

    /** Index into [FORMS]; shared by every style. */
    var form: Int
        get() = sp.getInt("form", 0).coerceIn(0, FORMS.lastIndex)
        set(v) = sp.edit().putInt("form", v).apply()

    var rain: Boolean
        get() = sp.getBoolean("rain", true)
        set(v) = sp.edit().putBoolean("rain", v).apply()

    fun look(): Look {
        val s = STYLES[styleIdx]
        fun f(k: String, d: Float) = sp.getFloat("t_${s.id}_$k", d)
        fun b(k: String, d: Boolean) = sp.getFloat("t_${s.id}_$k", if (d) 1f else 0f) > .5f
        return Look(
            s, f("weight", s.weight), f("curl", s.curl), f("linger", s.linger), f("size", s.size), f("speed", s.speed),
            b("shade", s.shade), b("glow", s.glow), b("caustic", s.caustic),
        )
    }

    fun setTweak(key: String, v: Float) {
        sp.edit().putFloat("t_${STYLES[styleIdx].id}_$key", v).apply()
    }

    fun resetTweaks() {
        val prefix = "t_${STYLES[styleIdx].id}_"
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
