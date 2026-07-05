package com.legendsayantan.adbtools

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.legendsayantan.adbtools.lib.Utils.Companion.setupEdgeToEdgeInsets
import com.legendsayantan.adbtools.providers.VirtualMountProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VirtualMountActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        
        setContentView(R.layout.activity_virtual_mount)

        val root = findViewById<View>(R.id.root_layout)
        setupEdgeToEdgeInsets(R.id.root_layout, R.id.header_content)
        ViewCompat.animate(root).alpha(1f).scaleX(1f).scaleY(1f).setDuration(280).start()

        val switchEnable = findViewById<MaterialSwitch>(R.id.switchEnable)
        val editMountName = findViewById<EditText>(R.id.editMountName)
        val editMountAddress = findViewById<EditText>(R.id.editMountAddress)
        val btnTest = findViewById<Button>(R.id.btnTest)

        val prefs = getSharedPreferences("virtual_mount", MODE_PRIVATE)
        val providerComponent = ComponentName(this, VirtualMountProvider::class.java)

        // Init switch state based on ComponentEnabledSetting
        val state = packageManager.getComponentEnabledSetting(providerComponent)
        switchEnable.isChecked = state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            switchEnable.isEnabled = false
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    val newState = if (isChecked) {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    } else {
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    }
                    packageManager.setComponentEnabledSetting(
                        providerComponent,
                        newState,
                        PackageManager.DONT_KILL_APP
                    )
                }
                switchEnable.isEnabled = true
                val msg = if (isChecked) "Virtual Mount Enabled" else "Virtual Mount Disabled"
                Snackbar.make(root, msg, Snackbar.LENGTH_SHORT).show()
            }
        }

        editMountName.setText(prefs.getString("mount_name", "Unlocked Android Data"))
        editMountName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString("mount_name", s.toString()).apply()
            }
        })

        editMountAddress.setText(prefs.getString("mount_path", "/storage/emulated/0/Android/data"))
        editMountAddress.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString("mount_path", s.toString()).apply()
            }
        })

        btnTest.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW)
            val uri = DocumentsContract.buildRootUri(
                "${packageName}.providers.virtualmount",
                "shizutools_virtual_mount"
            )
            intent.setDataAndType(uri, "vnd.android.document/root")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            
            try {
                startActivity(intent)
                Snackbar.make(root, "File Manager Opened", Snackbar.LENGTH_SHORT).show()
            } catch (e: Exception) {
                // Fallback to open generic file picker
                Snackbar.make(root, "Direct launch failed. Opening generic picker.", Snackbar.LENGTH_LONG).show()
                val fallbackIntent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                fallbackIntent.addCategory(Intent.CATEGORY_OPENABLE)
                fallbackIntent.type = "*/*"
                startActivity(fallbackIntent)
            }
        }
    }
    
    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
