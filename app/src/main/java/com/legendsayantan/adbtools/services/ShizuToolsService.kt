package com.legendsayantan.adbtools.services

import android.annotation.SuppressLint
import android.os.Process
import android.util.Log

class ShizuToolsService(private val context: android.content.Context) : IShizuToolsService.Stub() {
    
    private val lockedBuckets = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val uidLastActive = mutableMapOf<Int, Long>()

    init {
        try {
            val amBinder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "activity") as android.os.IBinder

            var registerCode = -1
            try {
                val stubClass = Class.forName("android.app.IActivityManager\$Stub")
                registerCode = stubClass.getDeclaredField("TRANSACTION_registerProcessObserver").getInt(null)
            } catch (e: Exception) {
                try {
                    val amClass = Class.forName("android.app.IActivityManager")
                    registerCode = amClass.getDeclaredField("REGISTER_PROCESS_OBSERVER_TRANSACTION").getInt(null)
                } catch (e2: Exception) {}
            }

            if (registerCode != -1) {
                val data = android.os.Parcel.obtain()
                val reply = android.os.Parcel.obtain()
                try {
                    data.writeInterfaceToken("android.app.IActivityManager")
                    data.writeStrongBinder(ShizuProcessObserver())
                    amBinder.transact(registerCode, data, reply, 0)
                    reply.readException()
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            } else {
                Log.e("ShizuToolsService", "Could not find registerProcessObserver transaction code")
            }
        } catch (e: Exception) {
            Log.e("ShizuToolsService", "Error registering process observer: ${e.message}")
        }
    }

    inner class ShizuProcessObserver : android.os.Binder() {
        private var codeFgAct = -1
        
        init {
            try {
                val stubClass = Class.forName("android.app.IProcessObserver\$Stub")
                codeFgAct = stubClass.getDeclaredField("TRANSACTION_onForegroundActivitiesChanged").getInt(null)
            } catch (e: Exception) {}
        }

        override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
            if (codeFgAct != -1 && code == codeFgAct) {
                try {
                    data.enforceInterface("android.app.IProcessObserver")
                    val pid = data.readInt()
                    val uid = data.readInt()
                    val foregroundActivities = data.readInt() != 0
                    
                    if (!foregroundActivities) {
                        val packages = context.packageManager.getPackagesForUid(uid)
                        if (packages != null) {
                            for (pkg in packages) {
                                val targetBucket = lockedBuckets[pkg]
                                if (targetBucket != null) {
                                    val userId = uid / 100000
                                    setAppStandbyBucket(pkg, targetBucket, userId)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    /**
     * Op codes used across this app, mapped to their canonical `appops` CLI names. These
     * numeric codes are AOSP-stable/append-only across Android versions, but we still prefer
     * the symbolic name for the CLI path below since it's the same identifier every `appops get`
     * / `query-op` read call in this app already relies on, and is immune to any doubt about
     * numeric mapping on unfamiliar OS builds.
     */
    private fun opNameFor(opCode: Int): String? = when (opCode) {
        23 -> "WRITE_SETTINGS"
        24 -> "SYSTEM_ALERT_WINDOW"
        27 -> "RECORD_AUDIO"
        28 -> "PLAY_AUDIO"
        32 -> "TAKE_AUDIO_FOCUS"
        46 -> "PROJECT_MEDIA"
        else -> null
    }

    @SuppressLint("BlockedPrivateApi", "DiscouragedPrivateApi")
    override fun setAppOpMode(pkgName: String, uid: Int, opCode: Int, mode: Int) {
        // Prefer the `appops` shell CLI: it's maintained by the platform itself for whatever
        // Android version is actually running, so it stays correct across the app's entire
        // supported range (minSdk 27 through the newest release) without us having to track
        // internal AIDL changes. This is the same tool every read path (`appops get`,
        // `query-op`) in this app already depends on successfully.
        if (setAppOpModeViaCli(pkgName, opCode, mode)) return

        // Fall back to direct Binder reflection only if the CLI path is unavailable/failed
        // (e.g. no shell access in this environment). This reflects a hidden/internal AIDL
        // method whose signature can drift between Android versions, so it's a best-effort
        // secondary path rather than the primary one.
        try {
            val aos = try {
                val aoClz = Class.forName("android.app.AppOpsManager")
                val getAos = aoClz.getDeclaredMethod("getService").apply { isAccessible = true }
                getAos.invoke(null)
            } catch (e: Exception) {
                val b = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, "appops") as android.os.IBinder
                Class.forName("com.android.internal.app.IAppOpsService\$Stub")
                    .getMethod("asInterface", android.os.IBinder::class.java)
                    .invoke(null, b)
            } ?: return

            val setMode = aos.javaClass.getDeclaredMethod(
                "setMode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }

            setMode.invoke(aos, opCode, uid, pkgName, mode)
        } catch (e: Exception) {
            Log.e("ShizuToolsService", "Both appops CLI and reflection setMode failed: ${e.stackTraceToString()}")
        }
    }

    private fun setAppOpModeViaCli(pkgName: String, opCode: Int, mode: Int): Boolean {
        return try {
            val opArg = opNameFor(opCode) ?: opCode.toString()
            val modeName = when (mode) {
                0 -> "allow"
                1 -> "ignore"
                2 -> "deny"
                3 -> "default"
                4 -> "foreground"
                else -> "allow"
            }
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "appops set $pkgName $opArg $modeName"))
            // Drain both streams before waitFor() to avoid a deadlock if the process writes
            // more than the pipe buffer holds.
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            val exit = process.waitFor()
            if (exit != 0) {
                Log.w("ShizuToolsService", "appops set $pkgName $opArg $modeName exited $exit: ${err.ifBlank { out }}")
            }
            exit == 0
        } catch (e: Exception) {
            Log.e("ShizuToolsService", "appops CLI setMode failed: ${e.stackTraceToString()}")
            false
        }
    }

    override fun runCommand(command: String, callback: ICommandCallback, lineBundle: Int) {
        Thread {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
                val err = java.io.BufferedReader(java.io.InputStreamReader(process.errorStream))
                val output = StringBuilder()
                val errordata = StringBuilder()
                var line: String?
                var linecount = 0
                while (reader.readLine().also { line = it } != null) {
                    linecount++
                    output.append(line).append("\n")
                    if (linecount == lineBundle) {
                        linecount = 0
                        callback.onCommandResult(output.toString(), false)
                    }
                }
                while (err.readLine().also { line = it } != null) {
                    errordata.append(line).append("\n")
                }
                if (errordata.isNotBlank()) callback.onCommandError(errordata.toString())
                else callback.onCommandResult(output.toString(), true)
                process.waitFor()
            } catch (e: Exception) {
                callback.onCommandError(e.message ?: "Execution failed")
            }
        }.start()
    }
    
    override fun installApks(apkPaths: List<String>, flags: Int, statusReceiver: android.content.IntentSender) {
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            
            // Set DontKillApp if 0x10000000 is present (custom flag mapping just for API)
            // Wait, we can map dontKill in the UI to an arbitrary flag bit and handle it here.
            // Let's reserve 0x40000000 for DONT_KILL_APP
            if ((flags and 0x40000000) != 0) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION.SDK_INT) {
                    // It's a public method added in API 34, wait, setDontKillApp is API 34.
                    // For older devices, this is only available via reflection on installFlags (INSTALL_DONT_KILL_APP = 0x00001000)
                    val dontKillFlag = 0x00001000
                    try {
                        val installFlagsField = params.javaClass.getDeclaredField("installFlags")
                        installFlagsField.isAccessible = true
                        var currentFlags = installFlagsField.getInt(params)
                        currentFlags = currentFlags or dontKillFlag
                        installFlagsField.setInt(params, currentFlags)
                    } catch (e: Exception) {}
                }
            }
            
            // Mask out our custom flags before setting
            val actualFlags = flags and 0x40000000.inv()
            
            try {
                val installFlagsField = params.javaClass.getDeclaredField("installFlags")
                installFlagsField.isAccessible = true
                var currentFlags = installFlagsField.getInt(params)
                currentFlags = currentFlags or actualFlags
                installFlagsField.setInt(params, currentFlags)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)
            
            for (path in apkPaths) {
                val file = java.io.File(path)
                session.openWrite(file.name, 0, file.length()).use { out ->
                    java.io.FileInputStream(file).use { input ->
                        input.copyTo(out)
                    }
                    session.fsync(out)
                }
            }
            
            session.commit(statusReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("ShizuToolsService", "Error installing apks: ${e.stackTraceToString()}")
        }
    }

    override fun setAppEnabledState(pkgName: String, enabled: Boolean) {
        try {
            val state = if (enabled) {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            }
            context.packageManager.setApplicationEnabledSetting(pkgName, state, 0)
        } catch (e: Exception) {
            Log.e("ShizuToolsService", "Error setting enabled state: ${e.message}")
        }
    }

    override fun setAppHiddenState(pkgName: String, hidden: Boolean) {
        try {
            val method = context.packageManager.javaClass.getMethod(
                "setApplicationHiddenSettingAsUser",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                android.os.UserHandle::class.java
            )
            method.invoke(context.packageManager, pkgName, hidden, android.os.Process.myUserHandle())
        } catch (e: Exception) {
            Log.e("ShizuToolsService", "Error setting hidden state: ${e.message}")
        }
    }

    override fun uninstallApp(pkgName: String, statusReceiver: android.content.IntentSender) {
        try {
            context.packageManager.packageInstaller.uninstall(pkgName, statusReceiver)
        } catch (e: Exception) {
            Log.e("ShizuToolsService", "Error uninstalling app: ${e.message}")
        }
    }

    override fun restoreUninstalledApp(pkgName: String, statusReceiver: android.content.IntentSender) {
        try {
            var success = false
            try {
                val method = context.packageManager.packageInstaller.javaClass.getMethod(
                    "installExistingPackage",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    android.content.IntentSender::class.java
                )
                method.invoke(context.packageManager.packageInstaller, pkgName, android.content.pm.PackageManager.INSTALL_REASON_USER, statusReceiver)
                success = true
            } catch (e: Exception) {}
            
            if (!success) {
                try {
                    val method = context.packageManager.javaClass.getMethod(
                        "installExistingPackageAsUser",
                        String::class.java,
                        Int::class.javaPrimitiveType
                    )
                    val res = method.invoke(context.packageManager, pkgName, android.os.Process.myUserHandle().hashCode()) as Int
                    val intent = android.content.Intent()
                    if (res == 1) {
                        intent.putExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, android.content.pm.PackageInstaller.STATUS_SUCCESS)
                    } else {
                        intent.putExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, android.content.pm.PackageInstaller.STATUS_FAILURE)
                    }
                    statusReceiver.sendIntent(context, 0, intent, null, null)
                    success = true
                } catch(e: Exception){}
            }
            if(!success) {
                val method = context.packageManager.javaClass.getMethod("installExistingPackage", String::class.java)
                val res = method.invoke(context.packageManager, pkgName) as Int
                val intent = android.content.Intent()
                if (res == 1) {
                    intent.putExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, android.content.pm.PackageInstaller.STATUS_SUCCESS)
                } else {
                    intent.putExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, android.content.pm.PackageInstaller.STATUS_FAILURE)
                }
                statusReceiver.sendIntent(context, 0, intent, null, null)
            }
        } catch (e: Exception) {
            Log.e("ShizuToolsService", "Error restoring uninstalled app: ${e.message}")
        }
    }

    override fun getPackageStates(): List<String> {
        val result = mutableListOf<String>()
        try {
            val flags = android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
            val packages = context.packageManager.getInstalledPackages(flags)
            
            for (pkg in packages) {
                val appInfo = pkg.applicationInfo ?: continue
                var state = "INSTALLED"
                
                if (!appInfo.enabled) {
                    state = "DISABLED"
                }
                
                try {
                    val privateFlagsField = android.content.pm.ApplicationInfo::class.java.getField("privateFlags")
                    val privateFlags = privateFlagsField.getInt(appInfo)
                    if ((privateFlags and 1) != 0) {
                        state = "HIDDEN"
                    }
                } catch (e: Exception) {}
                
                // FLAG_INSTALLED is 1<<23 (0x800000)
                if ((appInfo.flags and 0x800000) == 0) {
                    state = "UNINSTALLED"
                }
                
                result.add("${pkg.packageName}:$state")
            }
        } catch (e: Exception) {
            Log.e("ShizuToolsService", "Error getting package states: ${e.message}")
        }
        return result
    }

    override fun getInstalledPackages(userId: Int): List<String> {
        val result = mutableListOf<String>()
        try {
            val packages = if (userId >= 0) {
                try {
                    val method = context.packageManager.javaClass.getMethod("getInstalledPackagesAsUser", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    @Suppress("UNCHECKED_CAST")
                    method.invoke(context.packageManager, 0, userId) as List<android.content.pm.PackageInfo>
                } catch (e: Exception) {
                    context.packageManager.getInstalledPackages(0)
                }
            } else {
                context.packageManager.getInstalledPackages(0)
            }
            
            for (pkg in packages) {
                result.add(pkg.packageName)
            }
        } catch (e: Exception) {
            Log.e("ShizuToolsService", "Error getting installed packages: ${e.message}")
        }
        return result
    }

    override fun grantPermission(pkgName: String, permission: String) {
        try {
            val method = context.packageManager.javaClass.getMethod(
                "grantRuntimePermission", 
                String::class.java, 
                String::class.java, 
                android.os.UserHandle::class.java
            )
            method.invoke(context.packageManager, pkgName, permission, android.os.Process.myUserHandle())
        } catch (e: Exception) {
            Log.e("ShizuToolsService", "Error granting permission $permission to $pkgName: ${e.message}")
        }
    }

    override fun getGlobalSetting(key: String): String {
        return android.provider.Settings.Global.getString(context.contentResolver, key) ?: "null"
    }

    override fun putGlobalSetting(key: String, value: String) {
        try {
            if (value.equals("null", ignoreCase = true)) {
                android.provider.Settings.Global.putString(context.contentResolver, key, null)
            } else {
                android.provider.Settings.Global.putString(context.contentResolver, key, value)
            }
        } catch (e: Exception) {
            Log.e("ShizuToolsService", "Error putting global setting $key: ${e.message}")
        }
    }

    override fun injectKeyEvent(displayId: Int, keyCode: Int) {
        try {
            val im = context.getSystemService(android.content.Context.INPUT_SERVICE) as android.hardware.input.InputManager
            val method = im.javaClass.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
            
            val now = android.os.SystemClock.uptimeMillis()
            val down = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0)
            val up = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0)
            
            try {
                val setDisplayId = android.view.KeyEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                setDisplayId.invoke(down, displayId)
                setDisplayId.invoke(up, displayId)
            } catch (e: Exception) {}
            
            method.invoke(im, down, 0)
            method.invoke(im, up, 0)
        } catch (e: Exception) {
            Log.e("ShizuToolsService", "Error injecting key: ${e.message}")
        }
    }

    override fun injectTap(displayId: Int, x: Int, y: Int) {
        try {
            val im = context.getSystemService(android.content.Context.INPUT_SERVICE) as android.hardware.input.InputManager
            val method = im.javaClass.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
            
            val now = android.os.SystemClock.uptimeMillis()
            val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
            val up = android.view.MotionEvent.obtain(now, now + 10, android.view.MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
            
            down.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            up.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            
            try {
                val setDisplayId = android.view.MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                setDisplayId.invoke(down, displayId)
                setDisplayId.invoke(up, displayId)
            } catch (e: Exception) {}
            
            method.invoke(im, down, 0)
            method.invoke(im, up, 0)
            
            down.recycle()
            up.recycle()
        } catch (e: Exception) {
            Log.e("ShizuToolsService", "Error injecting tap: ${e.message}")
        }
    }

    override fun setAppStandbyBucket(packageName: String, bucketIndex: Int, userId: Int) {
        try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "usagestats") as android.os.IBinder
            
            val stub = Class.forName("android.app.usage.IUsageStatsManager\$Stub")
            val usageStatsManager = stub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
            
            val method = usageStatsManager.javaClass.getMethod(
                "setAppStandbyBucket", 
                String::class.java, 
                Int::class.javaPrimitiveType, 
                Int::class.javaPrimitiveType
            )
            method.invoke(usageStatsManager, packageName, bucketIndex, userId)
        } catch (e: Exception) {
            Log.e("ShizuToolsService", "Error setting standby bucket: ${e.message}")
        }
    }

    override fun getAppStandbyBucket(packageName: String, userId: Int): Int {
        try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "usagestats") as android.os.IBinder
            
            val stub = Class.forName("android.app.usage.IUsageStatsManager\$Stub")
            val usageStatsManager = stub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
            
            val method = usageStatsManager.javaClass.getMethod(
                "getAppStandbyBucket", 
                String::class.java, 
                String::class.java, 
                Int::class.javaPrimitiveType
            )
            return method.invoke(usageStatsManager, packageName, packageName, userId) as Int
        } catch (e: Exception) {
            Log.e("ShizuToolsService", "Error getting standby bucket: ${e.message}")
        }
        return -1
    }

    override fun setBucketLock(packageName: String, bucket: Int, locked: Boolean) {
        if (locked) {
            lockedBuckets[packageName] = bucket
        } else {
            lockedBuckets.remove(packageName)
        }
    }
    
    // Virtual Mount Filesystem Operations
    override fun listDirectory(absolutePath: String): android.os.ParcelFileDescriptor? {
        try {
            val fds = android.os.ParcelFileDescriptor.createPipe()
            val readFd = fds[0]
            val writeFd = fds[1]
            
            Thread {
                try {
                    android.os.ParcelFileDescriptor.AutoCloseOutputStream(writeFd).use { stream ->
                        val file = java.io.File(absolutePath)
                        val files = file.listFiles()
                        if (files != null) {
                            val writer = java.io.OutputStreamWriter(stream, "UTF-8")
                            for (child in files) {
                                val name = child.name
                                val size = child.length()
                                val lastModified = child.lastModified()
                                val isDir = child.isDirectory
                                val encodedName = name.replace("|", "%7C").replace("\n", "%0A")
                                writer.write("$encodedName|$size|$lastModified|$isDir\n")
                            }
                            writer.flush()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()
            
            return readFd
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override fun openFile(absolutePath: String, mode: String): android.os.ParcelFileDescriptor? {
        try {
            val file = java.io.File(absolutePath)
            val modeInt = when (mode) {
                "r" -> android.os.ParcelFileDescriptor.MODE_READ_ONLY
                "w" -> android.os.ParcelFileDescriptor.MODE_WRITE_ONLY or android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_TRUNCATE
                "wa" -> android.os.ParcelFileDescriptor.MODE_WRITE_ONLY or android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_APPEND
                "rw" -> android.os.ParcelFileDescriptor.MODE_READ_WRITE or android.os.ParcelFileDescriptor.MODE_CREATE
                "rwt" -> android.os.ParcelFileDescriptor.MODE_READ_WRITE or android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_TRUNCATE
                else -> android.os.ParcelFileDescriptor.MODE_READ_ONLY
            }
            return android.os.ParcelFileDescriptor.open(file, modeInt)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override fun deletePath(absolutePath: String): Boolean {
        return try {
            val file = java.io.File(absolutePath)
            if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun createDocument(parentPath: String, mimeType: String, displayName: String): String? {
        try {
            val parent = java.io.File(parentPath)
            val newFile = java.io.File(parent, displayName)
            if (mimeType == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                if (newFile.mkdirs() || newFile.exists()) {
                    return newFile.absolutePath
                }
            } else {
                if (newFile.createNewFile() || newFile.exists()) {
                    return newFile.absolutePath
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    @SuppressLint("BlockedPrivateApi", "DiscouragedPrivateApi")
    override fun setPlayerVolume(uid: Int, volume: Float) {
        try {
            val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            val configs = audioManager.activePlaybackConfigurations
            for (config in configs) {
                try {
                    val getClientUid = config.javaClass.getDeclaredMethod("getClientUid")
                    getClientUid.isAccessible = true
                    val cUid = getClientUid.invoke(config) as Int
                    if (cUid == uid) {
                        val getPlayerProxy = config.javaClass.getDeclaredMethod("getPlayerProxy")
                        getPlayerProxy.isAccessible = true
                        val proxy = getPlayerProxy.invoke(config)
                        if (proxy != null) {
                            val setVolume = proxy.javaClass.getDeclaredMethod("setVolume", Float::class.javaPrimitiveType)
                            setVolume.isAccessible = true
                            setVolume.invoke(proxy, volume)
                        }
                    }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            Log.e("ShizuToolsService", "Error setting player volume: ${e.message}")
        }
    }

    @SuppressLint("BlockedPrivateApi", "DiscouragedPrivateApi")
    override fun getActiveAudioUids(): IntArray {
        val uids = mutableSetOf<Int>()
        val currentTime = System.currentTimeMillis()
        try {
            val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            val configs = audioManager.activePlaybackConfigurations
            for (config in configs) {
                try {
                    val getClientUid = config.javaClass.getDeclaredMethod("getClientUid")
                    getClientUid.isAccessible = true
                    val uid = getClientUid.invoke(config) as Int
                    val isActuallyPlaying = try {
                        val getStateMethod = config.javaClass.getDeclaredMethod("getPlayerState")
                        getStateMethod.isAccessible = true
                        val state = getStateMethod.invoke(config) as Int
                        state == 2 // AudioPlaybackConfiguration.PLAYER_STATE_STARTED = 2
                    } catch (e: Exception) {
                        false
                    }
                    if (uid > 0 && isActuallyPlaying) {
                        uidLastActive[uid] = currentTime
                    }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            Log.e("ShizuToolsService", "Error getting active audio UIDs: ${e.message}")
        }
        uidLastActive.entries.removeIf { currentTime - it.value > 30_000 }
        uids.addAll(uidLastActive.keys)
        return uids.toIntArray()
    }
}
