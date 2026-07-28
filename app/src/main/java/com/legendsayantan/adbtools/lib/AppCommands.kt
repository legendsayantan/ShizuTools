package com.legendsayantan.adbtools.lib

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.os.Build
import com.legendsayantan.adbtools.data.AudioOutputBase
import com.legendsayantan.adbtools.data.AudioOutputKey
import com.legendsayantan.adbtools.data.StandbyBuckets
import com.legendsayantan.adbtools.services.SoundMasterService

/**
 * Structured, shell-like commands ("soundmaster start dsp", "mixedaudio mute <pkg>", "governor
 * set <pkg> rare") layered on top of the raw-shell Intent Shell / Local Shell channels, letting
 * external automation and the in-app terminal control specific app features directly instead of
 * shelling out. Wired in from IntentReceiver.kt and LocalShellBottomSheet.kt - check
 * isAppCommand() first, and only call dispatch() if it returns true; anything else should still
 * go through the normal raw-shell path unchanged.
 */
object AppCommands {
    private val FEATURES = setOf("soundmaster", "mixedaudio", "governor", "help")

    fun isAppCommand(line: String): Boolean {
        val first = line.trim().substringBefore(' ').lowercase()
        return first in FEATURES
    }

    fun dispatch(context: Context, line: String, callback: (success: Boolean, message: String) -> Unit) {
        val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) {
            callback(false, "Empty command.")
            return
        }
        when (tokens[0].lowercase()) {
            "soundmaster" -> handleSoundMaster(context, tokens.drop(1), callback)
            "mixedaudio" -> handleMixedAudio(context, tokens.drop(1), callback)
            "governor" -> handleGovernor(context, tokens.drop(1), callback)
            "help" -> callback(true, fullHelp())
            else -> callback(false, "Unknown command '${tokens[0]}'. Try 'help'.")
        }
    }

    /** Full command reference text - used both for the top-level "help" command and by
     *  IntentShellBottomSheet's static reference section. */
    fun fullHelp() = listOf(soundMasterHelp(), mixedAudioHelp(), governorHelp()).joinToString("\n\n")

    private fun resolveUid(context: Context, pkg: String): Int? = try {
        context.packageManager.getApplicationInfo(pkg, 0).uid
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** Validates the package exists before running block(uid); otherwise reports a clear error
     *  instead of failing deeper inside a Shizuku call. */
    private fun requirePkg(context: Context, pkg: String?, callback: (Boolean, String) -> Unit, block: (uid: Int) -> Unit) {
        if (pkg.isNullOrBlank()) {
            callback(false, "A package name is required.")
            return
        }
        val uid = resolveUid(context, pkg)
        if (uid == null) {
            callback(false, "Package not found: $pkg")
            return
        }
        block(uid)
    }

    // ---------------- SoundMaster ----------------

    private fun soundMasterHelp() = """
        soundmaster start [smart|dsp]
        soundmaster stop
        soundmaster mode <smart|dsp>
        soundmaster status
        soundmaster volume <pkg> <0-150>
        soundmaster devices
        soundmaster device <pkg> switch <deviceId|default> [<toDeviceId|default>]
        soundmaster device <pkg> add <deviceId|default> [volume]
        soundmaster device <pkg> remove <deviceId|default>
    """.trimIndent()

    private fun handleSoundMaster(context: Context, args: List<String>, callback: (Boolean, String) -> Unit) {
        if (args.isEmpty()) {
            callback(false, soundMasterHelp())
            return
        }
        when (args[0].lowercase()) {
            "help" -> callback(true, soundMasterHelp())
            "status" -> {
                val running = SoundMasterService.running
                val mode = if (SoundMasterPreferences.isAdvancedDspMode(context)) "dsp" else "smart"
                callback(true, "SoundMaster ${if (running) "running" else "stopped"} (mode: $mode)")
            }
            "stop" -> {
                context.stopService(Intent(context, SoundMasterService::class.java))
                callback(true, "SoundMaster stopped.")
            }
            "start" -> {
                val modeArg = args.getOrNull(1)?.lowercase()
                val isDsp = when (modeArg) {
                    "dsp" -> true
                    "smart" -> false
                    null -> SoundMasterPreferences.isAdvancedDspMode(context)
                    else -> {
                        callback(false, "Unknown mode '$modeArg'. Use 'smart' or 'dsp'.")
                        return
                    }
                }
                if (modeArg != null) SoundMasterPreferences.setAdvancedDspMode(context, isDsp)
                if (SoundMasterService.running) {
                    callback(false, "SoundMaster is already running.")
                    return
                }
                SoundMasterService.startEngine(context.applicationContext, isDsp, callback)
            }
            "mode" -> {
                val modeArg = args.getOrNull(1)?.lowercase()
                val isDsp = when (modeArg) {
                    "dsp" -> true
                    "smart" -> false
                    else -> {
                        callback(false, "Usage: soundmaster mode <smart|dsp>")
                        return
                    }
                }
                SoundMasterPreferences.setAdvancedDspMode(context, isDsp)
                if (SoundMasterService.running) {
                    context.startService(Intent(context, SoundMasterService::class.java).apply {
                        action = if (isDsp) SoundMasterService.ACTION_ENABLE_DSP else SoundMasterService.ACTION_ENABLE_SMART
                    })
                    callback(true, "Switching to $modeArg mode." + if (isDsp) " (on-screen consent may be required)" else "")
                } else {
                    callback(true, "Mode preference set to $modeArg for next start.")
                }
            }
            "volume" -> handleVolume(context, args.drop(1), callback)
            "devices" -> handleListDevices(callback)
            "device" -> handleDevice(context, args.drop(1), callback)
            else -> callback(false, "Unknown soundmaster action '${args[0]}'.\n${soundMasterHelp()}")
        }
    }

    private fun handleVolume(context: Context, args: List<String>, callback: (Boolean, String) -> Unit) {
        val pkg = args.getOrNull(0)
        val level = args.getOrNull(1)?.toFloatOrNull()
        if (pkg == null || level == null || level < 0f || level > 150f) {
            callback(false, "Usage: soundmaster volume <pkg> <0-150>")
            return
        }
        requirePkg(context, pkg, callback) { uid ->
            // Persisted regardless of current state, matching how SoundMasterService.updateActivePackages
            // reads this same prefs entry whenever the package is (re)attached.
            context.getSharedPreferences("soundmaster_vols", Context.MODE_PRIVATE).edit().putFloat(pkg, level / 100f).apply()

            if (!SoundMasterService.running) {
                callback(true, "Volume preference saved for $pkg (engine not running).")
                return@requirePkg
            }
            if (SoundMasterPreferences.isAdvancedDspMode(context)) {
                val attached = SoundMasterService.apps.firstOrNull { it.pkg == pkg }
                if (attached == null) {
                    callback(true, "Volume preference saved for $pkg (not currently attached in DSP mode).")
                    return@requirePkg
                }
                SoundMasterService.setVolumeOf(AudioOutputKey(pkg, attached.output), level)
                callback(true, "Volume for $pkg set to $level.")
            } else {
                if (!SoundMasterService.activePackages.contains(pkg)) {
                    callback(true, "Volume preference saved for $pkg (not currently active).")
                    return@requirePkg
                }
                ShizuToolsController.execute { service ->
                    try {
                        service.setPlayerVolume(uid, level / 100f)
                        callback(true, "Volume for $pkg set to $level.")
                    } catch (e: Exception) {
                        callback(false, "Failed to set live volume: ${e.message}")
                    }
                }
            }
        }
    }

    private fun handleListDevices(callback: (Boolean, String) -> Unit) {
        if (!SoundMasterService.running || SoundMasterService.getAudioDevices().isEmpty()) {
            callback(false, "Device management requires the engine running in DSP mode.")
            return
        }
        val lines = SoundMasterService.getAudioDevices().map { d ->
            if (d == null) "default -> Default" else "${d.id} -> ${d.productName} (${AudioOutputMap.getName(d.type)})"
        }
        callback(true, lines.joinToString("\n"))
    }

    /** Resolves a device-id argument ("default" or a numeric id) against the currently available
     *  output devices. The Boolean says whether it resolved at all; the device is null both for
     *  "default" and for "not found", so always check the Boolean first. */
    private fun resolveDeviceArg(arg: String?): Pair<Boolean, AudioDeviceInfo?> {
        if (arg == null) return false to null
        if (arg.equals("default", ignoreCase = true)) return true to null
        val id = arg.toIntOrNull() ?: return false to null
        val match = SoundMasterService.getAudioDevices().filterNotNull().find { it.id == id }
        return if (match != null) true to match else false to null
    }

    private fun handleDevice(context: Context, args: List<String>, callback: (Boolean, String) -> Unit) {
        if (!SoundMasterService.running || !SoundMasterPreferences.isAdvancedDspMode(context)) {
            callback(false, "Device management requires the engine running in DSP mode.")
            return
        }
        val pkg = args.getOrNull(0)
        val action = args.getOrNull(1)?.lowercase()
        if (pkg == null || action == null) {
            callback(false, "Usage: soundmaster device <pkg> <switch|add|remove> ...")
            return
        }

        requirePkg(context, pkg, callback) { _ ->
            val currentOutputs = SoundMasterService.apps.filter { it.pkg == pkg }.map { it.output }
            when (action) {
                "add" -> {
                    val (found, device) = resolveDeviceArg(args.getOrNull(2))
                    if (!found) {
                        callback(false, "Unknown device '${args.getOrNull(2)}'. Use 'soundmaster devices' to list them.")
                        return@requirePkg
                    }
                    val outputKey = device?.id ?: -1
                    if (currentOutputs.contains(outputKey)) {
                        callback(false, "$pkg already outputs to that device.")
                        return@requirePkg
                    }
                    val volume = args.getOrNull(3)?.toFloatOrNull() ?: 100f
                    val key = AudioOutputKey(pkg, outputKey)
                    if (!SoundMasterService.isAttachable(key)) {
                        callback(false, "$pkg cannot be attached to that device right now.")
                        return@requirePkg
                    }
                    SoundMasterService.onDynamicAttach(AudioOutputBase(pkg, outputKey, volume), device)
                    callback(true, "Added output for $pkg on device ${args.getOrNull(2)}.")
                }
                "switch" -> {
                    val fromId: Int
                    val toArg: String?
                    if (args.size >= 4) {
                        // soundmaster device <pkg> switch <fromDeviceId|default> <toDeviceId|default>
                        val parsedFrom = if (args[2].equals("default", true)) -1 else args[2].toIntOrNull()
                        if (parsedFrom == null || !currentOutputs.contains(parsedFrom)) {
                            callback(false, "$pkg has no current output matching '${args[2]}'.")
                            return@requirePkg
                        }
                        fromId = parsedFrom
                        toArg = args.getOrNull(3)
                    } else if (currentOutputs.size == 1) {
                        fromId = currentOutputs[0]
                        toArg = args.getOrNull(2)
                    } else {
                        callback(false, "$pkg has ${currentOutputs.size} outputs; specify: soundmaster device $pkg switch <fromDeviceId> <toDeviceId>")
                        return@requirePkg
                    }
                    val (found, toDevice) = resolveDeviceArg(toArg)
                    if (!found) {
                        callback(false, "Unknown device '$toArg'. Use 'soundmaster devices' to list them.")
                        return@requirePkg
                    }
                    val moved = SoundMasterService.switchDeviceFor(AudioOutputKey(pkg, fromId), toDevice)
                    callback(moved, if (moved) "Switched $pkg to device $toArg." else "Could not switch - target device may already be in use.")
                }
                "remove" -> {
                    val idArg = args.getOrNull(2)
                    val id = if (idArg.equals("default", true)) -1 else idArg?.toIntOrNull()
                    if (id == null || !currentOutputs.contains(id)) {
                        callback(false, "$pkg has no current output matching '$idArg'.")
                        return@requirePkg
                    }
                    SoundMasterService.onDynamicDetach(AudioOutputKey(pkg, id))
                    callback(true, "Removed $pkg's output on device $idArg.")
                }
                else -> callback(false, "Unknown device action '$action'. Use switch, add, or remove.")
            }
        }
    }

    // ---------------- MixedAudio ----------------

    private fun mixedAudioHelp() = """
        mixedaudio mute <pkg>
        mixedaudio unmute <pkg>
        mixedaudio focus <pkg> <default|ignore|deny>
        mixedaudio restore <pkg>
        mixedaudio restoreall
        mixedaudio status <pkg>
    """.trimIndent()

    private fun handleMixedAudio(context: Context, args: List<String>, callback: (Boolean, String) -> Unit) {
        if (args.isEmpty()) {
            callback(false, mixedAudioHelp())
            return
        }
        when (args[0].lowercase()) {
            "help" -> callback(true, mixedAudioHelp())
            "mute" -> setPlayAudio(context, args.getOrNull(1), true, callback)
            "unmute" -> setPlayAudio(context, args.getOrNull(1), false, callback)
            "focus" -> {
                val pkg = args.getOrNull(1)
                val modeArg = args.getOrNull(2)?.lowercase()
                val mode = when (modeArg) {
                    "default" -> AppOps.MODE_ALLOWED
                    "ignore" -> AppOps.MODE_IGNORED
                    "deny" -> AppOps.MODE_ERRORED
                    else -> null
                }
                if (pkg == null || mode == null) {
                    callback(false, "Usage: mixedaudio focus <pkg> <default|ignore|deny>")
                    return
                }
                requirePkg(context, pkg, callback) { uid ->
                    ShizuToolsController.execute { service ->
                        try {
                            service.setAppOpMode(pkg, uid, AppOps.TAKE_AUDIO_FOCUS, mode)
                            callback(true, "Set audio-focus mode for $pkg to $modeArg.")
                        } catch (e: Exception) {
                            callback(false, "Failed: ${e.message}")
                        }
                    }
                }
            }
            "restore" -> {
                val pkg = args.getOrNull(1)
                if (pkg == null) {
                    callback(false, "Usage: mixedaudio restore <pkg>")
                    return
                }
                requirePkg(context, pkg, callback) { uid ->
                    ShizuToolsController.execute { service ->
                        try {
                            service.setAppOpMode(pkg, uid, AppOps.PLAY_AUDIO, AppOps.MODE_ALLOWED)
                            service.setAppOpMode(pkg, uid, AppOps.TAKE_AUDIO_FOCUS, AppOps.MODE_ALLOWED)
                            callback(true, "Restored $pkg to default.")
                        } catch (e: Exception) {
                            callback(false, "Failed: ${e.message}")
                        }
                    }
                }
            }
            "restoreall" -> {
                ShizuToolsController.execute { service ->
                    try {
                        val pkgs = service.queryAppOpStates(intArrayOf(AppOps.PLAY_AUDIO, AppOps.TAKE_AUDIO_FOCUS))
                            .split("\n")
                            .mapNotNull { line -> line.split("|").getOrNull(0)?.takeIf { it.isNotBlank() } }
                            .distinct()
                        var count = 0
                        pkgs.forEach { pkg ->
                            val uid = resolveUid(context, pkg) ?: return@forEach
                            try {
                                service.setAppOpMode(pkg, uid, AppOps.PLAY_AUDIO, AppOps.MODE_ALLOWED)
                                service.setAppOpMode(pkg, uid, AppOps.TAKE_AUDIO_FOCUS, AppOps.MODE_ALLOWED)
                                count++
                            } catch (e: Exception) {
                            }
                        }
                        callback(true, "Restored $count app(s) to default.")
                    } catch (e: Exception) {
                        callback(false, "Failed to query app-op states: ${e.message}")
                    }
                }
            }
            "status" -> {
                val pkg = args.getOrNull(1)
                if (pkg == null) {
                    callback(false, "Usage: mixedaudio status <pkg>")
                    return
                }
                requirePkg(context, pkg, callback) { uid ->
                    ShizuToolsController.execute { service ->
                        try {
                            val muteMode = service.getAppOpMode(pkg, uid, AppOps.PLAY_AUDIO)
                            val focusMode = service.getAppOpMode(pkg, uid, AppOps.TAKE_AUDIO_FOCUS)
                            val muted = muteMode == AppOps.MODE_ERRORED
                            val focusName = when (focusMode) {
                                AppOps.MODE_IGNORED -> "ignore"
                                AppOps.MODE_ERRORED -> "deny"
                                else -> "default"
                            }
                            callback(true, "$pkg: muted=$muted, focus=$focusName")
                        } catch (e: Exception) {
                            callback(false, "Failed: ${e.message}")
                        }
                    }
                }
            }
            else -> callback(false, "Unknown mixedaudio action '${args[0]}'.\n${mixedAudioHelp()}")
        }
    }

    private fun setPlayAudio(context: Context, pkg: String?, mute: Boolean, callback: (Boolean, String) -> Unit) {
        if (pkg == null) {
            callback(false, "Usage: mixedaudio ${if (mute) "mute" else "unmute"} <pkg>")
            return
        }
        requirePkg(context, pkg, callback) { uid ->
            ShizuToolsController.execute { service ->
                try {
                    service.setAppOpMode(pkg, uid, AppOps.PLAY_AUDIO, if (mute) AppOps.MODE_ERRORED else AppOps.MODE_ALLOWED)
                    callback(true, "${if (mute) "Muted" else "Unmuted"} $pkg.")
                } catch (e: Exception) {
                    callback(false, "Failed: ${e.message}")
                }
            }
        }
    }

    // ---------------- Governor (Standby Bucket) ----------------

    private fun governorHelp() = """
        governor set <pkg> <exempted|active|working_set|frequent|rare|restricted|never>
        governor lock <pkg>
        governor unlock <pkg>
        governor status <pkg>
    """.trimIndent()

    private fun handleGovernor(context: Context, args: List<String>, callback: (Boolean, String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            callback(false, "Standby Buckets require Android 9 or higher.")
            return
        }
        if (args.isEmpty()) {
            callback(false, governorHelp())
            return
        }
        val prefs = context.getSharedPreferences("locked_buckets_prefs", Context.MODE_PRIVATE)
        when (args[0].lowercase()) {
            "help" -> callback(true, governorHelp())
            "set" -> {
                val pkg = args.getOrNull(1)
                val bucketArg = args.getOrNull(2)
                val code = bucketArg?.let { StandbyBuckets.codeForCommandName(it) }
                if (pkg == null || code == null) {
                    callback(false, "Usage: governor set <pkg> <exempted|active|working_set|frequent|rare|restricted|never>")
                    return
                }
                requirePkg(context, pkg, callback) { _ ->
                    ShizuToolsController.execute { service ->
                        try {
                            service.setAppStandbyBucket(pkg, code, StandbyBuckets.currentUserId())
                            if (prefs.contains(pkg)) prefs.edit().putInt(pkg, code).apply()
                            callback(true, "Set $pkg's bucket to $bucketArg.")
                        } catch (e: Exception) {
                            callback(false, "Failed: ${e.message}")
                        }
                    }
                }
            }
            "lock", "unlock" -> {
                val locked = args[0].lowercase() == "lock"
                val pkg = args.getOrNull(1)
                if (pkg == null) {
                    callback(false, "Usage: governor ${args[0]} <pkg>")
                    return
                }
                requirePkg(context, pkg, callback) { _ ->
                    ShizuToolsController.execute { service ->
                        try {
                            val bucket = service.getAppStandbyBucket(pkg, StandbyBuckets.currentUserId())
                            service.setBucketLock(pkg, bucket, locked)
                            if (locked) prefs.edit().putInt(pkg, bucket).apply() else prefs.edit().remove(pkg).apply()
                            callback(true, "${if (locked) "Locked" else "Unlocked"} $pkg at ${StandbyBuckets.commandNameFor(bucket)}.")
                        } catch (e: Exception) {
                            callback(false, "Failed: ${e.message}")
                        }
                    }
                }
            }
            "status" -> {
                val pkg = args.getOrNull(1)
                if (pkg == null) {
                    callback(false, "Usage: governor status <pkg>")
                    return
                }
                requirePkg(context, pkg, callback) { _ ->
                    ShizuToolsController.execute { service ->
                        try {
                            val bucket = service.getAppStandbyBucket(pkg, StandbyBuckets.currentUserId())
                            val locked = prefs.contains(pkg)
                            callback(true, "$pkg: bucket=${StandbyBuckets.commandNameFor(bucket)}, locked=$locked")
                        } catch (e: Exception) {
                            callback(false, "Failed: ${e.message}")
                        }
                    }
                }
            }
            else -> callback(false, "Unknown governor action '${args[0]}'.\n${governorHelp()}")
        }
    }
}
