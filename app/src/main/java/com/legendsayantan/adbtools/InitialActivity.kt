package com.legendsayantan.adbtools

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.legendsayantan.adbtools.lib.GradleUpdate
import com.legendsayantan.adbtools.lib.Utils.Companion.initialiseStatusBar
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.OnRequestPermissionResultListener


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

        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
        try {
            if(!checkPermission(REQUEST_CODE)) Shizuku.requestPermission(REQUEST_CODE)
        }catch (e:Exception){}

        findViewById<TextView>(R.id.textView).setOnClickListener {
            val shizukuUrl = "https://shizuku.rikka.app/"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(shizukuUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        GradleUpdate(
            applicationContext,
            "https://cdn.jsdelivr.net/gh/legendsayantan/ShizuTools@master/app/build.gradle",
            86400000
        ).checkAndNotify("https://github.com/legendsayantan/ShizuTools/raw/master/app/release/app-release.apk",R.drawable.baseline_file_download_24)
    }

    override fun onResume() {
        super.onResume()
        try {
            if(checkPermission(REQUEST_CODE)) onGranted()
        }catch (e:Exception){}
    }
    private fun onRequestPermissionsResult(requestCode: Int, grantResult: Int) {
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        if(REQUEST_CODE==requestCode && granted) onGranted()
    }



    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
        super.onDestroy()
    }

    private fun checkPermission(code: Int): Boolean {
            if (Shizuku.isPreV11()) {
                // Pre-v11 is unsupported
                return false
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                // Granted
                return true
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                // Users choose "Deny and don't ask again"
                return false
            } else {
                return false
            }
    }
    private fun onGranted() {
        Intent(this, MainActivity::class.java).also {
            startActivity(it)
            finish()
        }
    }
}