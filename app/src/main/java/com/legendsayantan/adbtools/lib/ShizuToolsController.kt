package com.legendsayantan.adbtools.lib

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.legendsayantan.adbtools.services.IShizuToolsService
import com.legendsayantan.adbtools.services.ShizuToolsService
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

object ShizuToolsController {

    // Written from the main thread (ServiceConnection callbacks) and read from arbitrary caller
    // threads plus the executor pool - @Volatile so a disconnect is visible immediately instead
    // of racing with in-flight reads on other threads.
    @Volatile private var service: IShizuToolsService? = null
    @Volatile private var connection: ServiceConnection? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private var activeClients = 0
    private const val IDLE_TIMEOUT_MS = 10000L // Shut down after 10 seconds of inactivity
    
    private val pendingActions = mutableListOf<(IShizuToolsService) -> Unit>()
    private val executor = Executors.newCachedThreadPool()
    
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

        val current = service
        if (current != null && current.asBinder().isBinderAlive) {
            executor.execute {
                // Re-snapshot right before use: `service` can be nulled out by
                // onServiceDisconnected/onBindingDied on the main thread between the check above
                // and this executor task actually running.
                val svc = service
                if (svc == null || !svc.asBinder().isBinderAlive) {
                    // Died mid-flight (Shizuku daemon restart, idle unbind race, etc.) - re-queue
                    // through bind() instead of silently dropping the action or crashing on a
                    // null service. The activeClients slot from this call is only released once,
                    // whichever path ends up actually running the action.
                    bind(action)
                } else {
                    try {
                        action(svc)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        releaseClient()
                    }
                }
            }
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
                executor.execute {
                    actionsToRun.forEach { act ->
                        try {
                            // Re-check per action rather than trusting the service assigned above -
                            // if the binder died partway through this batch, later actions in the
                            // same batch shouldn't blindly run against a dead reference.
                            val svc = service
                            if (svc == null || !svc.asBinder().isBinderAlive) {
                                bind(act)
                            } else {
                                act(svc)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            releaseClient()
                        }
                    }
                }
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
