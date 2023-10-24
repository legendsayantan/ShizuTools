package com.legendsayantan.adbtools.adapters

/**
 * @author legendsayantan
 */
import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textview.MaterialTextView
import com.legendsayantan.adbtools.data.AppData
import com.legendsayantan.adbtools.R
import com.legendsayantan.adbtools.lib.Utils.Companion.extractUrls
import com.legendsayantan.adbtools.lib.Utils.Companion.removeUrls

class DebloatAdapter(private val activity: Activity, private val dataList: List<AppData>) :
    BaseAdapter() {

    override fun getCount(): Int {
        return dataList.size
    }

    override fun getItem(position: Int): AppData {
        return dataList[position]
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val data = getItem(position)

        val view: View = if (convertView == null) {
            val inflater =
                activity.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            inflater.inflate(R.layout.debloat_item, null)
        } else {
            convertView
        }

        val appNameTextView = view.findViewById<MaterialTextView>(R.id.app_name)
        val listModeTextView = view.findViewById<MaterialTextView>(R.id.listMode)
        val descriptionTextView = view.findViewById<MaterialTextView>(R.id.Description)
        val background = view.findViewById<MaterialCardView>(R.id.background)

        val desc = if(data.description.isNotEmpty()){
            var copy = data.description
            copy.replace("\n\n","\n").removeUrls()
        }else{
            data.id
        }

        appNameTextView.text = data.name
        listModeTextView.text = data.list
        descriptionTextView.text = desc
        background.strokeColor = when (data.removal) {
            "Recommended" -> activity.getColor(R.color.green)
            "Advanced" -> activity.getColor(R.color.yellow)
            "Expert" -> activity.getColor(R.color.red)
            else -> activity.getColor(R.color.transparent)
        }
        return view
    }
}
