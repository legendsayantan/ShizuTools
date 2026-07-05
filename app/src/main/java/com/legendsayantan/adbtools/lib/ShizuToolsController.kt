package com.legendsayantan.adbtools.lib

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.legendsayantan.adbtools.services.IShizuToolsService
import com.legendsayantan.adbtools.services.ShizuToolsService
import rikka.shizuku.Shizuku

object ShizuToolsController {
    
    private var service: IShizuToolsService? = null
    private var connection: ServiceConnection? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private var activeClients = 0
    private const val IDLE_TIMEOUT_MS = 10000L // Shut down after 10 seconds of inactivity
    
    private val pendingActions = mutableListOf<(IShizuToolsService) -> Unit>()
    
    private val disconnectRunnable = Runnable {
        if (activeClients == 0 && connection != null) {
            unbind()
        }
    }
    
    private fun getArgs() = Shizuku.UserServiceArgs(
        ComponentName("com.legendsayantan.adbtools", ShizuToolsService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("shizutools")
        .debuggable(true)
        .version(1)

    fun execute(action: (IShizuToolsService) -> Unit) {
        handler.post {
            activeClients++
            handler.removeCallbacks(disconnectRunnable)
        }
        
        if (service != null && service!!.asBinder().isBinderAlive) {
            Thread {
                try {
                    action(service!!)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    releaseClient()
                }
            }.start()
        } else {
            bind(action)
        }
    }
    
    private fun releaseClient() {
        handler.post {
            activeClients--
            if (activeClients == 0) {
                handler.postDelayed(disconnectRunnable, IDLE_TIMEOUT_MS)
            }
        }
    }

    private fun bind(action: (IShizuToolsService) -> Unit) {
        pendingActions.add(action)
        if (connection != null) return // Already binding
        
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = IShizuToolsService.Stub.asInterface(binder)
                val actionsToRun = pendingActions.toList()
                pendingActions.clear()
                Thread {
                    actionsToRun.forEach { act ->
                        try {
                            act(service!!)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            releaseClient()
                        }
                    }
                }.start()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                connection = null
            }
            
            override fun onBindingDied(name: ComponentName?) {
                service = null
                connection = null
            }
        }
        
        try {
            Shizuku.bindUserService(getArgs(), connection!!)
        } catch (e: Exception) {
            e.printStackTrace()
            connection = null
        }
    }

    fun unbind() {
        if (connection != null) {
            try {
                Shizuku.unbindUserService(getArgs(), connection!!, true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            connection = null
            service = null
        }
    }
}
