package com.example.inkwell

import android.annotation.SuppressLint
import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : ComponentActivity() {
    private var glView: InkGlView? = null
    private var motion: MotionSource? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val settings = InkSettings.get(this)
        setContent {
            Studio(settings, onGlView = { glView = it }, onSim = { motion = MotionSource(this, it) }, onSetWallpaper = { setWallpaper(this) })
        }
    }

    /** Full-screen: bars stay hidden and only slide in, translucent, on an edge swipe. */
    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        glView?.onResume()
        motion?.start()
    }

    override fun onPause() {
        motion?.stop()
        glView?.onPause()
        super.onPause()
    }
}

private fun setWallpaper(context: Context) {
    val component = ComponentName(context, InkWallpaperService::class.java)
    val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
    try {
        context.startActivity(direct)
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        } catch (_: Exception) {
        }
    }
}

/** Live preview of the simulation; touches stir it exactly as they do on the home screen. */
@SuppressLint("ViewConstructor")
class InkGlView(context: Context, private val sim: InkSim) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 0, 0, 0)
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = sim.create()
            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) = sim.resize(width, height)
            override fun onDrawFrame(gl: GL10?) = sim.frame()
        })
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> sim.post { sim.down(x, y) }
            MotionEvent.ACTION_MOVE -> sim.post { sim.move(x, y) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> sim.post { sim.up() }
        }
        return true
    }
}

private val TUNE = listOf(
    Triple("weight", "Weight", -1f..1f),
    Triple("curl", "Swirl", 0f..40f),
    Triple("linger", "Linger", 0f..1f),
    Triple("size", "Drop size", .4f..2f),
    Triple("speed", "Flow", .15f..1f),
)

private fun fmt(key: String, v: Float) = when (key) {
    "weight" -> if (v < -.05f) "Rises" else if (v > .05f) "Sinks" else "Floats"
    "curl" -> "${(v / 40 * 100).toInt()}"
    "size" -> "%.2f×".format(v)
    else -> "${(v * 100).toInt()}"
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Studio(settings: InkSettings, onGlView: (InkGlView) -> Unit, onSim: (InkSim) -> Unit, onSetWallpaper: () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(settings) {
        val l = { tick++; Unit }
        settings.listeners.add(l)
        onDispose { settings.listeners.remove(l) }
    }
    @Suppress("UNUSED_EXPRESSION") tick
    val look = settings.look()
    val s = look.s
    val sim = remember { InkSim(settings).also(onSim) }
    var tuneOpen by remember { mutableStateOf(false) }

    val fg = if (s.dark) Color(0xFFF1EFE9) else Color(0xFF1D1C1A)
    val fg2 = fg.copy(alpha = .55f)
    val veil = if (s.dark) Color(0x80080A0C) else Color(0x9EF4F2EE)

    val activity = LocalContext.current as Activity
    SideEffect {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !s.dark
            isAppearanceLightNavigationBars = !s.dark
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { ctx -> InkGlView(ctx, sim).also(onGlView) }, modifier = Modifier.fillMaxSize())

        Text(
            "Live preview — tilt, shake, tap, drag",
            color = fg2, fontSize = 11.sp, letterSpacing = 1.sp,
            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).padding(20.dp).align(Alignment.TopStart),
        )

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, veil)))
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("%02d / %02d%s".format(settings.effectiveIdx() + 1, STYLES.size, if (settings.auto) "  ·  BY THE CLOCK" else ""), color = fg2, fontSize = 11.sp, letterSpacing = 1.4.sp)
                    Text(s.name, color = fg, fontSize = 24.sp, fontWeight = FontWeight.Light)
                }
                TextButton(onClick = { settings.pickStyle(settings.effectiveIdx() - 1) }) { Text("‹", color = fg, fontSize = 26.sp) }
                TextButton(onClick = { settings.pickStyle(settings.effectiveIdx() + 1) }) { Text("›", color = fg, fontSize = 26.sp) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                s.inks.forEachIndexed { i, (_, hex) ->
                    val c = if (s.prism) Brush.sweepGradient(listOf(Color(0xFF7FE3C0), Color(0xFF5FC7E8), Color(0xFF8E8CF0), Color(0xFFC58CE8), Color(0xFF7FE3C0)))
                    else Brush.linearGradient(listOf(Color(android.graphics.Color.parseColor(hex)), Color(android.graphics.Color.parseColor(hex))))
                    Box(
                        Modifier.size(34.dp).clip(CircleShape)
                            .clickable {
                                settings.inkIdx = i
                                sim.post { sim.sampleDrop() }
                            }
                            .padding(9.dp).clip(CircleShape).background(c)
                            .border(0.dp, Color.Transparent, CircleShape),
                    ) {
                        if (i == settings.inkIdx) Box(Modifier.size(34.dp).border(BorderStroke(1.dp, fg), CircleShape))
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Pill("Auto", settings.auto, fg, fg2) { settings.auto = !settings.auto }
                Pill("Rain", settings.rain, fg, fg2) { settings.rain = !settings.rain }
                Pill("Glass", settings.glass, fg, fg2) { settings.glass = !settings.glass }
                Pill("Clear", false, fg, fg2) { sim.post { sim.clear() } }
                Pill("Tune", tuneOpen, fg, fg2) { tuneOpen = !tuneOpen }
            }

            if (tuneOpen) {
                Column(
                    Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for ((key, label, range) in TUNE) {
                        val v = when (key) {
                            "weight" -> look.weight; "curl" -> look.curl; "linger" -> look.linger
                            "size" -> look.size; else -> look.speed
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label.uppercase(), color = fg, fontSize = 11.sp, letterSpacing = 1.4.sp)
                            Text(fmt(key, v), color = fg2, fontSize = 11.sp, letterSpacing = 1.4.sp)
                        }
                        Slider(
                            value = v, onValueChange = { settings.setTweak(key, it) }, valueRange = range,
                            colors = SliderDefaults.colors(
                                thumbColor = fg, activeTrackColor = fg, inactiveTrackColor = fg.copy(alpha = .16f),
                            ),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Pill("Light", look.shade, fg, fg2) { settings.setTweak("shade", if (look.shade) 0f else 1f) }
                        Pill("Glow", look.glow, fg, fg2) { settings.setTweak("glow", if (look.glow) 0f else 1f) }
                        Pill("Caustics", look.caustic, fg, fg2) { settings.setTweak("caustic", if (look.caustic) 0f else 1f) }
                        Pill("Reset", false, fg, fg2) { settings.resetTweaks() }
                    }
                }
            }

            Button(
                onClick = onSetWallpaper,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = fg, contentColor = if (s.dark) Color(0xFF0B0D10) else Color(0xFFF4F2EE)),
            ) { Text("Set as wallpaper", fontWeight = FontWeight.Light, fontSize = 15.sp, letterSpacing = .5.sp) }
        }
    }
}

@Composable
private fun Pill(label: String, on: Boolean, fg: Color, fg2: Color, onClick: () -> Unit) {
    Text(
        label.uppercase(), color = if (on) fg else fg2, fontSize = 11.sp, letterSpacing = 1.4.sp,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .border(1.dp, if (on) fg else fg.copy(alpha = .16f), RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
