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
    private var pending = ""

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
        } else if (r == 8) {
            val u = d?.data
            if (c == RESULT_OK && u != null) {
                val ok = runCatching { contentResolver.openOutputStream(u)?.use { it.write(pending.toByteArray(Charsets.UTF_8)) } }.isSuccess
                wv.evaluateJavascript(if (ok) "toast('Saved')" else "toast('Could not save the file')", null)
            }
        } else super.onActivityResult(r, c, d)
    }

    @Deprecated("old api")
    override fun onBackPressed() {
        wv.evaluateJavascript("back()") { if (it != "true") finish() }
    }

    inner class Bridge {
        @JavascriptInterface fun exportTxt(name: String, text: String) {
            pending = text
            runOnUiThread {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"; putExtra(Intent.EXTRA_TITLE, name)
                }, 8)
            }
        }
        @JavascriptInterface fun canOverlay() = Settings.canDrawOverlays(this@MainActivity)
        @JavascriptInterface fun askOverlay() {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        @JavascriptInterface fun apps(): String {
            val pm = packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val arr = org.json.JSONArray()
            pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }.filter { it.first != packageName }.sortedBy { it.second.lowercase() }
                .forEach { arr.put(JSONObject().put("p", it.first).put("n", it.second)) }
            return arr.toString()
        }
        @JavascriptInterface fun hasUsage(): Boolean {
            val ao = getSystemService(android.app.AppOpsManager::class.java)
            return ao.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == android.app.AppOpsManager.MODE_ALLOWED
        }
        @JavascriptInterface fun askUsage() { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        @JavascriptInterface fun load(): String {
            for (n in listOf("data.json", "data.bak")) {
                val f = java.io.File(filesDir, n)
                if (f.exists()) {
                    val t = runCatching { f.readText() }.getOrDefault("")
                    if (t.startsWith("{")) return t
                }
            }
            return ""
        }
        @JavascriptInterface fun save(full: String, lite: String) {
            val f = java.io.File(filesDir, "data.json")
            val tmp = java.io.File(filesDir, "data.tmp")
            runCatching {
                tmp.writeText(full)
                if (f.exists()) f.copyTo(java.io.File(filesDir, "data.bak"), overwrite = true)
                tmp.renameTo(f)
            }
            getSharedPreferences("p", 0).edit().putString("data", lite).commit()
            val on = JSONObject(lite).optJSONObject("cfg")?.optBoolean("on") ?: false
            val i = Intent(this@MainActivity, OverlayService::class.java)
            if (on && Settings.canDrawOverlays(this@MainActivity)) startForegroundService(i) else stopService(i)
        }
    }
}
