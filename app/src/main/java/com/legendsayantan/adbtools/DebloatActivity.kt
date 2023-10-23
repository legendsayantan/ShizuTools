package com.legendsayantan.adbtools

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.legendsayantan.adbtools.adapters.DebloatAdapter
import com.legendsayantan.adbtools.data.AppData
import com.legendsayantan.adbtools.lib.ShizukuShell
import com.legendsayantan.adbtools.lib.Utils.Companion.extractUrls
import com.legendsayantan.adbtools.lib.Utils.Companion.initialiseStatusBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import java.util.Timer
import kotlin.concurrent.timerTask

class DebloatActivity : AppCompatActivity() {
    val output = listOf<String>()
    var shell: ShizukuShell? = null
    val prefs by lazy { getSharedPreferences("debloater", Context.MODE_PRIVATE) }
    lateinit var apps: List<AppData>
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debloat)
        initialiseStatusBar()
        val list = findViewById<ListView>(R.id.apps_list)
        shell = ShizukuShell(output, "pm grant $packageName android.permission.QUERY_ALL_PACKAGES")
        shell!!.exec()
        val setListListener = {
            list.setOnItemClickListener { _, _, position, _ ->
                var dialog: AlertDialog? = null
                val app = apps[position]
                //show dialog
                val links = TextView(this)
                links.text = "Links :"
                val listOfLinks = app.description.extractUrls()
                val linkView = ListView(this)
                linkView.adapter =
                    ArrayAdapter(this, android.R.layout.simple_list_item_1, listOfLinks)
                linkView.setOnItemClickListener { _, _, position, _ ->
                    //open link
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(listOfLinks[position])))
                }
                val uninstallBtn = MaterialButton(this)
                uninstallBtn.text = "Confirm to uninstall"
                uninstallBtn.setOnClickListener {
                    //uninstall app
                    shell = ShizukuShell(output, "pm uninstall -k --user 0 ${app.id}")
                    Toast.makeText(this, "Uninstalling ${app.name}", Toast.LENGTH_SHORT).show()
                    shell!!.exec()
                    Timer().schedule(timerTask {
                        runOnUiThread {
                            shell = ShizukuShell(output, "pm list packages | grep ${app.id}")
                            shell!!.exec()
                            if(output.isEmpty()) {
                                apps = apps.filter { it.id != app.id }
                                list.adapter = DebloatAdapter(applicationContext, apps)
                                Toast.makeText(applicationContext, "Uninstalled ${app.name}", Toast.LENGTH_LONG).show()
                            }
                            dialog?.dismiss()
                        }
                    }, 1000)
                }
                val btnContainer = LinearLayout(this)
                btnContainer.addView(uninstallBtn)
                btnContainer.setPadding(20, 20, 20, 20)
                btnContainer.gravity = Gravity.RIGHT
                val dialogView = LinearLayout(this)
                dialogView.orientation = LinearLayout.VERTICAL
                dialogView.setPadding(20, 0, 20, 20)
                if (listOfLinks.isNotEmpty()) dialogView.addView(links)
                dialogView.addView(linkView)
                dialogView.addView(btnContainer)
                dialog = AlertDialog.Builder(this)
                    .setView(dialogView)
                    .setCancelable(true)
                    .setTitle(app.name)
                    .setMessage(app.id)
                    .show()
            }
            findViewById<LinearLayout>(R.id.loader).visibility = LinearLayout.GONE
        }

        if (output.isEmpty()) {
            //success
            info("Loading online database")
            loadDatabase({ databaseApps ->
                //database available
                runOnUiThread {
                    info("Scanning local apps")
                    val localApps = getAllInstalledApps(packageManager)
                    val finalApp = arrayListOf<AppData>()
                    Thread {
                        localApps.forEach { app ->
                            val searchResult = databaseApps.find { it.id == app.packageName }
                            val appName = app.loadLabel(packageManager).toString()

                            if (searchResult != null) {
                                if (searchResult.removal != "Unsafe") finalApp.add(searchResult.apply {
                                    name = appName
                                })
                            } else {
                                finalApp.add(
                                    AppData(
                                        appName, app.packageName, "",
                                        "", arrayListOf(), arrayListOf(),
                                        arrayListOf(), ""
                                    )
                                )
                            }
                        }
                        runOnUiThread {
                            apps = finalApp.sortedWith(compareBy { it.name })
                            list.adapter = DebloatAdapter(applicationContext, apps)
                            setListListener()
                        }
                    }.start()
                }
            }, {
                info("No local or online database found.\nPlease check your internet connection and try again.")
            })
        }
    }

    fun loadDatabase(onComplete: (ArrayList<AppData>) -> Unit, onFailure: () -> Unit) {
        try {
            val url =
                URL("https://cdn.jsdelivr.net/gh/0x192/universal-android-debloater@main/resources/assets/uad_lists.json")
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    // Read the JSON content from the URL
                    val connection = url.openConnection()
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val jsonContent = reader.readText()
                    prefs.edit().putString("database", jsonContent).apply()
                    onComplete(jsonContent.asAppDatabase())
                } catch (e: Exception) {
                    e.printStackTrace()
                    if (prefs.getString("database", null).isNullOrBlank()) {
                        onFailure()
                    } else {
                        onComplete(prefs.getString("database", null)!!.asAppDatabase())
                    }
                }
            }
        } catch (e: Exception) {
            if (prefs.getString("database", null).isNullOrBlank()) {
                onFailure()
            } else {
                onComplete(prefs.getString("database", null)!!.asAppDatabase())
            }
        }
    }

    private fun String.asAppDatabase(): ArrayList<AppData> {
        return Gson().fromJson(this, object : TypeToken<ArrayList<AppData?>?>() {}.type)
    }

    fun getAllInstalledApps(packageManager: PackageManager): List<ApplicationInfo> {
        return packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
    }

    fun info(string: String) {
        findViewById<TextView>(R.id.infoView).text = string
    }
}