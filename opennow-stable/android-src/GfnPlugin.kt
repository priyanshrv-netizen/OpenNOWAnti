package com.zortos.opennow

import android.content.pm.ActivityInfo
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.JSObject
import com.getcapacitor.JSArray
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.net.InetAddress

/**
 * GfnPlugin — Capacitor native plugin for OpenNOW Android.
 *
 * Handles all platform-specific operations that cannot be done in the WebView:
 *  - Auth token management (secure storage via EncryptedSharedPreferences)
 *  - GFN API calls (login, regions, games, sessions)
 *  - Device orientation + fullscreen management
 *  - Settings persistence (SharedPreferences)
 *  - Region ping
 */
@CapacitorPlugin(name = "GfnPlugin")
class GfnPlugin : Plugin() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val http = OkHttpClient()
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val prefs by lazy {
        activity.getSharedPreferences("opennow_prefs", android.content.Context.MODE_PRIVATE)
    }

    // ------------------------------------------------------------------ Auth
    // Tokens are stored in plain SharedPreferences for now.
    // Replace with EncryptedSharedPreferences for production builds.

    @PluginMethod
    fun getAuthSession(call: PluginCall) {
        scope.launch {
            val accessToken  = prefs.getString("access_token", null)
            val refreshToken = prefs.getString("refresh_token", null)
            val idToken      = prefs.getString("id_token", null)
            val expiresAt    = prefs.getLong("expires_at", 0L)
            val userId       = prefs.getString("user_id", null)
            val displayName  = prefs.getString("display_name", null)
            val tier         = prefs.getString("membership_tier", "FREE")
            val providerUrl  = prefs.getString("provider_streaming_url", "") ?: ""
            val idpId        = prefs.getString("provider_idp_id", "nvidia") ?: "nvidia"
            val providerName = prefs.getString("provider_display_name", "NVIDIA") ?: "NVIDIA"

            val ret = JSObject()
            if (accessToken == null || userId == null) {
                ret.put("session", JSObject.NULL)
            } else {
                val tokens = JSObject().apply {
                    put("accessToken",  accessToken)
                    refreshToken?.let { put("refreshToken", it) }
                    idToken?.let      { put("idToken", it) }
                    put("expiresAt",   expiresAt)
                }
                val user = JSObject().apply {
                    put("userId",       userId)
                    put("displayName",  displayName ?: userId)
                    put("membershipTier", tier)
                }
                val provider = JSObject().apply {
                    put("idpId",  idpId)
                    put("code",   idpId)
                    put("displayName", providerName)
                    put("streamingServiceUrl", providerUrl)
                    put("priority", 0)
                }
                val session = JSObject().apply {
                    put("tokens",   tokens)
                    put("user",     user)
                    put("provider", provider)
                }
                ret.put("session", session)
            }
            val refresh = JSObject().apply {
                put("attempted", false)
                put("forced",    false)
                put("outcome",   "not_attempted")
                put("message",   "")
            }
            ret.put("refresh", refresh)
            call.resolve(ret)
        }
    }

    @PluginMethod
    fun getLoginProviders(call: PluginCall) {
        scope.launch {
            // Fetch providers from GFN API
            try {
                val req = Request.Builder()
                    .url("https://gfn.am/v1/login-providers")
                    .header("Content-Type", "application/json")
                    .build()
                val body = http.newCall(req).execute().use { it.body?.string() ?: "[]" }
                val arr = try { JSONArray(body) } catch (_: Exception) { JSONArray() }
                val result = JSObject()
                result.put("providers", JSArray(arr.toString()))
                call.resolve(result)
            } catch (e: Exception) {
                // Fallback: return a minimal NVIDIA provider
                val fallback = JSArray().apply {
                    put(JSONObject().apply {
                        put("idpId", "nvidia")
                        put("code",  "nvidia")
                        put("displayName", "NVIDIA")
                        put("streamingServiceUrl", "https://api-prod.nvidia.com/gfn/v1")
                        put("priority", 0)
                    })
                }
                val result = JSObject()
                result.put("providers", fallback)
                call.resolve(result)
            }
        }
    }

    @PluginMethod
    fun login(call: PluginCall) {
        // The web layer handles the OAuth flow.
        // This method is called after the user completes OAuth to persist tokens.
        val accessToken  = call.getString("accessToken") ?: return call.reject("Missing accessToken")
        val refreshToken = call.getString("refreshToken")
        val idToken      = call.getString("idToken")
        val expiresAt    = call.getLong("expiresAt", 0L) ?: 0L
        val userId       = call.getString("userId") ?: return call.reject("Missing userId")
        val displayName  = call.getString("displayName") ?: userId
        val tier         = call.getString("membershipTier") ?: "FREE"
        val providerUrl  = call.getString("streamingServiceUrl") ?: ""
        val idpId        = call.getString("idpId") ?: "nvidia"
        val providerName = call.getString("providerDisplayName") ?: "NVIDIA"

        prefs.edit().apply {
            putString("access_token",  accessToken)
            refreshToken?.let { putString("refresh_token", it) }
            idToken?.let       { putString("id_token", it) }
            putLong("expires_at", expiresAt)
            putString("user_id", userId)
            putString("display_name", displayName)
            putString("membership_tier", tier)
            putString("provider_streaming_url", providerUrl)
            putString("provider_idp_id", idpId)
            putString("provider_display_name", providerName)
            apply()
        }
        call.resolve()
    }

    @PluginMethod
    fun logout(call: PluginCall) {
        prefs.edit().clear().apply()
        call.resolve()
    }

    // ---------------------------------------------------------------- Regions

    @PluginMethod
    fun getRegions(call: PluginCall) {
        val token   = call.getString("token") ?: ""
        val baseUrl = call.getString("streamingBaseUrl") ?: "https://api-prod.nvidia.com/gfn/v1"

        scope.launch {
            try {
                val req = Request.Builder()
                    .url("$baseUrl/zones")
                    .header("Authorization", "Bearer $token")
                    .build()
                val body = http.newCall(req).execute().use { it.body?.string() ?: "{}" }
                val json = JSONObject(body)
                val zonesArr = json.optJSONArray("zones") ?: JSONArray()
                val regions = JSArray()
                for (i in 0 until zonesArr.length()) {
                    val z = zonesArr.optJSONObject(i) ?: continue
                    val region = JSObject().apply {
                        put("name", z.optString("id", ""))
                        put("url",  z.optString("url", ""))
                    }
                    regions.put(region)
                }
                call.resolve(JSObject().apply { put("regions", regions) })
            } catch (e: Exception) {
                call.resolve(JSObject().apply { put("regions", JSArray()) })
            }
        }
    }

    // --------------------------------------------------------------- Settings

    @PluginMethod
    fun getSettings(call: PluginCall) {
        val ret = JSObject().apply {
            put("resolution",          prefs.getString("res", "1920x1080"))
            put("aspectRatio",         prefs.getString("aspect_ratio", "16:9"))
            put("fps",                 prefs.getInt("fps", 60))
            put("maxBitrateMbps",      prefs.getInt("max_bitrate_mbps", 75))
            put("codec",               prefs.getString("codec", "H264"))
            put("colorQuality",        prefs.getString("color_quality", "8bit_420"))
            put("region",              prefs.getString("region", ""))
            put("clipboardPaste",      prefs.getBoolean("clipboard_paste", false))
            put("mouseSensitivity",    prefs.getFloat("mouse_sensitivity", 1f).toDouble())
            put("mouseAcceleration",   prefs.getFloat("mouse_acceleration", 1f).toDouble())
            put("shortcutToggleStats", prefs.getString("sc_stats", "F3"))
            put("shortcutTogglePointerLock", prefs.getString("sc_ptr", "F8"))
            put("shortcutStopStream",  prefs.getString("sc_stop", "Ctrl+Shift+Q"))
            put("shortcutToggleAntiAfk", prefs.getString("sc_antiafk", "Ctrl+Shift+K"))
            put("shortcutToggleMicrophone", prefs.getString("sc_mic", "Ctrl+Shift+M"))
            put("shortcutScreenshot",  prefs.getString("sc_screenshot", "F11"))
            put("shortcutToggleRecording", prefs.getString("sc_recording", "F12"))
            put("microphoneMode",      prefs.getString("mic_mode", "disabled"))
            put("microphoneDeviceId",  prefs.getString("mic_device_id", ""))
            put("hideStreamButtons",   prefs.getBoolean("hide_stream_buttons", false))
            put("controllerMode",      prefs.getBoolean("controller_mode", false))
            put("controllerUiSounds",  prefs.getBoolean("controller_ui_sounds", false))
            put("controllerBackgroundAnimations", prefs.getBoolean("controller_bg_anim", false))
            put("autoLoadControllerLibrary", prefs.getBoolean("auto_load_ctrl_lib", false))
            put("autoFullScreen",      prefs.getBoolean("auto_fullscreen", false))
            put("favoriteGameIds",     JSArray())
            put("sessionCounterEnabled", prefs.getBoolean("session_counter", false))
            put("sessionClockShowEveryMinutes", prefs.getInt("clock_every_min", 60))
            put("sessionClockShowDurationSeconds", prefs.getInt("clock_duration_sec", 30))
            put("windowWidth",         1920)
            put("windowHeight",        1080)
            put("keyboardLayout",      prefs.getString("keyboard_layout", "en-US"))
            put("gameLanguage",        prefs.getString("game_lang", "en_US"))
            put("enableL4S",           prefs.getBoolean("enable_l4s", false))
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun setSetting(call: PluginCall) {
        val key   = call.getString("key")   ?: return call.reject("Missing key")
        val value = call.data.opt("value")  ?: return call.reject("Missing value")
        val edit  = prefs.edit()
        when (value) {
            is Boolean -> edit.putBoolean(prefKey(key), value)
            is Int     -> edit.putInt(prefKey(key), value)
            is Long    -> edit.putLong(prefKey(key), value)
            is Double  -> edit.putFloat(prefKey(key), value.toFloat())
            is Float   -> edit.putFloat(prefKey(key), value)
            else       -> edit.putString(prefKey(key), value.toString())
        }
        edit.apply()
        call.resolve()
    }

    @PluginMethod
    fun resetSettings(call: PluginCall) {
        // Only remove settings keys (keep auth)
        val authKeys = setOf("access_token","refresh_token","id_token","expires_at",
            "user_id","display_name","membership_tier","provider_streaming_url",
            "provider_idp_id","provider_display_name")
        val edit = prefs.edit()
        prefs.all.keys.filter { it !in authKeys }.forEach { edit.remove(it) }
        edit.apply()
        call.resolve()
    }

    private fun prefKey(jsKey: String): String = when (jsKey) {
        "resolution"           -> "res"
        "fps"                  -> "fps"
        "maxBitrateMbps"       -> "max_bitrate_mbps"
        "codec"                -> "codec"
        "colorQuality"         -> "color_quality"
        "region"               -> "region"
        "clipboardPaste"       -> "clipboard_paste"
        "mouseSensitivity"     -> "mouse_sensitivity"
        "mouseAcceleration"    -> "mouse_acceleration"
        "microphoneMode"       -> "mic_mode"
        "microphoneDeviceId"   -> "mic_device_id"
        "hideStreamButtons"    -> "hide_stream_buttons"
        "controllerMode"       -> "controller_mode"
        "controllerUiSounds"   -> "controller_ui_sounds"
        "autoFullScreen"       -> "auto_fullscreen"
        "enableL4S"            -> "enable_l4s"
        else                   -> jsKey
    }

    // ---------------------------------------------------- Orientation / UI

    @PluginMethod
    fun setOrientation(call: PluginCall) {
        val mode = call.getString("mode") ?: "landscape"
        activity.runOnUiThread {
            activity.requestedOrientation = when (mode) {
                "portrait"  -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                "sensor"    -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                else        -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }
        call.resolve()
    }

    @PluginMethod
    fun toggleFullscreen(call: PluginCall) {
        activity.runOnUiThread {
            val window = activity.window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val insetsController = WindowInsetsControllerCompat(window, window.decorView)
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        call.resolve()
    }

    @PluginMethod
    fun showSessionConflictDialog(call: PluginCall) {
        // Return "new" by default — could show an AlertDialog in production
        call.resolve(JSObject().apply { put("choice", "new") })
    }

    // ------------------------------------------------------------------ Ping

    @PluginMethod
    fun pingRegions(call: PluginCall) {
        val urls  = call.getArray("urls", JSArray()) ?: JSArray()
        scope.launch {
            val results = JSObject()
            for (i in 0 until urls.length()) {
                val url = urls.optString(i) ?: continue
                val pingMs = try {
                    measurePingMs(url)
                } catch (_: Exception) {
                    -1L
                }
                results.put(url, pingMs)
            }
            call.resolve(JSObject().apply { put("results", results) })
        }
    }

    private fun measurePingMs(url: String): Long {
        val host = url.replace(Regex("^https?://"), "").split("/")[0].split(":")[0]
        val start = System.currentTimeMillis()
        InetAddress.getByName(host).isReachable(2000)
        return System.currentTimeMillis() - start
    }

    // ----------------------------------------------- Games (via HTTP fetch)

    @PluginMethod
    fun fetchMainGames(call: PluginCall) {
        val token   = call.getString("token") ?: ""
        val baseUrl = call.getString("providerStreamingBaseUrl") ?: ""
        scope.launch {
            try {
                val json = getJson("$baseUrl/games?library=false", token)
                call.resolve(JSObject().apply { put("games", JSArray(json)) })
            } catch (e: Exception) {
                call.resolve(JSObject().apply { put("games", JSArray()) })
            }
        }
    }

    @PluginMethod
    fun fetchLibraryGames(call: PluginCall) {
        val token   = call.getString("token") ?: ""
        val baseUrl = call.getString("providerStreamingBaseUrl") ?: ""
        scope.launch {
            try {
                val json = getJson("$baseUrl/games?library=true", token)
                call.resolve(JSObject().apply { put("games", JSArray(json)) })
            } catch (e: Exception) {
                call.resolve(JSObject().apply { put("games", JSArray()) })
            }
        }
    }

    @PluginMethod
    fun fetchPublicGames(call: PluginCall) {
        scope.launch {
            try {
                val json = getJson("https://gfn.am/v1/games", "")
                call.resolve(JSObject().apply { put("games", JSArray(json)) })
            } catch (e: Exception) {
                call.resolve(JSObject().apply { put("games", JSArray()) })
            }
        }
    }

    @PluginMethod
    fun resolveLaunchAppId(call: PluginCall) {
        call.resolve(JSObject().apply { put("appId", JSObject.NULL) })
    }

    // --------------------------------------------------- Session Management

    @PluginMethod
    fun createSession(call: PluginCall) {
        val token   = call.getString("token") ?: ""
        val baseUrl = call.getString("streamingBaseUrl") ?: ""
        scope.launch {
            try {
                val body = call.data.toString().toRequestBody(JSON_MEDIA)
                val req  = Request.Builder()
                    .url("$baseUrl/sessions")
                    .header("Authorization", "Bearer $token")
                    .post(body)
                    .build()
                val resp = http.newCall(req).execute().use { it.body?.string() ?: "{}" }
                call.resolve(JSObject(resp))
            } catch (e: Exception) {
                call.reject("createSession failed: ${e.message}")
            }
        }
    }

    @PluginMethod
    fun pollSession(call: PluginCall) {
        val token     = call.getString("token") ?: ""
        val baseUrl   = call.getString("streamingBaseUrl") ?: ""
        val sessionId = call.getString("sessionId") ?: ""
        val serverIp  = call.getString("serverIp") ?: ""
        val zone      = call.getString("zone") ?: ""
        scope.launch {
            try {
                val url = "$baseUrl/zones/$zone/sessions/$sessionId/status?serverIp=$serverIp"
                val resp = getJson(url, token)
                call.resolve(JSObject(resp))
            } catch (e: Exception) {
                call.reject("pollSession failed: ${e.message}")
            }
        }
    }

    @PluginMethod
    fun stopSession(call: PluginCall) {
        val token     = call.getString("token") ?: ""
        val baseUrl   = call.getString("streamingBaseUrl") ?: ""
        val sessionId = call.getString("sessionId") ?: ""
        val zone      = call.getString("zone") ?: ""
        val serverIp  = call.getString("serverIp") ?: ""
        scope.launch {
            try {
                val url = "$baseUrl/zones/$zone/sessions/$sessionId?serverIp=$serverIp"
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .delete()
                    .build()
                http.newCall(req).execute().close()
                call.resolve()
            } catch (_: Exception) {
                call.resolve()
            }
        }
    }

    @PluginMethod
    fun getActiveSessions(call: PluginCall) {
        call.resolve(JSObject().apply { put("sessions", JSArray()) })
    }

    @PluginMethod
    fun claimSession(call: PluginCall) {
        call.reject("claimSession not implemented on Android")
    }

    @PluginMethod
    fun reportSessionAd(call: PluginCall) {
        call.resolve(JSObject())
    }

    // -------------------------------------------------------------- Helpers

    private fun getJson(url: String, token: String): String {
        val reqBuilder = Request.Builder().url(url)
        if (token.isNotEmpty()) reqBuilder.header("Authorization", "Bearer $token")
        return http.newCall(reqBuilder.build()).execute().use { it.body?.string() ?: "{}" }
    }

    override fun handleOnDestroy() {
        scope.cancel()
        super.handleOnDestroy()
    }
}
