package com.ebrahim.roflash

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.TextView
import org.json.JSONObject
import java.util.Calendar

class OverlayService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var box: View? = null
    private var running = false
    private val hideR = Runnable { hide() }

    private val tick = object : Runnable {
        override fun run() {
            val c = cfg()
            val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val s = c.optInt("start", 8); val e = c.optInt("end", 22)
            val inWindow = if (s <= e) hr in s until e else hr >= s || hr < e
            if (inWindow && box == null && Settings.canDrawOverlays(this@OverlayService)) show(c)
            h.postDelayed(this, c.optInt("interval", 15).coerceAtLeast(1) * 60000L)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("p", "Passive exposure", NotificationManager.IMPORTANCE_MIN))
        startForeground(1, Notification.Builder(this, "p")
            .setContentTitle("Passive exposure is on")
            .setSmallIcon(android.R.drawable.ic_menu_agenda).build())
        if (!running) { running = true; h.postDelayed(tick, cfg().optInt("interval", 15).coerceAtLeast(1) * 60000L) }
        return START_STICKY
    }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); hide(); running = false }

    private fun data() = JSONObject(getSharedPreferences("p", 0).getString("data", "{}")!!)
    private fun cfg() = data().optJSONObject("cfg") ?: JSONObject()

    private fun show(c: JSONObject) {
        val sets = data().optJSONArray("sets") ?: return
        val list = ArrayList<Pair<String, String>>()
        for (i in 0 until sets.length()) {
            val s = sets.getJSONObject(i)
            if (!s.optBoolean("passive", true)) continue
            val cs = s.getJSONArray("cards")
            for (j in 0 until cs.length()) cs.getJSONObject(j).let { list.add(it.getString("f") to it.getString("b")) }
        }
        if (list.isEmpty()) return
        val p = list.random()
        val front = if (c.optBoolean("rev")) p.second else p.first
        val back = if (c.optBoolean("rev")) p.first else p.second
        var shown = false
        val tv = TextView(this).apply {
            text = "$front\n\n(tap to reveal)"
            textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            background = GradientDrawable().apply { setColor(Color.parseColor("#EE1F2937")); cornerRadius = 40f }
            setOnClickListener {
                if (!shown) {
                    shown = true; text = "$front\n— — —\n$back"
                    h.removeCallbacks(hideR); h.postDelayed(hideR, 8000)
                } else hide()
            }
        }
        val lp = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.9).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 220 }
        getSystemService(WindowManager::class.java).addView(tv, lp)
        box = tv
        h.postDelayed(hideR, 10000)
    }

    private fun hide() {
        h.removeCallbacks(hideR)
        box?.let { try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) {} }
        box = null
    }
}
