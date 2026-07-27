package com.legendsayantan.adbtools

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.legendsayantan.adbtools.lib.Logger.Companion.log
import com.legendsayantan.adbtools.lib.Utils.Companion.showSnackbar
import com.legendsayantan.adbtools.services.SoundMasterService
import com.legendsayantan.adbtools.dialog.SoundMasterBottomSheet

class SoundMasterProjectionActivity : AppCompatActivity() {
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private val MEDIA_PROJECTION_REQUEST_CODE = 13

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Android 14+ offers a "share a single app" option in the consent dialog alongside
        // "entire screen". If a user picks a single app, the resulting MediaProjection is
        // scoped to just that app's UID - AudioPlaybackCaptureConfiguration.addMatchingUid()
        // for every *other* app SoundMaster is trying to control would then silently capture
        // nothing. Forcing the default-display config skips that choice entirely.
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjectionManager.createScreenCaptureIntent(
                android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            mediaProjectionManager.createScreenCaptureIntent()
        }

        startActivityForResult(captureIntent, MEDIA_PROJECTION_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MEDIA_PROJECTION_REQUEST_CODE) {
            val isLiveUpgrade = intent.getBooleanExtra(SoundMasterService.EXTRA_LIVE_UPGRADE, false)
            if (resultCode == Activity.RESULT_OK) {
                SoundMasterService.projectionData = data
                SoundMasterBottomSheet.isMediaProjectionActive = true
                if (isLiveUpgrade && SoundMasterService.running) {
                    // Engine is already running (started in Smart mode) - just wire the
                    // freshly granted projection into it instead of restarting the engine.
                    startService(Intent(this, SoundMasterService::class.java).apply {
                        action = SoundMasterService.ACTION_ENABLE_DSP
                    })
                } else {
                    showSnackbar("SoundMaster Engine Started", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                    startService(Intent(this, SoundMasterService::class.java))
                }
            } else {
                showSnackbar("Request to obtain MediaProjection failed.", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                applicationContext.log("Request to obtain MediaProjection failed.")
                SoundMasterBottomSheet.isMediaProjectionActive = false
                if (isLiveUpgrade && SoundMasterService.running) {
                    startService(Intent(this, SoundMasterService::class.java).apply {
                        action = SoundMasterService.ACTION_DSP_CONSENT_DENIED
                    })
                }
            }
        }
        finish()
    }
}
