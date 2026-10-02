package com.ebrahim.roflash

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.abs
import kotlin.random.Random

class OverlayService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var box: View? = null
    private var anim: ValueAnimator? = null
    private var autoR: Runnable? = null
    private var running = false
    private var base = 0
    private var sw = 0

    private fun mins(s: String) = runCatching { s.split(":").let { it[0].toInt() * 60 + it[1].toInt() } }.getOrDefault(0)

    private val tick = object : Runnable {
        override fun run() {
            val c = cfg()
            val cal = Calendar.getInstance()
            val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val s = mins(c.optString("from", "08:00")); val e = mins(c.optString("to", "22:00"))
            val inWin = if (s <= e) now in s until e else now >= s || now < e
            if ((c.optBoolean("all", false) || inWin) && box == null && Settings.canDrawOverlays(this@OverlayService)) show(c)
            h.postDelayed(this, c.optInt("every", 15).coerceAtLeast(1) * 60000L)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("p", "Passive exposure", NotificationManager.IMPORTANCE_MIN))
        startForeground(1, Notification.Builder(this, "p").setContentTitle("Passive exposure is on")
            .setSmallIcon(android.R.drawable.ic_menu_agenda).build())
        if (!running) { running = true; h.postDelayed(tick, cfg().optInt("every", 15).coerceAtLeast(1) * 60000L) }
        return START_STICKY
    }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); hide(); running = false }

    private fun data() = JSONObject(getSharedPreferences("p", 0).getString("data", "{}")!!)
    private fun cfg() = data().optJSONObject("cfg") ?: JSONObject()

    private fun slide(v: View, lp: WindowManager.LayoutParams, from: Int, to: Int, ms: Long, ip: TimeInterpolator, end: () -> Unit = {}) {
        val wm = getSystemService(WindowManager::class.java)
        anim?.removeAllListeners(); anim?.cancel()
        anim = ValueAnimator.ofInt(from, to).apply {
            duration = ms; interpolator = ip
            addUpdateListener {
                if (box == null) return@addUpdateListener
                lp.x = it.animatedValue as Int
                v.alpha = (1f - abs(lp.x - base).toFloat() / sw).coerceIn(0f, 1f)
                try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { end() }
            })
            start()
        }
    }

    private fun show(c: JSONObject) {
        val cols = data().optJSONArray("cols") ?: return
        val pool = ArrayList<Triple<String, String, Int>>()
        for (i in 0 until cols.length()) {
            val col = cols.getJSONObject(i)
            if (!col.optBoolean("ps", true)) continue
            val cs = col.getJSONArray("cards")
            for (j in 0 until cs.length()) {
                val k = cs.getJSONObject(j)
                val w = when (k.optString("r")) { "e" -> 1; "h" -> 6; else -> 3 }
                pool.add(Triple(k.getString("en"), k.getString("ro"), w))
            }
        }
        if (pool.isEmpty()) return
        var x = Random.nextInt(pool.sumOf { it.third })
        val t = pool.first { x -= it.third; x < 0 }
        val roFirst = c.optString("face", "ro") == "ro"
        val front = if (roFirst) t.second else t.first
        val back = if (roFirst) t.first else t.second

        val wm = getSystemService(WindowManager::class.java)
        val d = resources.displayMetrics.density
        sw = resources.displayMetrics.widthPixels
        base = (12 * d).toInt()

        val icon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher); scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            outlineProvider = ViewOutlineProvider.BACKGROUND; clipToOutline = true; elevation = 8 * d
            layoutParams = LinearLayout.LayoutParams((50 * d).toInt(), (50 * d).toInt()).apply { marginEnd = (10 * d).toInt() }
        }
        val tv = TextView(this).apply {
            text = front; textSize = 18f; setTextColor(Color.parseColor("#111827"))
            maxWidth = (sw * 0.75).toInt() - (50 * d).toInt() - (10 * d).toInt() - (40 * d).toInt()
        }
        val card = LinearLayout(this).apply {
            setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), (16 * d).toInt())
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 30 * d }
            elevation = 8 * d; cameraDistance = 8000 * d
            addView(tv)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(icon); addView(card); alpha = 0f
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = -sw; y = (64 * d).toInt() }

        var shown = false
        fun leave(dir: Int) = slide(root, lp, lp.x, if (dir > 0) -sw else sw, 280, AccelerateInterpolator()) { hide() }
        fun flip() {
            if (shown) { leave(1); return }
            shown = true
            card.animate().rotationY(90f).setDuration(140).withEndAction {
                tv.text = back; card.rotationY = -90f
                card.animate().rotationY(0f).setDuration(160).start()
            }.start()
            autoR = Runnable { leave(1) }.also { h.postDelayed(it, 5000) }
        }
        var downX = 0f; var startX = 0; var moved = false
        root.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; startX = lp.x; moved = false; anim?.cancel() }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    if (abs(dx) > 16 * d) moved = true
                    if (moved) {
                        lp.x = startX - dx.toInt()
                        try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.rawX - downX
                    if (!moved) flip()
                    else if (abs(dx) > 90 * d) leave(if (dx > 0) 1 else -1)
                    else slide(root, lp, lp.x, base, 250, OvershootInterpolator())
                }
            }
            true
        }
        wm.addView(root, lp)
        box = root
        slide(root, lp, -sw, base, 520, OvershootInterpolator(1.1f))
    }

    private fun hide() {
        anim?.removeAllListeners(); anim?.cancel(); anim = null
        autoR?.let { h.removeCallbacks(it) }; autoR = null
        box?.let { try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) {} }
        box = null
    }
}
