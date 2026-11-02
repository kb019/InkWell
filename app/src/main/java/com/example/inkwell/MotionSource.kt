package com.example.inkwell

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Turns the accelerometer into tilt (which way is downhill on the screen) and shakes.
 * Register only while the wallpaper is visible so it costs nothing in the background.
 */
class MotionSource(context: Context, private val sim: InkSim) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var gx = 0f
    private var gy = 9.81f
    private var gz = 0f
    private var primed = false
    private var lastShake = 0L

    fun start() {
        primed = false
        accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sm.unregisterListener(this)
        sim.setTilt(0f, 0f)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]
        val y = e.values[1]
        val z = e.values[2]
        if (!primed) {
            gx = x; gy = y; gz = z; primed = true
        }
        // Low-pass for gravity; what is left over is the shake.
        gx += (x - gx) * .12f; gy += (y - gy) * .12f; gz += (z - gz) * .12f
        val lx = x - gx
        val ly = y - gy
        val lz = z - gz
        val linear = sqrt(lx * lx + ly * ly + lz * lz)

        // Downhill in screen axes (x right, y up). Upright is neutral, lying flat has no in-plane slope.
        val g = 9.81f
        val flat = (1f - abs(gz) / g * 1.2f).coerceIn(0f, 1f)
        val tx = (-gx / g * flat).coerceIn(-1.2f, 1.2f)
        val ty = ((-gy / g + 1f) * flat).coerceIn(-1.2f, 1.2f)
        sim.setTilt(tx, ty)

        val t = e.timestamp / 1_000_000
        if (linear > 11f && t - lastShake > 900) {
            lastShake = t
            val strength = ((linear - 11f) / 15f + .4f).coerceIn(.4f, 1f)
            sim.post { sim.shake(strength) }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
