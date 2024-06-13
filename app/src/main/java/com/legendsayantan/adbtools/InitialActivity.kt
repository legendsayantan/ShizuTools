package com.legendsayantan.adbtools

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.legendsayantan.adbtools.lib.GradleUpdate
import com.legendsayantan.adbtools.lib.Utils.Companion.initialiseNotiChannel
import com.legendsayantan.adbtools.lib.Utils.Companion.initialiseStatusBar
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.OnRequestPermissionResultListener

/**
 * @author legendsayantan
 */
class InitialActivity : AppCompatActivity() {
    val REQUEST_CODE = 123;

    private val requestPermissionResultListener =
        OnRequestPermissionResultListener { requestCode: Int, grantResult: Int ->
            this.onRequestPermissionsResult(
                requestCode,
                grantResult
            )
        }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_initial)
        initialiseStatusBar()
        initialiseNotiChannel()
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
        try {
            if(!checkPermission()) Shizuku.requestPermission(REQUEST_CODE)
        }catch (_:Exception){}

        findViewById<TextView>(R.id.textView).setOnClickListener {
            val shizukuUrl = "https://shizuku.rikka.app/"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(shizukuUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        GradleUpdate(
            applicationContext,
            "https://cdn.jsdelivr.net/gh/legendsayantan/ShizuTools@master/app/build.gradle",
            86400000
        ).checkAndNotify("https://github.com/legendsayantan/ShizuTools/releases/latest",R.drawable.baseline_file_download_24)
    }

    override fun onResume() {
        super.onResume()
        try {
            if(checkPermission()) onGranted()
        }catch (_:Exception){}
    }
    private fun onRequestPermissionsResult(requestCode: Int, grantResult: Int) {
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        if(REQUEST_CODE==requestCode && granted) onGranted()
    }



    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
        super.onDestroy()
    }

    private fun checkPermission(): Boolean {
            if (Shizuku.isPreV11()) {
                // Pre-v11 is unsupported
                return false
            }
        return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            // Granted
            true
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            // Users choose "Deny and don't ask again"
            false
        } else {
            false
        }
    }
    private fun onGranted() {
        Intent(this, MainActivity::class.java).also {
            startActivity(it)
            finish()
        }
    }
}