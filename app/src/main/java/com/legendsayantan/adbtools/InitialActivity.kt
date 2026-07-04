package com.legendsayantan.adbtools

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.legendsayantan.adbtools.lib.GradleUpdate
import com.legendsayantan.adbtools.lib.Logger.Companion.log
import com.legendsayantan.adbtools.lib.Utils.Companion.initialiseNotiChannel
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.OnRequestPermissionResultListener

class InitialActivity : AppCompatActivity() {
    val REQUEST_CODE = 123
    
    private lateinit var btnAction: MaterialButton
    
    // Step views
    private lateinit var step1Icon: ImageView
    private lateinit var step1Title: TextView
    private lateinit var step1Desc: TextView
    
    private lateinit var step2Icon: ImageView
    private lateinit var step2Title: TextView
    private lateinit var step2Desc: TextView
    
    private lateinit var step3Icon: ImageView
    private lateinit var step3Title: TextView
    private lateinit var step3Desc: TextView

    private val requestPermissionResultListener =
        OnRequestPermissionResultListener { requestCode: Int, grantResult: Int ->
            this.onRequestPermissionsResult(requestCode, grantResult)
        }
        
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_initial)
        
        val root = findViewById<View>(R.id.root_layout)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, 0, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.animate(root).alpha(1f).scaleX(1f).scaleY(1f).setDuration(280).start()
        
        btnAction = findViewById(R.id.btn_action)
        
        // Init Steps
        val stepInstall = findViewById<View>(R.id.step_install)
        step1Icon = stepInstall.findViewById(R.id.step_icon)
        step1Title = stepInstall.findViewById(R.id.step_title)
        step1Desc = stepInstall.findViewById(R.id.step_desc)
        step1Title.text = "Install Shizuku"
        step1Desc.text = "The Shizuku app must be installed"
        
        val stepStart = findViewById<View>(R.id.step_start)
        step2Icon = stepStart.findViewById(R.id.step_icon)
        step2Title = stepStart.findViewById(R.id.step_title)
        step2Desc = stepStart.findViewById(R.id.step_desc)
        step2Title.text = "Start Shizuku"
        step2Desc.text = "Start the Shizuku service on your device"
        
        val stepPermission = findViewById<View>(R.id.step_permission)
        step3Icon = stepPermission.findViewById(R.id.step_icon)
        step3Title = stepPermission.findViewById(R.id.step_title)
        step3Desc = stepPermission.findViewById(R.id.step_desc)
        step3Title.text = "Grant Permission"
        step3Desc.text = "Allow ShizuTools to access Shizuku"
        
        try {
            findViewById<TextView>(R.id.version_text).text = "v${packageManager.getPackageInfo(packageName, 0).versionName}"
        } catch (e: Exception) {}

        initialiseNotiChannel()
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)

        btnAction.setOnClickListener {
            checkShizukuStatus()
        }


        
        checkShizukuStatus()
    }

    override fun onResume() {
        super.onResume()
        checkShizukuStatus()
    }
    
    private fun checkShizukuStatus() {
        // Step 1: Check Install
        val installed = isShizukuInstalled()
        updateStep(step1Icon, step1Title, installed)
        
        if (!installed) {
            btnAction.text = "Get from Play Store"
            btnAction.setIconResource(R.drawable.baseline_file_download_24)
            btnAction.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")))
            }
            return
        }
        
        // Step 2: Check if running
        val running = isShizukuRunning()
        updateStep(step2Icon, step2Title, running)
        
        if (!running) {
            btnAction.text = "Check Status"
            btnAction.setIconResource(R.drawable.baseline_refresh_24)
            btnAction.setOnClickListener { checkShizukuStatus() }
            return
        }
        
        // Step 3: Check Permission
        val hasPermission = checkPermission()
        updateStep(step3Icon, step3Title, hasPermission)
        
        if (!hasPermission) {
            btnAction.text = "Grant Permission"
            btnAction.setIconResource(R.drawable.baseline_verified_24)
            btnAction.setOnClickListener {
                try {
                    Shizuku.requestPermission(REQUEST_CODE)
                } catch (e: Exception) {
                    log(e.stackTraceToString(), true)
                }
            }
            return
        }
        
        // All done
        onGranted()
    }
    
    private fun updateStep(icon: ImageView, title: TextView, completed: Boolean) {
        if (completed) {
            icon.setImageResource(R.drawable.baseline_verified_24)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.colorSecondary))
            title.alpha = 1f
        } else {
            icon.setImageResource(R.drawable.outline_info_24)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.colorOutline))
            title.alpha = 0.5f
        }
    }

    private fun isShizukuInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
    
    private fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    private fun onRequestPermissionsResult(requestCode: Int, grantResult: Int) {
        if (REQUEST_CODE == requestCode) {
            checkShizukuStatus()
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
        super.onDestroy()
    }

    private fun checkPermission(): Boolean {
        if (Shizuku.isPreV11()) {
            return false
        }
        return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }
    
    private fun onGranted() {
        Intent(this, MainActivity::class.java).also {
            startActivity(it)
            finish()
        }
    }
}