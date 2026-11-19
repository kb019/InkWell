package com.kodalibhargav.inkwell

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.delay
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : ComponentActivity() {
    private var glView: InkGlView? = null
    private var motion: MotionSource? = null
    private var pendingSim: InkSim? = null

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val sim = pendingSim
            if (granted && sim != null) {
                capture(sim)
            } else {
                toast("Allow storage access to save images")
            }
        }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    /** Saves the ink (without the controls) as a PNG in Pictures/Inkwell. */
    private fun saveImage(sim: InkSim) {
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSim = sim
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        capture(sim)
    }

    /**
     * Opens the user's email app with a message to the support address. The version and device are written
     * into the body where the user can read, edit or delete them before sending. The app itself sends nothing,
     * so it still needs no network permission.
     */
    private fun sendFeedback() {
        val version =
            try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: ""
            } catch (_: Exception) {
                ""
            }
        val body = "\n\n\n---\nApp: Inkwell $version\nAndroid: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nDevice: ${Build.MANUFACTURER} ${Build.MODEL}\n"
        val intent =
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                .putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
                .putExtra(Intent.EXTRA_SUBJECT, "Inkwell feedback")
                .putExtra(Intent.EXTRA_TEXT, body)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast("No email app found. You can write to $SUPPORT_EMAIL")
        }
    }

    /** Opens this app's Play Store page so the user can leave a rating. No library or network permission needed. */
    private fun rateApp() {
        val id = packageName
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id")))
            } catch (_: Exception) {
                toast("No app can open the store page")
            }
        }
    }

    /** Rating links to Google Play, so hide it for installs that came from another store such as F-Droid. */
    private fun canRate(): Boolean {
        val installer =
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    packageManager.getInstallSourceInfo(packageName).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getInstallerPackageName(packageName)
                }
            } catch (_: Exception) {
                null
            }
        return installer == null || installer == "com.android.vending"
    }

    private fun capture(sim: InkSim) {
        sim.capture { bmp ->
            Thread {
                val ok = bmp != null && InkCapture.save(this, bmp)
                toast(if (ok) "Saved to gallery" else "Couldn't save the image")
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val settings = InkSettings.get(this)
        settings.setSystemNight(isNightMode(this))
        setContent {
            Studio(
                settings,
                onGlView = { glView = it },
                onSim = { sim ->
                    motion =
                        MotionSource(this, sim, settings).also { m ->
                            settings.listeners.add { m.update() }
                            m.setVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                        }
                },
                onSetWallpaper = { setWallpaper(this) },
                onSave = ::saveImage,
                onRate = ::rateApp,
                showRate = canRate(),
                onFeedback = ::sendFeedback,
            )
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
        motion?.setVisible(true)
    }

    override fun onPause() {
        motion?.setVisible(false)
        glView?.onPause()
        super.onPause()
    }
}

private fun setWallpaper(context: Context) {
    val component = ComponentName(context, InkWallpaperService::class.java)
    val direct =
        Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
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
class InkGlView(
    context: Context,
    private val sim: InkSim,
) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(if (supportsEs3(context)) 3 else 2)
        setEGLConfigChooser(8, 8, 8, 0, 0, 0)
        setRenderer(
            object : Renderer {
                override fun onSurfaceCreated(
                    gl: GL10?,
                    config: EGLConfig?,
                ) = sim.create()

                override fun onSurfaceChanged(
                    gl: GL10?,
                    width: Int,
                    height: Int,
                ) = sim.resize(width, height)

                override fun onDrawFrame(gl: GL10?) = sim.frame()
            },
        )
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

private val TUNE =
    listOf(
        Triple("weight", "Weight", -1f..1f),
        Triple("curl", "Swirl", 0f..40f),
        Triple("linger", "Linger", 0f..1f),
        Triple("size", "Drop size", .4f..2f),
        Triple("speed", "Flow", .15f..1f),
        Triple("strength", "Ink strength", .4f..1.6f),
        Triple("bright", "Liquid brightness", -1f..1f),
        Triple("tint", "Liquid tint", -1f..1f),
        Triple("rainRate", "Rain rate", .3f..3f),
    )

private fun fmt(
    key: String,
    v: Float,
) = when (key) {
    "weight" ->
        if (v < -.05f) {
            "Rises"
        } else if (v > .05f) {
            "Sinks"
        } else {
            "Floats"
        }
    "curl" -> "${(v / 40 * 100).toInt()}"
    "size" -> "%.2f×".format(v)
    "strength" -> "${(v * 100).toInt()}%"
    "bright" -> "%+d".format((v * 100).toInt())
    "tint" -> "%+d°".format((v * 60).toInt())
    "rainRate" -> "%.1f×".format(v)
    else -> "${(v * 100).toInt()}"
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Studio(
    settings: InkSettings,
    onGlView: (InkGlView) -> Unit,
    onSim: (InkSim) -> Unit,
    onSetWallpaper: () -> Unit,
    onSave: (InkSim) -> Unit,
    onRate: () -> Unit,
    showRate: Boolean,
    onFeedback: () -> Unit,
) {
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(settings) {
        val l = {
            tick++
            Unit
        }
        settings.listeners.add(l)
        onDispose { settings.listeners.remove(l) }
    }
    // The tick is the key, so reading it here subscribes this composable to settings changes.
    // A bare, unused read gets removed by R8 in release builds and the UI stops updating.
    val look = remember(tick) { settings.look() }
    val s = look.s
    val sim = remember { InkSim(settings).also(onSim) }
    var tuneOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var legal by remember { mutableStateOf<Pair<String, String>?>(null) }
    val night = isSystemInDarkTheme()
    SideEffect { settings.setSystemNight(night) }
    val ctx = LocalContext.current
    val versionName =
        remember {
            try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
            } catch (_: Exception) {
                ""
            }
        }
    var unsupported by remember { mutableStateOf(false) }
    LaunchedEffect(sim) {
        while (true) {
            unsupported = sim.unsupported
            delay(700)
        }
    }

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

        if (unsupported) {
            Text(
                "This device can't draw the ink.\nIt needs OpenGL ES with floating-point textures.",
                color = fg,
                fontSize = 15.sp,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
            )
        }

        Text(
            "Live preview — tap to drop, drag to stir",
            color = fg2,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).padding(20.dp).align(Alignment.TopStart),
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, veil)))
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val pool = settings.stylePool()
                    Text("%02d / %02d".format(pool.indexOf(STYLES.indexOf(s)) + 1, pool.size), color = fg2, fontSize = 11.sp, letterSpacing = 1.4.sp)
                    Text(s.name, color = fg, fontSize = 24.sp, fontWeight = FontWeight.Light)
                }
                TextButton(onClick = { settings.stepStyle(-1) }) { Text("‹", color = fg, fontSize = 26.sp) }
                TextButton(onClick = { settings.stepStyle(1) }) { Text("›", color = fg, fontSize = 26.sp) }
            }

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FORMS.forEachIndexed { i, f ->
                    Pill(f.name, settings.form == i, fg, fg2) {
                        settings.form = i
                        sim.post { sim.sampleDrop() }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                s.inks.forEachIndexed { i, (_, hex) ->
                    val c =
                        if (s.prism) {
                            Brush.sweepGradient(listOf(Color(0xFF7FE3C0), Color(0xFF5FC7E8), Color(0xFF8E8CF0), Color(0xFFC58CE8), Color(0xFF7FE3C0)))
                        } else {
                            Brush.linearGradient(listOf(Color(android.graphics.Color.parseColor(hex)), Color(android.graphics.Color.parseColor(hex))))
                        }
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .clickable {
                                settings.inkIdx = i
                                sim.post { sim.sampleDrop() }
                            }.padding(9.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(0.dp, Color.Transparent, CircleShape),
                    ) {
                        if (i == settings.inkIdx) Box(Modifier.size(34.dp).border(BorderStroke(1.dp, fg), CircleShape))
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Pill("Rain", settings.rain, fg, fg2) { settings.rain = !settings.rain }
                Pill("Clear", false, fg, fg2) { sim.post { sim.clear() } }
                Pill("Save", false, fg, fg2) { onSave(sim) }
                Pill("Tune", tuneOpen, fg, fg2) {
                    tuneOpen = !tuneOpen
                    if (tuneOpen) settingsOpen = false
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("MOTION", color = fg2, fontSize = 11.sp, letterSpacing = 1.4.sp, modifier = Modifier.padding(end = 6.dp))
                    Pill("Tilt", settings.tilt, fg, fg2) { settings.tilt = !settings.tilt }
                    Pill("Shake", settings.shake, fg, fg2) { settings.shake = !settings.shake }
                }
                Pill("Settings", settingsOpen, fg, fg2) {
                    settingsOpen = !settingsOpen
                    if (settingsOpen) tuneOpen = false
                }
            }

            if (tuneOpen) {
                Column(
                    Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for ((key, label, range) in TUNE) {
                        val v =
                            when (key) {
                                "weight" -> look.weight
                                "curl" -> look.curl
                                "linger" -> look.linger
                                "size" -> look.size
                                "strength" -> look.strength
                                "bright" -> look.bright
                                "tint" -> look.tint
                                "rainRate" -> look.rainRate
                                else -> look.speed
                            }
                        TuneSlider(label, fmt(key, v), v, range, fg, fg2) { settings.setTweak(key, it) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Pill("Light", look.shade, fg, fg2) { settings.setTweak("shade", if (look.shade) 0f else 1f) }
                        Pill("Glow", look.glow, fg, fg2) { settings.setTweak("glow", if (look.glow) 0f else 1f) }
                        Pill("Caustics", look.caustic, fg, fg2) { settings.setTweak("caustic", if (look.caustic) 0f else 1f) }
                        Pill("Reset", false, fg, fg2) { settings.resetTweaks() }
                    }
                }
            }

            if (settingsOpen) {
                Column(
                    Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("THEME", color = fg2, fontSize = 11.sp, letterSpacing = 1.4.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("All", "System", "Light", "Dark").forEachIndexed { i, name ->
                            Pill(name, settings.theme == i, fg, fg2) { settings.theme = i }
                        }
                    }
                    Text(
                        when (settings.theme) {
                            THEME_LIGHT -> "Only pale liquids."
                            THEME_DARK -> "Only dark liquids."
                            THEME_SYSTEM -> "Follows your phone's light or dark mode."
                            else -> "Shows every style."
                        },
                        color = fg2,
                        fontSize = 12.sp,
                    )
                    // Google Play installs go to the store page, where reviews and developer replies live.
                    // Other installs (F-Droid, downloaded APK) have no Play page, so they use email.
                    if (showRate) {
                        SettingsLink("Rate & give feedback", fg, onRate)
                    } else {
                        SettingsLink("Send feedback", fg, onFeedback)
                    }
                    SettingsLink("Privacy policy", fg) { legal = "Privacy policy" to PRIVACY_POLICY }
                    SettingsLink("Terms of service", fg) { legal = "Terms of service" to TERMS_OF_SERVICE }
                    Text("VERSION $versionName", color = fg2, fontSize = 11.sp, letterSpacing = 1.4.sp)
                }
            }

            Button(
                onClick = onSetWallpaper,
                enabled = !unsupported,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = fg, contentColor = if (s.dark) Color(0xFF0B0D10) else Color(0xFFF4F2EE)),
            ) { Text(if (unsupported) "Not supported on this device" else "Set as wallpaper", fontWeight = FontWeight.Light, fontSize = 15.sp, letterSpacing = .5.sp) }
        }
    }

    legal?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { legal = null },
            confirmButton = { TextButton(onClick = { legal = null }) { Text("Close") } },
            title = { Text(title) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(body, fontSize = 14.sp) } },
        )
    }
}

@Composable
private fun TuneSlider(
    label: String,
    valueText: String,
    v: Float,
    range: ClosedFloatingPointRange<Float>,
    fg: Color,
    fg2: Color,
    onChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label.uppercase(), color = fg, fontSize = 11.sp, letterSpacing = 1.4.sp)
        Text(valueText, color = fg2, fontSize = 11.sp, letterSpacing = 1.4.sp)
    }
    Slider(
        value = v,
        onValueChange = onChange,
        valueRange = range,
        colors = SliderDefaults.colors(thumbColor = fg, activeTrackColor = fg, inactiveTrackColor = fg.copy(alpha = .16f)),
    )
}

@Composable
private fun SettingsLink(
    label: String,
    fg: Color,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = fg,
        fontSize = 16.sp,
        fontWeight = FontWeight.Light,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
    )
}

@Composable
private fun Pill(
    label: String,
    on: Boolean,
    fg: Color,
    fg2: Color,
    onClick: () -> Unit,
) {
    Text(
        label.uppercase(),
        color = if (on) fg else fg2,
        fontSize = 11.sp,
        letterSpacing = 1.4.sp,
        modifier =
        Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, if (on) fg else fg.copy(alpha = .16f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
