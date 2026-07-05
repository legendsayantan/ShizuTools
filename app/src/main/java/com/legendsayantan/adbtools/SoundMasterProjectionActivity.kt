package com.legendsayantan.adbtools

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
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
        
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            MEDIA_PROJECTION_REQUEST_CODE
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MEDIA_PROJECTION_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                showSnackbar("SoundMaster Engine Started", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                SoundMasterService.projectionData = data
                startService(Intent(this, SoundMasterService::class.java))
                SoundMasterBottomSheet.isMediaProjectionActive = true
            } else {
                showSnackbar("Request to obtain MediaProjection failed.", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                applicationContext.log("Request to obtain MediaProjection failed.")
                SoundMasterBottomSheet.isMediaProjectionActive = false
            }
        }
        finish()
    }
}
