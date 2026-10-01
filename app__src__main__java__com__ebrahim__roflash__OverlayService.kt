package com.ebrahim.roflash

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.Calendar
import kotlin.random.Random

class OverlayService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var box: View? = null
    private var running = false
    private val hideR = Runnable { hide() }

    private fun mins(s: String) = runCatching { s.split(":").let { it[0].toInt() * 60 + it[1].toInt() } }.getOrDefault(0)

    private val tick = object : Runnable {
        override fun run() {
            val c = cfg()
            val cal = Calendar.getInstance()
            val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val s = mins(c.optString("from", "08:00")); val e = mins(c.optString("to", "22:00"))
            val ok = if (s <= e) now in s until e else now >= s || now < e
            if (ok && box == null && Settings.canDrawOverlays(this@OverlayService)) show(c)
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
        val d = resources.displayMetrics.density
        val maxW = (resources.displayMetrics.widthPixels * 0.75).toInt()
        val icon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher); scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.LTGRAY) }
            outlineProvider = ViewOutlineProvider.BACKGROUND; clipToOutline = true
            layoutParams = LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()).apply { marginEnd = (12 * d).toInt() }
        }
        val tv = TextView(this).apply {
            text = front; textSize = 18f; setTextColor(Color.parseColor("#111827"))
            maxWidth = maxW - (44 * d).toInt() - (64 * d).toInt()
        }
        var shown = false
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * d).toInt(), (14 * d).toInt(), (20 * d).toInt(), (14 * d).toInt())
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 32 * d }
            elevation = 12 * d
            addView(icon); addView(tv)
            setOnClickListener {
                if (!shown) { shown = true; tv.text = "$front\n— $back"; h.removeCallbacks(hideR); h.postDelayed(hideR, 8000) }
                else hide()
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = (12 * d).toInt(); y = (64 * d).toInt() }
        getSystemService(WindowManager::class.java).addView(card, lp)
        box = card
        h.postDelayed(hideR, 10000)
    }

    private fun hide() {
        h.removeCallbacks(hideR)
        box?.let { try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) {} }
        box = null
    }
}
