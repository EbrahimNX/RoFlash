package com.ebrahim.roflash

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var wv: WebView
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (android.os.Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        wv = WebView(this); setContentView(wv)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.addJavascriptInterface(Bridge(), "Android")
        wv.loadUrl("file:///android_asset/index.html")
    }
    @Deprecated("old api") override fun onBackPressed() {
        wv.evaluateJavascript("back()") { if (it != "true") finish() }
    }
    inner class Bridge {
        @JavascriptInterface fun canOverlay() = Settings.canDrawOverlays(this@MainActivity)
        @JavascriptInterface fun askOverlay() {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        @JavascriptInterface fun sync(json: String) {
            getSharedPreferences("p", 0).edit().putString("data", json).apply()
            val on = JSONObject(json).optJSONObject("cfg")?.optBoolean("on") ?: false
            val i = Intent(this@MainActivity, OverlayService::class.java)
            if (on && Settings.canDrawOverlays(this@MainActivity)) startForegroundService(i) else stopService(i)
        }
    }
}
