package com.legendsayantan.adbtools.lib

/**
 * AppOps op-code constants used across this app's audio-routing features. These are the same
 * literal op indices ShizuToolsService.opNameFor() maps to symbolic names for its reflection/CLI
 * resolution - kept here too as one shared reference instead of duplicating the magic numbers at
 * every call site.
 */
object AppOps {
    const val SYSTEM_ALERT_WINDOW = 24
    const val RECORD_AUDIO = 27
    const val PLAY_AUDIO = 28
    const val TAKE_AUDIO_FOCUS = 32
    const val PROJECT_MEDIA = 46

    // AppOpsManager.MODE_* - shared naming for the mode ints passed to setAppOpMode/getAppOpMode.
    const val MODE_ALLOWED = 0
    const val MODE_IGNORED = 1
    const val MODE_ERRORED = 2 // "deny"
    const val MODE_DEFAULT = 3
    const val MODE_FOREGROUND = 4
}
