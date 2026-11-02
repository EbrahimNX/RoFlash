package com.ebrahim.roflash

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.util.Base64
import android.view.*
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import kotlin.math.abs
import kotlin.random.Random

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        runCatching {
            val on = JSONObject(File(c.filesDir, "data.json").readText()).optJSONObject("cfg")?.optBoolean("on") ?: false
            if (on && Settings.canDrawOverlays(c)) c.startForegroundService(Intent(c, OverlayService::class.java))
        }
    }
}

class OverlayService : Service() {
    private class Item(val en: String, val ro: String, val w: Int, val img: String)

    private val h = Handler(Looper.getMainLooper())
    private val action = "com.ebrahim.roflash.TICK"
    private var box: View? = null
    private var anim: ValueAnimator? = null
    private var autoR: Runnable? = null
    private var running = false
    private var lastSig = ""
    private var base = 0
    private var sw = 0
    private val rx = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { tick() } }

    private fun mins(s: String) = runCatching { s.split(":").let { it[0].toInt() * 60 + it[1].toInt() } }.getOrDefault(0)
    private fun pi() = PendingIntent.getBroadcast(this, 0, Intent(action).setPackage(packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun schedule(ms: Long) {
        getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + ms.coerceAtLeast(3000), pi())
    }

    @Suppress("DEPRECATION")
    private fun fg(): String? = try {
        val um = getSystemService(UsageStatsManager::class.java)
        val end = System.currentTimeMillis()
        val ev = um.queryEvents(end - 2 * 3600_000L, end)
        val e = UsageEvents.Event(); var last: String? = null
        while (ev.hasNextEvent()) { ev.getNextEvent(e); if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) last = e.packageName }
        last
    } catch (_: Exception) { null }

    private fun tick() {
        val c = cfg()
        val everyMs = c.optInt("every", 15).coerceAtLeast(1) * 60000L
        val prefs = getSharedPreferences("p", 0)
        val now = System.currentTimeMillis()
        val last = prefs.getLong("last", 0L)
        if (now - last < everyMs - 5000) { schedule(everyMs - (now - last)); return }
        val cal = Calendar.getInstance()
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val s = mins(c.optString("from", "08:00")); val e = mins(c.optString("to", "22:00"))
        val inWin = c.optBoolean("all", false) || (if (s <= e) m in s until e else m >= s || m < e)
        if (!inWin) { schedule(5 * 60000L); return }
        val skip = c.optJSONArray("skip"); val f = fg()
        val blocked = MainActivity.visible || (f != null && skip != null && (0 until skip.length()).any { skip.getString(it) == f })
        if (blocked || box != null || !Settings.canDrawOverlays(this)) { schedule(30000L); return }
        show(c)
        prefs.edit().putLong("last", now).apply()
        schedule(everyMs)
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val f = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(rx, f)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("p", "Passive exposure", NotificationManager.IMPORTANCE_MIN))
        try {
            startForeground(1, Notification.Builder(this, "p").setContentTitle("Passive exposure is on")
                .setSmallIcon(android.R.drawable.ic_menu_agenda).build())
        } catch (_: Exception) {}
        val sig = cfg().toString()
        if (!running) { running = true; lastSig = sig; schedule(8000) }
        else if (sig != lastSig) { lastSig = sig; schedule(2000) }
        return START_STICKY
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null); hide(); running = false
        try { unregisterReceiver(rx) } catch (_: Exception) {}
        getSystemService(AlarmManager::class.java).cancel(pi())
    }

    private fun data(): JSONObject = runCatching { JSONObject(File(filesDir, "data.json").readText()) }
        .getOrElse { JSONObject(getSharedPreferences("p", 0).getString("data", "{}")!!) }
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
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { end() } })
            start()
        }
    }

    private fun show(c: JSONObject) {
        val cols = data().optJSONArray("cols") ?: return
        val pool = ArrayList<Item>()
        for (i in 0 until cols.length()) {
            val col = cols.getJSONObject(i)
            if (!col.optBoolean("ps", true)) continue
            val cs = col.getJSONArray("cards")
            for (j in 0 until cs.length()) {
                val k = cs.getJSONObject(j)
                val w = when (k.optString("r")) { "e" -> 1; "h" -> 8; else -> 3 }
                pool.add(Item(k.optString("en", ""), k.optString("ro", ""), w, k.optString("pic", "")))
            }
        }
        if (pool.isEmpty()) return
        var x = Random.nextInt(pool.sumOf { it.w })
        val t = pool.first { x -= it.w; x < 0 }
        val roFirst = c.optString("face", "ro") == "ro"
        val front = (if (roFirst) t.ro else t.en).ifBlank { t.ro.ifBlank { t.en } }
        val back = (if (roFirst) t.en else t.ro).ifBlank { front }
        val bmp = if (c.optBoolean("pics", false) && t.img.contains(",")) runCatching {
            val b = Base64.decode(t.img.substringAfter(","), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(b, 0, b.size)
        }.getOrNull() else null
        val tf = runCatching { Typeface.createFromAsset(assets, "fonts/ma.ttf") }.getOrNull()

        val wm = getSystemService(WindowManager::class.java)
        val d = resources.displayMetrics.density
        sw = resources.displayMetrics.widthPixels
        base = (12 * d).toInt()

        val icon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher); scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke((2 * d).toInt(), Color.WHITE) }
            outlineProvider = ViewOutlineProvider.BACKGROUND; clipToOutline = true
            layoutParams = LinearLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt()).apply { marginStart = (10 * d).toInt() }
        }
        val tv = TextView(this).apply {
            text = front; textSize = 18f; setTextColor(Color.parseColor("#1E1B3A"))
            if (tf != null) typeface = tf
            maxWidth = (sw * 0.75).toInt() - (52 * d).toInt() - (10 * d).toInt() - (40 * d).toInt()
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * d).toInt(), (14 * d).toInt(), (18 * d).toInt(), (14 * d).toInt())
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 28 * d; setStroke((1 * d).toInt(), Color.parseColor("#E3E0F5")) }
            cameraDistance = 8000 * d
            if (bmp != null) addView(ImageView(this@OverlayService).apply {
                setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, 16 * d) }
                }
                clipToOutline = true
                layoutParams = LinearLayout.LayoutParams((180 * d).toInt(), (135 * d).toInt()).apply { bottomMargin = (10 * d).toInt() }
            })
            addView(tv)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(card); addView(icon); alpha = 0f
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
                    if (moved) { lp.x = startX - dx.toInt(); try { wm.updateViewLayout(root, lp) } catch (_: Exception) {} }
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
