package com.example.inkwell

import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Stable-fluids ink simulation with buoyancy, ported from the Inkwell Glass web prototype.
 * The dye texture holds colour in rgb and density in alpha, so heavy ink pulls the liquid down
 * (or light smoke lifts it) and rolls into plumes.
 *
 * Everything except [post] must be called on the GL thread.
 */
class InkSim(private val settings: InkSettings) {

    // ---------------------------------------------------------------- shaders
    private companion object {
        const val VS = """precision highp float;attribute vec2 aPosition;varying vec2 vUv,vL,vR,vT,vB;uniform vec2 texelSize;
void main(){vUv=aPosition*.5+.5;vL=vUv-vec2(texelSize.x,0.);vR=vUv+vec2(texelSize.x,0.);vT=vUv+vec2(0.,texelSize.y);vB=vUv-vec2(0.,texelSize.y);gl_Position=vec4(aPosition,0.,1.);}"""
        const val H = "precision highp float;precision highp sampler2D;varying vec2 vUv,vL,vR,vT,vB;"

        val FS = mapOf(
            "splat" to H + """uniform sampler2D uTarget;uniform float aspectRatio,radius,uMin,uMax;uniform vec4 color;uniform vec2 point;
void main(){vec2 p=vUv-point;p.x*=aspectRatio;vec4 s=exp(-dot(p,p)/radius)*color;vec4 b=texture2D(uTarget,vUv);gl_FragColor=clamp(b+s,vec4(uMin),vec4(uMax));}""",
            "buoy" to H + """uniform sampler2D uVelocity,uDye;uniform float g,dt,seed,uMode;uniform vec2 uForce;
float h(vec2 p){return fract(sin(dot(p,vec2(12.9898,78.233)))*43758.5453);}
void main(){vec2 v=texture2D(uVelocity,vUv).xy;vec4 D=max(texture2D(uDye,vUv),0.);float d=min(uMode<.5?dot(D.rgb,vec3(.333)):D.a*1.6,2.5);
float n=h(floor(vUv*90.)+seed)-.5;v.y-=g*d*dt;v.x+=n*abs(g)*d*dt*.35;v+=uForce*d*dt;gl_FragColor=vec4(v,0.,1.);}""",
            "advect" to H + """uniform sampler2D uVelocity,uSource;uniform vec2 texelSize,dyeTexelSize;uniform float dt,dissipation;
void main(){vec2 co=vUv-dt*texture2D(uVelocity,vUv).xy*texelSize;vec4 r=texture2D(uSource,co);gl_FragColor=r*dissipation;}""",
            "divergence" to H + """uniform sampler2D uVelocity;void main(){float L=texture2D(uVelocity,vL).x,R=texture2D(uVelocity,vR).x,T=texture2D(uVelocity,vT).y,B=texture2D(uVelocity,vB).y;
vec2 C=texture2D(uVelocity,vUv).xy;if(vL.x<0.)L=-C.x;if(vR.x>1.)R=-C.x;if(vT.y>1.)T=-C.y;if(vB.y<0.)B=-C.y;gl_FragColor=vec4(.5*(R-L+T-B),0.,0.,1.);}""",
            "curl" to H + """uniform sampler2D uVelocity;void main(){float L=texture2D(uVelocity,vL).y,R=texture2D(uVelocity,vR).y,T=texture2D(uVelocity,vT).x,B=texture2D(uVelocity,vB).x;gl_FragColor=vec4(.5*(R-L-T+B),0.,0.,1.);}""",
            "vorticity" to H + """uniform sampler2D uVelocity,uCurl;uniform float curl,dt;void main(){float L=texture2D(uCurl,vL).x,R=texture2D(uCurl,vR).x,T=texture2D(uCurl,vT).x,B=texture2D(uCurl,vB).x,C=texture2D(uCurl,vUv).x;
vec2 f=.5*vec2(abs(T)-abs(B),abs(R)-abs(L));f/=length(f)+.0001;f*=curl*C;f.y*=-1.;vec2 v=texture2D(uVelocity,vUv).xy+f*dt;gl_FragColor=vec4(clamp(v,-1000.,1000.),0.,1.);}""",
            "pressure" to H + """uniform sampler2D uPressure,uDivergence;void main(){float L=texture2D(uPressure,vL).x,R=texture2D(uPressure,vR).x,T=texture2D(uPressure,vT).x,B=texture2D(uPressure,vB).x;
gl_FragColor=vec4((L+R+B+T-texture2D(uDivergence,vUv).x)*.25,0.,0.,1.);}""",
            "gradient" to H + """uniform sampler2D uPressure,uVelocity;void main(){float L=texture2D(uPressure,vL).x,R=texture2D(uPressure,vR).x,T=texture2D(uPressure,vT).x,B=texture2D(uPressure,vB).x;
gl_FragColor=vec4(texture2D(uVelocity,vUv).xy-vec2(R-L,T-B),0.,1.);}""",
            "clear" to H + """uniform sampler2D uTexture;uniform float value;void main(){gl_FragColor=value*texture2D(uTexture,vUv);}""",
            "display" to H + """uniform sampler2D uTexture;uniform vec3 top,bot;uniform float uMode,time,uShade,uGlow,uCaustic,uGlass,uRadius;uniform vec2 res,tx;
float hsh(vec2 p){return fract(sin(dot(p,vec2(12.9898,78.233)))*43758.5453);}
vec4 S(vec2 uv){return max(texture2D(uTexture,uv),0.);}
float D(vec2 uv){vec4 s=S(uv);return uMode<.5?dot(s.rgb,vec3(.333)):s.a;}
void main(){
  vec2 gr=vec2(D(vUv+vec2(tx.x*2.,0.))-D(vUv-vec2(tx.x*2.,0.)),D(vUv+vec2(0.,tx.y*2.))-D(vUv-vec2(0.,tx.y*2.)));
  vec2 uv=vUv+gr*.012*uGlass;
  vec4 A=S(uv);
  vec4 Ab=vec4(0.);for(int i=0;i<8;i++){float a=float(i)*.785398;Ab+=S(uv+vec2(cos(a),sin(a))*tx*7.);}Ab/=8.;
  vec4 Am=A*(1.-.18*uGlow)+Ab*.4*uGlow;
  float gy=D(vUv+vec2(0.,tx.y*2.))-D(vUv-vec2(0.,tx.y*2.));
  float lit=clamp(-gy*2.2,-.5,.5)*uShade;
  vec3 water=mix(bot,top,smoothstep(0.,1.,vUv.y));
  vec3 lin;
  if(uMode<.5){lin=water*exp(-Am.rgb*.85*(1.-lit*.5));}
  else{float a=max(Am.a,1e-4);vec3 c=Am.rgb/a;float d=1.-exp(-Am.a*1.7);
    if(uMode<1.5)lin=water+c*d*(.7+lit*.45)+c*smoothstep(.35,1.,d)*.12*uGlow;
    else lin=mix(water,c*(.9+lit*.3),d);}
  float ca=sin(vUv.x*34.+time*.3+sin(vUv.y*20.+time*.21)*1.7)*sin(vUv.y*27.-time*.25);
  lin+=water*ca*.025*smoothstep(.55,1.,vUv.y)*uCaustic+ca*.0007*uCaustic*step(.5,uMode);
  vec3 c=pow(max(lin,0.),vec3(1./2.2));
  if(uGlass>.5){
    vec2 px=vUv*res,hf=res*.5,qq=abs(px-hf)-(hf-uRadius);
    float inside=-(length(max(qq,0.))+min(max(qq.x,qq.y),0.)-uRadius);
    float k=res.y/820.;
    vec2 nd=normalize((px-hf)/hf+1e-4);float lf=dot(nd,normalize(vec2(-.62,.78)));
    float rim=exp(-inside/(10.*k));
    c+=vec3(1.)*rim*max(lf,0.)*.16;
    c*=1.-rim*max(-lf,0.)*.22;
    c+=vec3(1.)*exp(-pow((inside-16.*k)/(2.2*k),2.))*max(lf,0.)*.06;
    c*=1.-.1*smoothstep(.25,.9,length((vUv-.5)*vec2(1.,.6))*1.6);
    float ph=mod(time*.07,2.6)-1.3;float sw=exp(-pow(dot(vUv-.5,normalize(vec2(1.,.55)))-ph,2.)*55.);
    c+=vec3(1.)*sw*.045*(1.-smoothstep(.2,1.2,Am.a+dot(Am.rgb,vec3(.2))));
    vec3 n=normalize(vec3(-gr*6.,1.));c+=vec3(1.)*pow(max(dot(n,normalize(vec3(-.4,.6,.7))),0.),60.)*.075*uShade;
  }
  c+=(hsh(vUv*res+fract(time))-.5)*.01;
  vec2 q=vUv-.5;c*=1.-dot(q,q)*.12;
  gl_FragColor=vec4(c,1.);}""",
        )

        fun toLin(c: Float) = if (c <= .04045f) c / 12.92f else ((c + .055f) / 1.055f).pow(2.4f)
        fun lin(color: Int) = floatArrayOf(
            toLin(Color.red(color) / 255f), toLin(Color.green(color) / 255f), toLin(Color.blue(color) / 255f),
        )

        fun now() = System.nanoTime() / 1_000_000
        const val FORCE = 34f
    }

    // ---------------------------------------------------------------- GL helpers
    private class Prog(vs: String, fs: String) {
        val id: Int
        private val locs = HashMap<String, Int>()

        init {
            fun compile(type: Int, src: String): Int {
                val s = GLES20.glCreateShader(type)
                GLES20.glShaderSource(s, src)
                GLES20.glCompileShader(s)
                val ok = IntArray(1)
                GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) throw RuntimeException(GLES20.glGetShaderInfoLog(s))
                return s
            }
            id = GLES20.glCreateProgram()
            GLES20.glAttachShader(id, compile(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(id, compile(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glBindAttribLocation(id, 0, "aPosition")
            GLES20.glLinkProgram(id)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) throw RuntimeException(GLES20.glGetProgramInfoLog(id))
        }

        fun use() = GLES20.glUseProgram(id)
        private fun loc(n: String) = locs.getOrPut(n) { GLES20.glGetUniformLocation(id, n) }
        fun i(n: String, v: Int) = GLES20.glUniform1i(loc(n), v)
        fun f(n: String, v: Float) = GLES20.glUniform1f(loc(n), v)
        fun f2(n: String, a: Float, b: Float) = GLES20.glUniform2f(loc(n), a, b)
        fun f3(n: String, a: Float, b: Float, c: Float) = GLES20.glUniform3f(loc(n), a, b, c)
        fun f4(n: String, a: Float, b: Float, c: Float, d: Float) = GLES20.glUniform4f(loc(n), a, b, c, d)
    }

    private class Fbo(val w: Int, val h: Int) {
        val tex: Int
        val fbo: Int
        val tx = 1f / w
        val ty = 1f / h
        var complete = true

        init {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            tex = t[0]
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, w, h, 0, GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
            val f = IntArray(1)
            GLES20.glGenFramebuffers(1, f, 0)
            fbo = f[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0)
            complete = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
            GLES20.glViewport(0, 0, w, h)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }

        fun attach(unit: Int): Int {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            return unit
        }

        fun delete() {
            GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        }
    }

    private class Dbl(w: Int, h: Int) {
        var read = Fbo(w, h)
        var write = Fbo(w, h)
        val tx = 1f / w
        val ty = 1f / h
        fun swap() {
            val t = read; read = write; write = t
        }

        fun delete() {
            read.delete(); write.delete()
        }
    }

    // ---------------------------------------------------------------- state
    private val queue = ConcurrentLinkedQueue<() -> Unit>()

    /** Safe from any thread; runs on the GL thread at the start of the next frame. */
    fun post(r: () -> Unit) {
        queue.add(r)
    }

    private class Splat(val at: Long, val x: Float, val y: Float, val dx: Float, val dy: Float, val c: FloatArray?, val r: Float)
    private class Gesture(val sx: Float, val sy: Float, var lx: Float, var ly: Float, var nextPour: Long, val color: Int) {
        var moved = false
    }

    private val progs = HashMap<String, Prog>()
    private var vel: Dbl? = null
    private var dye: Dbl? = null
    private var pres: Dbl? = null
    private var div: Fbo? = null
    private var curlF: Fbo? = null
    private var W = 0
    private var H = 0
    private var failed = false

    private val splats = ArrayList<Splat>()
    private var gesture: Gesture? = null
    private var look: Look? = null
    private var seenVersion = -1
    private var styleId: String? = null
    private var hue = 150f
    private var seedF = 0f
    private var simT = 0f
    private var lastT = 0L
    private var nextDrift = 0L
    private var warmAt = 0L
    private var clearUntil = 0L

    @Volatile
    private var lastTouch = 0L

    // Device motion: tilt is a unit-ish vector in screen axes (x right, y up) pointing "downhill".
    @Volatile
    private var tiltX = 0f

    @Volatile
    private var tiltY = 0f
    private var pushX = 0f
    private var smX = 0f
    private var smY = 0f
    private var lastAutoCheck = 0L

    fun setTilt(x: Float, y: Float) {
        tiltX = x; tiltY = y
    }

    /** A page swipe on the home screen: the liquid lags behind the scroll, then settles. */
    fun slosh(delta: Float) {
        pushX = (pushX + delta * 9f).coerceIn(-2.5f, 2.5f)
        lastTouch = now()
    }

    /** A shake: big stirs through the liquid and a few fresh drops. Strength is roughly 0..1. */
    fun shake(strength: Float) {
        if (look == null || W == 0) return
        lastTouch = now()
        repeat(4) {
            val a = Math.random().toFloat() * 6.283f
            splats.add(Splat(0, .15f + Math.random().toFloat() * .7f, .2f + Math.random().toFloat() * .6f,
                kotlin.math.cos(a) * 900f * strength, kotlin.math.sin(a) * 900f * strength, null, .012f))
        }
        driftDrop(); driftDrop(200)
    }

    /** True when nothing has been touched for a while, so the host can throttle the frame rate. */
    fun idle() = now() - lastTouch > 3000

    // ---------------------------------------------------------------- lifecycle
    /** Call whenever a fresh GL context is current. */
    fun create() {
        progs.clear()
        vel = null; dye = null; pres = null; div = null; curlF = null
        W = 0; H = 0
        queue.clear()
        failed = false
        try {
            for ((k, src) in FS) progs[k] = Prog(VS, src)
        } catch (e: Exception) {
            failed = true
            return
        }
        val vb = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(floatArrayOf(-1f, -1f, -1f, 1f, 1f, 1f, 1f, -1f)).also { it.position(0) }
        val ib = ByteBuffer.allocateDirect(6 * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            .put(shortArrayOf(0, 1, 2, 0, 2, 3)).also { it.position(0) }
        val b = IntArray(2)
        GLES20.glGenBuffers(2, b, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, b[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 32, vb, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, b[1])
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, 12, ib, GLES20.GL_STATIC_DRAW)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    fun destroy() {
        vel?.delete(); dye?.delete(); pres?.delete(); div?.delete(); curlF?.delete()
        vel = null; dye = null; pres = null; div = null; curlF = null
        W = 0; H = 0
    }

    fun resize(w: Int, h: Int) {
        if (failed || w <= 0 || h <= 0 || (w == W && h == H)) return
        destroy()
        W = w; H = h
        fun res(r: Float): Pair<Int, Int> {
            var a = W.toFloat() / H
            if (a < 1) a = 1 / a
            val mn = r.roundToInt()
            val mx = (r * a).roundToInt()
            return if (W > H) mx to mn else mn to mx
        }

        val s = res(140f)
        val d = res(640f)
        vel = Dbl(s.first, s.second)
        dye = Dbl(d.first, d.second)
        div = Fbo(s.first, s.second)
        curlF = Fbo(s.first, s.second)
        pres = Dbl(s.first, s.second)
        if (!div!!.complete || !dye!!.read.complete) {
            failed = true
            return
        }
        lastT = now()
        refreshLook()
        seedScene()
    }

    // ---------------------------------------------------------------- ink
    private fun refreshLook() {
        if (seenVersion == settings.version && look != null) return
        seenVersion = settings.version
        look = settings.look()
    }

    private fun inkColor(hex: Int, amt: Float): FloatArray {
        val s = look!!.s
        val c = lin(hex)
        return if (s.mode == 0) floatArrayOf(
            -ln(max(c[0], .02f)) * amt, -ln(max(c[1], .02f)) * amt, -ln(max(c[2], .02f)) * amt, 0f,
        ) else floatArrayOf(c[0] * amt, c[1] * amt, c[2] * amt, amt)
    }

    private fun hsl(h: Float, s: Float, l: Float): Int {
        val a = s * min(l, 1 - l)
        fun f(n: Int): Float {
            val k = (n + h / 30) % 12
            return l - a * max(-1f, min(min(k - 3, 9 - k), 1f))
        }
        return Color.rgb((f(0) * 255).roundToInt(), (f(8) * 255).roundToInt(), (f(4) * 255).roundToInt())
    }

    private fun pickColor(i: Int): Int {
        val s = look!!.s
        if (s.prism) {
            hue = 150 + ((hue - 150 + 23 + Math.random().toFloat() * 14) % 130)
            return hsl(hue, .62f, .66f)
        }
        return Color.parseColor(s.inks[i.coerceIn(0, s.inks.lastIndex)].second)
    }

    private fun drop(x: Float, y: Float, color: Int, size: Float = 1f, delay: Long = 0) {
        val l = look!!
        val dir = if (l.weight < 0) 1f else -1f
        val k = l.size * size
        val at = now() + delay
        val amt = if (l.s.mode == 0) 1f else .7f
        splats.add(Splat(at, x, y, 0f, 220 * dir * k, inkColor(color, .9f * k * amt), .00045f * k))
        splats.add(Splat(at + 40, x, y + .006f * dir, (Math.random().toFloat() - .5f) * 30, 160 * dir * k, inkColor(color, .5f * k * amt), .0007f * k))
    }

    private fun driftDrop(delay: Long = 0) {
        val l = look!!
        val i = (Math.random() * l.s.inks.size).toInt()
        val y = if (l.weight < 0) .34f + Math.random().toFloat() * .14f else .56f + Math.random().toFloat() * .18f
        drop(.15f + Math.random().toFloat() * .7f, y, pickColor(i), .8f + Math.random().toFloat() * .5f, delay)
    }

    private fun seedScene() {
        for (k in 0..2) driftDrop(k * 30L)
        warmAt = now() + 120
        driftDrop(1500)
        nextDrift = now() + 6000
    }

    /** A demo drop in the currently selected ink, used when picking a swatch. */
    fun sampleDrop() {
        val l = look ?: return
        val y = if (l.weight < 0) .38f else .74f
        drop(.3f + Math.random().toFloat() * .4f, y, pickColor(settings.inkIdx), 1.1f)
    }

    fun clear() {
        clearUntil = now() + 2200
    }

    private fun wipe() {
        splats.clear()
        for (t in listOfNotNull(dye?.read, dye?.write, vel?.read, vel?.write)) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, t.fbo)
            GLES20.glViewport(0, 0, t.w, t.h)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
    }

    // ---------------------------------------------------------------- touch (pixel coordinates)
    fun down(px: Float, py: Float) {
        if (look == null || W == 0) return
        lastTouch = now()
        val x = px / W
        val y = 1 - py / H
        val g = Gesture(x, y, x, y, now() + 260, pickColor(settings.inkIdx))
        gesture = g
        drop(x, y, g.color, 1f)
    }

    fun move(px: Float, py: Float) {
        val g = gesture ?: return
        if (W == 0) return
        lastTouch = now()
        val x = px / W
        val y = 1 - py / H
        val dx = x - g.lx
        val dy = y - g.ly
        if (!g.moved && hypot(x - g.sx, y - g.sy) > .025f) g.moved = true
        if (g.moved && hypot(dx, dy) > .002f) splats.add(Splat(0, x, y, dx * 1800, dy * 1800, null, .0035f))
        g.lx = x; g.ly = y
    }

    fun up() {
        gesture = null
    }

    private fun pourStep(t: Long) {
        val g = gesture ?: return
        if (g.moved || t < g.nextPour) return
        g.nextPour = t + 70
        val l = look!!
        val dir = if (l.weight < 0) 1f else -1f
        splats.add(
            Splat(0, g.sx + (Math.random().toFloat() - .5f) * .003f, g.sy, 0f, 150 * dir,
                inkColor(g.color, .32f * l.size * (if (l.s.mode == 0) 1f else .7f)), .00025f * l.size),
        )
    }

    // ---------------------------------------------------------------- simulation
    private fun blit(t: Fbo?) {
        if (t != null) {
            GLES20.glViewport(0, 0, t.w, t.h)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, t.fbo)
        } else {
            GLES20.glViewport(0, 0, W, H)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, 0)
    }

    private fun splat(sp: Splat) {
        val vel = vel!!
        val dye = dye!!
        val p = progs["splat"]!!
        p.use()
        p.f("aspectRatio", W.toFloat() / H)
        p.f2("point", sp.x, sp.y)
        p.f("radius", sp.r)
        p.i("uTarget", vel.read.attach(0))
        p.f4("color", sp.dx, sp.dy, 0f, 0f)
        p.f("uMin", -1e4f); p.f("uMax", 1e4f)
        blit(vel.write); vel.swap()
        if (sp.c != null) {
            p.i("uTarget", dye.read.attach(0))
            p.f4("color", sp.c[0], sp.c[1], sp.c[2], sp.c[3])
            p.f("uMin", 0f); p.f("uMax", 3.6f)
            blit(dye.write); dye.swap()
        }
    }

    private fun step(dt: Float, g: Float, curl: Float, mode: Int, dyeDiss: Float, fx: Float = 0f, fy: Float = 0f) {
        val vel = vel!!; val dye = dye!!; val pres = pres!!; val div = div!!; val curlF = curlF!!
        val f = dt * 60f
        var p = progs["buoy"]!!
        p.use()
        p.f2("texelSize", vel.tx, vel.ty)
        p.i("uVelocity", vel.read.attach(0)); p.i("uDye", dye.read.attach(1))
        p.f("g", g); p.f("dt", dt); p.f("uMode", mode.toFloat()); p.f2("uForce", fx, fy)
        seedF = (seedF + 1.618f) % 97f
        p.f("seed", seedF)
        blit(vel.write); vel.swap()

        p = progs["curl"]!!; p.use()
        p.f2("texelSize", vel.tx, vel.ty); p.i("uVelocity", vel.read.attach(0))
        blit(curlF)

        p = progs["vorticity"]!!; p.use()
        p.f2("texelSize", vel.tx, vel.ty); p.i("uVelocity", vel.read.attach(0)); p.i("uCurl", curlF.attach(1))
        p.f("curl", curl); p.f("dt", dt)
        blit(vel.write); vel.swap()

        p = progs["divergence"]!!; p.use()
        p.f2("texelSize", vel.tx, vel.ty); p.i("uVelocity", vel.read.attach(0))
        blit(div)

        p = progs["clear"]!!; p.use()
        p.i("uTexture", pres.read.attach(0)); p.f("value", .8f)
        blit(pres.write); pres.swap()

        p = progs["pressure"]!!; p.use()
        p.f2("texelSize", vel.tx, vel.ty); p.i("uDivergence", div.attach(0))
        repeat(22) {
            p.i("uPressure", pres.read.attach(1))
            blit(pres.write); pres.swap()
        }

        p = progs["gradient"]!!; p.use()
        p.f2("texelSize", vel.tx, vel.ty); p.i("uPressure", pres.read.attach(0)); p.i("uVelocity", vel.read.attach(1))
        blit(vel.write); vel.swap()

        p = progs["advect"]!!; p.use()
        p.f2("texelSize", vel.tx, vel.ty); p.f2("dyeTexelSize", vel.tx, vel.ty); p.f("dt", dt)
        p.i("uVelocity", vel.read.attach(0)); p.i("uSource", vel.read.attach(0))
        p.f("dissipation", .988f.pow(f))
        blit(vel.write); vel.swap()
        p.f2("dyeTexelSize", dye.tx, dye.ty)
        p.i("uVelocity", vel.read.attach(0)); p.i("uSource", dye.read.attach(1))
        p.f("dissipation", dyeDiss.pow(f))
        blit(dye.write); dye.swap()
    }

    private fun render(l: Look) {
        val dye = dye!!
        val p = progs["display"]!!
        p.use()
        p.i("uTexture", dye.read.attach(0))
        val top = lin(Color.parseColor(l.s.top))
        val bot = lin(Color.parseColor(l.s.bottom))
        p.f3("top", top[0], top[1], top[2]); p.f3("bot", bot[0], bot[1], bot[2])
        p.f("uMode", l.s.mode.toFloat()); p.f("time", simT)
        p.f("uShade", if (l.shade) 1f else 0f); p.f("uGlow", if (l.glow) 1f else 0f); p.f("uCaustic", if (l.caustic) 1f else 0f)
        p.f("uGlass", if (settings.glass) 1f else 0f); p.f("uRadius", W * .13f)
        p.f2("res", W.toFloat(), H.toFloat()); p.f2("tx", dye.tx, dye.ty); p.f2("texelSize", 0f, 0f)
        blit(null)
    }

    /** Advance the simulation by wall-clock time and draw. */
    fun frame() {
        var r = queue.poll()
        while (r != null) {
            r(); r = queue.poll()
        }
        if (failed || W == 0 || dye == null) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }
        val t = now()
        if (lastT == 0L) lastT = t
        val dt = min((t - lastT) / 1000f, 1f / 30f)
        lastT = t
        simT += dt

        if (t - lastAutoCheck > 1000) {
            lastAutoCheck = t
            if (STYLES[settings.effectiveIdx()].id != look?.s?.id) seenVersion = -1
        }
        refreshLook()
        val l = look!!
        if (styleId != l.s.id) {
            val first = styleId == null
            styleId = l.s.id
            if (!first) {
                wipe()
                for (k in 0..2) driftDrop(250L + k * 500)
                nextDrift = t + 4500
            }
        }

        for (i in splats.indices.reversed()) {
            val s = splats[i]
            if (s.at <= t) {
                splat(s)
                splats.removeAt(i)
            }
        }
        pourStep(t)
        if (settings.rain && t > nextDrift) {
            driftDrop()
            nextDrift = t + 5500 + (Math.random() * 4000).toLong()
        }

        val g = l.weight * 60f
        val diss = 1f - (.0045f * (1f - l.linger) + .00008f)
        if (warmAt != 0L && t >= warmAt) {
            warmAt = 0
            repeat(70) { step(1f / 60f * l.speed, g, l.curl, l.s.mode, 1f) }
        }
        smX += (tiltX + pushX - smX) * min(1f, dt * 6f)
        smY += (tiltY - smY) * min(1f, dt * 6f)
        pushX *= .92f.pow(dt * 60f)
        step(dt * l.speed, g, l.curl, l.s.mode, if (t < clearUntil) .94f else diss, smX * FORCE, smY * FORCE)
        render(l)
    }
}
