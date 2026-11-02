package com.kodalibhargav.inkwell

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder

class InkWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = InkEngine()

    private inner class InkEngine : Engine() {
        private lateinit var sim: InkSim
        private var thread: EglThread? = null
        private var visible = false

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            sim = InkSim(InkSettings.get(applicationContext))
            setTouchEventsEnabled(true)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            thread = EglThread(holder, sim, supportsEs3(applicationContext)).also {
                it.setPaused(!visible)
                it.start()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            thread?.setSize(width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            thread?.finish()
            thread = null
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            thread?.setPaused(!visible)
            if (visible) sim.post { sim.sampleDrop() } // a fresh drop each time the screen wakes
        }

        override fun onTouchEvent(event: MotionEvent) {
            val x = event.x
            val y = event.y
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> sim.post { sim.down(x, y) }
                MotionEvent.ACTION_MOVE -> sim.post { sim.move(x, y) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> sim.post { sim.up() }
            }
        }

        override fun onDestroy() {
            thread?.finish()
            thread = null
            super.onDestroy()
        }
    }
}

/** Owns the EGL context for one wallpaper surface and drives the simulation at vsync. */
private class EglThread(private val holder: SurfaceHolder, private val sim: InkSim, private val es3: Boolean) : Thread("ink-gl") {
    private val lock = Object()
    private var paused = false
    private var quit = false
    private var dirty = false
    private var w = 0
    private var h = 0

    fun setSize(w: Int, h: Int) = synchronized(lock) {
        this.w = w; this.h = h; dirty = true; lock.notifyAll()
    }

    fun setPaused(p: Boolean) = synchronized(lock) {
        paused = p; lock.notifyAll()
    }

    fun finish() {
        synchronized(lock) { quit = true; lock.notifyAll() }
        join()
    }

    override fun run() {
        var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
        var surf: EGLSurface = EGL14.EGL_NO_SURFACE
        try {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val v = IntArray(2)
            EGL14.eglInitialize(dpy, v, 0, v, 1)
            val attrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, if (es3) 0x40 else EGL14.EGL_OPENGL_ES2_BIT, // 0x40 = EGL_OPENGL_ES3_BIT_KHR
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 0, EGL14.EGL_DEPTH_SIZE, 0, EGL14.EGL_NONE,
            )
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            EGL14.eglChooseConfig(dpy, attrs, 0, cfgs, 0, 1, n, 0)
            val cfg = cfgs[0] ?: return
            ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, if (es3) 3 else 2, EGL14.EGL_NONE), 0)
            surf = EGL14.eglCreateWindowSurface(dpy, cfg, holder.surface, intArrayOf(EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
            EGL14.eglSwapInterval(dpy, 1)
            sim.create()

            while (true) {
                var rw = 0
                var rh = 0
                var resize = false
                synchronized(lock) {
                    while ((paused || w == 0) && !quit) lock.wait()
                    if (quit) return@synchronized
                    if (dirty) {
                        rw = w; rh = h; dirty = false; resize = true
                    }
                }
                if (quit) break
                if (resize) sim.resize(rw, rh)
                // Settled liquid moves slowly; half the frame rate saves battery.
                if (sim.idle()) sleep(14)
                sim.frame()
                EGL14.eglSwapBuffers(dpy, surf)
            }
        } catch (_: InterruptedException) {
        } finally {
            try {
                sim.destroy()
            } catch (_: Exception) {
            }
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf)
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                EGL14.eglTerminate(dpy)
            }
        }
    }
}
