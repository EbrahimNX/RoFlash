package com.ebrahim.roflash

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var wv: WebView
    private var cb: ValueCallback<Array<Uri>>? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = android.graphics.Color.WHITE
        window.navigationBarColor = android.graphics.Color.WHITE
        window.decorView.systemUiVisibility = 0x2010
        if (android.os.Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        wv = WebView(this); setContentView(wv)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(w: WebView?, c: ValueCallback<Array<Uri>>?, p: FileChooserParams?): Boolean {
                cb?.onReceiveValue(null); cb = c
                val i = Intent(Intent.ACTION_GET_CONTENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }
                startActivityForResult(i, 7)
                return true
            }
        }
        wv.addJavascriptInterface(Bridge(), "Android")
        wv.loadUrl("file:///android_asset/index.html")
    }

    @Deprecated("old api")
    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        if (r == 7) {
            val u = d?.data
            cb?.onReceiveValue(if (c == RESULT_OK && u != null) arrayOf(u) else null)
            cb = null
        } else super.onActivityResult(r, c, d)
    }

    @Deprecated("old api")
    override fun onBackPressed() {
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
