package com.legendsayantan.adbtools.data

import android.os.Build
import android.os.Process

/**
 * Canonical App Standby Bucket table, shared between the StandbyBucket UI (branded "Governor" in
 * strings.xml) and the app-command dispatcher so both agree on the same codes/names/API gating.
 */
object StandbyBuckets {
    // STANDBY_BUCKET_EXEMPTED (5) needs API 29+, STANDBY_BUCKET_RESTRICTED (45) needs API 30+ -
    // the rest are valid from API 28 (when app standby buckets were introduced) onward.
    val allValues = intArrayOf(5, 10, 20, 30, 40, 45, 50)
    val allDisplayNames = arrayOf("Fully Exempted", "Always Active", "Working Set", "Frequently Used", "Rarely Used", "Strictly Restricted", "Never Used")

    /** Command-friendly snake_case keys, in the same order as allValues/allDisplayNames. */
    private val commandNames = arrayOf("exempted", "active", "working_set", "frequent", "rare", "restricted", "never")

    fun isSupportedOnThisDevice(code: Int): Boolean = when (code) {
        5 -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        45 -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        else -> true
    }

    /** Values/names filtered to what this device's API level actually supports, for pickers. */
    fun supportedValues(): IntArray = allValues.filter { isSupportedOnThisDevice(it) }.toIntArray()
    fun supportedDisplayNames(): Array<String> =
        allValues.indices.filter { isSupportedOnThisDevice(allValues[it]) }.map { allDisplayNames[it] }.toTypedArray()

    fun displayName(code: Int): String {
        val idx = allValues.indexOf(code)
        return if (idx >= 0) allDisplayNames[idx] else "Unknown ($code)"
    }

    /** Resolves a command's bucket-name argument (e.g. "rare") to its bucket code, only if it's
     *  supported on this device's API level. */
    fun codeForCommandName(name: String): Int? {
        val idx = commandNames.indexOf(name.lowercase())
        if (idx < 0) return null
        val code = allValues[idx]
        return if (isSupportedOnThisDevice(code)) code else null
    }

    fun commandNameFor(code: Int): String {
        val idx = allValues.indexOf(code)
        return if (idx >= 0) commandNames[idx] else code.toString()
    }

    /** AOSP's UID numbering is userId*100000 + appId - more reliable across OEMs than relying on
     *  UserHandle.hashCode() returning the user ID, which is an implementation detail, not a
     *  documented contract. */
    fun currentUserId(): Int = Process.myUid() / 100000
}
