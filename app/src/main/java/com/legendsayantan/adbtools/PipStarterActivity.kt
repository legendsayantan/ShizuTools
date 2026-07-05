package com.legendsayantan.adbtools

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.card.MaterialCardView
import com.legendsayantan.adbtools.lib.Logger.Companion.log
import com.legendsayantan.adbtools.lib.ShizuToolsController
import com.legendsayantan.adbtools.services.ICommandCallback
import java.util.Timer
import kotlin.concurrent.timerTask

class PipStarterActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        log = {
            applicationContext.log(it)
        }
        intent.getStringExtra("package").let { pkg ->
            if (pkg == null) {
                showing = true
                setContentView(R.layout.activity_pip_starter)
                
                androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { _, windowInsets ->
                    val insets = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    findViewById<android.widget.Space>(R.id.insetSpace).minimumHeight = insets.bottom
                    windowInsets
                }
                
                val prefs = getSharedPreferences("universal_pip", Context.MODE_PRIVATE)
                val uses = prefs.getInt("pip_uses_count", 0)
                if (uses < 3) {
                    hideTimerInterval = 6000L
                    prefs.edit().putInt("pip_uses_count", uses + 1).apply()
                } else {
                    hideTimerInterval = 3000L
                }
                
                val controls = listOf<MaterialCardView>(
                    findViewById(R.id.skipPrev),
                    findViewById(R.id.rewind),
                    findViewById(R.id.playPause),
                    findViewById(R.id.forward),
                    findViewById(R.id.skipNext)
                )
                val keys = listOf(
                    arrayOf(KeyEvent.KEYCODE_MEDIA_PREVIOUS),
                    arrayOf(KeyEvent.KEYCODE_MEDIA_REWIND,KeyEvent.KEYCODE_DPAD_LEFT),
                    arrayOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
                    arrayOf(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,KeyEvent.KEYCODE_DPAD_RIGHT),
                    arrayOf(KeyEvent.KEYCODE_MEDIA_NEXT)
                )
                getExternalDisplayId(this) { display->
                    Handler(mainLooper).post {
                        controls.forEachIndexed { index, materialCardView ->
                            materialCardView.setOnClickListener {
                                materialCardView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                keys[index].forEach { key ->
                                    ShizuToolsController.execute { service -> 
                                        service.injectKeyEvent(display, key)
                                    }
                                    interacted()
                                }
                            }
                        }
                    }
                }
                val extraBtns = listOf<MaterialCardView>(
                    findViewById(R.id.adSkipButton),
                    findViewById(R.id.fullScreenButton)
                )
                extraBtns[0].setOnClickListener {
                    extraBtns[0].performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    interacted()
                    val metrics = getWindowParams()
                    getExternalDisplayId(this) { display ->
                        ShizuToolsController.execute { service ->
                            service.injectTap(display, (metrics.first * 0.95).toInt(), (metrics.second*0.86).toInt())
                        }
                    }
                }
                extraBtns[1].setOnClickListener {
                    extraBtns[1].performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    disablePip(this)
                    interacted()
                }

                //outside touch
                findViewById<ConstraintLayout>(R.id.main).setOnClickListener {
                    finish()
                }

                setupAutoHide()
            } else {
                try {
                    startActivity(packageManager.getLaunchIntentForPackage(pkg))
                    playVideo()
                }catch (e:Exception){
                    log(e.stackTraceToString(),true)
                }
                finish()
            }
        }
    }

    private fun setupAutoHide() {
        Timer().schedule(timerTask {
            if (lastInteractionAt >= 0
                && lastInteractionAt + hideTimerInterval < System.currentTimeMillis()
            ) {
                finish()
                cancel()
            }
        }, hideTimerInterval, hideTimerInterval)
    }

    companion object {
        var showing = false
        var hideTimerInterval = 3000L
        var lastInteractionAt = 0L
        var interacted = {
            lastInteractionAt = System.currentTimeMillis()
        }
        var log : (String)->Unit = {}
        fun Context.getWindowParams():Pair<Int,Int>{
            val metrics = resources.displayMetrics
            return Pair(metrics.widthPixels,((metrics.widthPixels / (metrics.heightPixels.toFloat() / metrics.widthPixels)) + 100).toInt())
        }
        
        fun getExternalDisplayId(context: Context, callback:(Int)->Unit){
            ShizuToolsController.execute { service ->
                val listener = object : ICommandCallback.Stub() {
                    override fun onCommandResult(output: String, done: Boolean) {
                        if (done) {
                            val id = "\\d+".toRegex()
                                .findAll(output)
                                .filter { it.value != "0" }
                                .map { it.value.toInt() }
                                .maxOrNull() ?: 0
                            Handler(Looper.getMainLooper()).post { callback(id) }
                        }
                    }
                    override fun onCommandError(error: String) {
                        println(error)
                        Handler(Looper.getMainLooper()).post { 
                            disablePip(context)
                            log(error) 
                        }
                    }
                }
                service.runCommand("dumpsys display | grep 'Display [0-9][0-9]*'", listener, 0)
            }
        }

        fun Context.handlePip() {
            ShizuToolsController.execute { service ->
                val output = service.getGlobalSetting("overlay_display_devices")
                if (output.trim().contains("null", true)) {
                    Handler(Looper.getMainLooper()).post { enablePip() }
                } else {
                    val listener = object : ICommandCallback.Stub() {
                        override fun onCommandResult(output: String, done: Boolean) {}
                        override fun onCommandError(error: String) {
                            Handler(Looper.getMainLooper()).post {
                                disablePip(this@handlePip)
                                println(error)
                                log(error)
                            }
                        }
                    }
                    service.runCommand("am start -n $packageName/${PipStarterActivity::class.java.canonicalName} --display 0", listener, 0)
                }
            }
        }

        fun Context.enablePip() {
            Timer().schedule(timerTask {
                ShizuToolsController.execute { service ->
                    val listener = object : ICommandCallback.Stub() {
                        override fun onCommandResult(output: String, done: Boolean) {
                            if (done) {
                                val split = output.split(" ")
                                if (split.size > 4) {
                                    val pipPackage = split[4].split("/")[0]
                                    val metrics = getWindowParams()
                                    service.putGlobalSetting("overlay_display_devices", "${metrics.first}x${metrics.second}/240")
                                    
                                    Timer().schedule(timerTask {
                                        getExternalDisplayId(this@enablePip) { newDisplayId ->
                                            ShizuToolsController.execute { innerService ->
                                                val innerListener = object : ICommandCallback.Stub() {
                                                    override fun onCommandResult(innerOut: String, innerDone: Boolean) {
                                                        if (innerDone) println("PIP started")
                                                    }
                                                    override fun onCommandError(error: String) {
                                                        Handler(Looper.getMainLooper()).post {
                                                            disablePip(this@enablePip)
                                                            println(error)
                                                            log(error)
                                                        }
                                                    }
                                                }
                                                val command = "am start -n $packageName/${PipStarterActivity::class.java.canonicalName} --es package $pipPackage --display $newDisplayId"
                                                innerService.runCommand(command, innerListener, 0)
                                            }
                                        }
                                    }, 500)
                                }
                            }
                        }
                        override fun onCommandError(error: String) {
                            Handler(Looper.getMainLooper()).post {
                                disablePip(this@enablePip)
                                println(error)
                                log(error)
                            }
                        }
                    }
                    service.runCommand("dumpsys window displays | grep -E 'mCurrentFocus'", listener, 0)
                }
            }, 1500)
        }

        fun disablePip(context: Context) {
            ShizuToolsController.execute { service ->
                service.putGlobalSetting("overlay_display_devices", "null")
                Handler(Looper.getMainLooper()).post { playVideo() }
            }
        }

        fun playVideo() {
            Timer().schedule(timerTask {
                ShizuToolsController.execute { service ->
                    service.injectKeyEvent(0, KeyEvent.KEYCODE_MEDIA_PLAY)
                }
            }, 1500)
        }
    }
}
