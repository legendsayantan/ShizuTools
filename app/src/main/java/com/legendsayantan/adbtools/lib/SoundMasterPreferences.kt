package com.legendsayantan.adbtools.lib

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.legendsayantan.adbtools.data.RouteConfig

object SoundMasterPreferences {
    private const val PREFS_NAME = "soundmaster_prefs"
    private const val KEY_ROUTES = "routes_json"
    private const val KEY_ADVANCED_DSP = "advanced_dsp_mode"
    private const val KEY_BUBBLE_X = "bubble_x"
    private const val KEY_BUBBLE_Y = "bubble_y"
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveRoutes(context: Context, routes: List<RouteConfig>) {
        val json = Gson().toJson(routes)
        getPrefs(context).edit().putString(KEY_ROUTES, json).apply()
    }

    fun loadRoutes(context: Context): List<RouteConfig> {
        val json = getPrefs(context).getString(KEY_ROUTES, null) ?: return emptyList()
        val type = object : TypeToken<List<RouteConfig>>() {}.type
        return try {
            Gson().fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun isAdvancedDspMode(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ADVANCED_DSP, false)
    }

    fun setAdvancedDspMode(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ADVANCED_DSP, enabled).apply()
    }

    fun saveBubblePosition(context: Context, x: Int, y: Int) {
        getPrefs(context).edit()
            .putInt(KEY_BUBBLE_X, x)
            .putInt(KEY_BUBBLE_Y, y)
            .apply()
    }

    fun loadBubblePosition(context: Context): Pair<Int, Int> {
        val prefs = getPrefs(context)
        val x = prefs.getInt(KEY_BUBBLE_X, -1)
        val y = prefs.getInt(KEY_BUBBLE_Y, -1)
        return Pair(x, y)
    }
}
