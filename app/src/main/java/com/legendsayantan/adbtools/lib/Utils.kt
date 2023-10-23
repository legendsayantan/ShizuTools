package com.legendsayantan.adbtools.lib

import android.app.Activity
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.legendsayantan.adbtools.R

/**
 * @author legendsayantan
 */
class Utils {
    companion object{
        fun String.extractUrls(): List<String> {
            val urlRegex = Regex("(https?|ftp|file)://[-A-Za-z0-9+&@#/%?=~_|!:,.;]*[-A-Za-z0-9+&@#/%=~_|]")
            val matches = urlRegex.findAll(this)
            val urls = mutableListOf<String>()

            for (match in matches) {
                urls.add(match.value)
            }

            return urls
        }
        fun String.removeUrls(): String {
            val urlRegex = Regex("(https?|ftp|file)://[-A-Za-z0-9+&@#/%?=~_|!:,.;]*[-A-Za-z0-9+&@#/%=~_|]")
            var counter = 1
            val replacedText = urlRegex.replace(this) {
                val replacement = "[link $counter]"
                counter++
                replacement
            }
            return replacedText
        }

        fun Activity.initialiseStatusBar(){
            window.statusBarColor = ColorUtils.blendARGB(ContextCompat.getColor(this, R.color.green), Color.BLACK, 0.5f)
        }

    }
}