package com.legendsayantan.adbtools.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileNotFoundException

data class HistoryEntry(val pkg: String, val name: String, val timestamp: Long)

class HistoryManager(private val context: Context) {
    private val FILENAME = "uninstall_history.json"
    private val gson = Gson()

    fun getHistory(): List<HistoryEntry> {
        val file = File(context.filesDir, FILENAME)
        if (!file.exists()) return emptyList()
        return try {
            val json = file.readText()
            val type = object : TypeToken<List<HistoryEntry>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addHistory(pkg: String, name: String) {
        val current = getHistory().toMutableList()
        current.removeAll { it.pkg == pkg } // Remove duplicates
        current.add(0, HistoryEntry(pkg, name, System.currentTimeMillis()))
        val file = File(context.filesDir, FILENAME)
        file.writeText(gson.toJson(current))
    }
}
