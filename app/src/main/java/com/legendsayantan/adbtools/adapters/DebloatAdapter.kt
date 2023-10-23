package com.legendsayantan.adbtools.adapters

/**
 * @author legendsayantan
 */
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.legendsayantan.adbtools.data.AppData
import com.legendsayantan.adbtools.R
import com.legendsayantan.adbtools.lib.Utils.Companion.extractUrls
import com.legendsayantan.adbtools.lib.Utils.Companion.removeUrls

class DebloatAdapter(private val context: Context, private val dataList: List<AppData>) :
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
        val view: View

        if (convertView == null) {
            val inflater =
                context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            view = inflater.inflate(R.layout.debloat_item, null)
        } else {
            view = convertView
        }

        val appNameTextView = view.findViewById<TextView>(R.id.app_name)
        val listModeTextView = view.findViewById<TextView>(R.id.listMode)
        val descriptionTextView = view.findViewById<TextView>(R.id.Description)
        val background = view.findViewById<View>(R.id.background)

        val desc = if(data.description.isNotEmpty()){
            var copy = data.description
            val links = copy.extractUrls()
            copy.replace("\n\n","\n").removeUrls()
        }else{
            data.id
        }

        appNameTextView.text = data.name
        listModeTextView.text = data.list
        descriptionTextView.text = desc
        background.setBackgroundColor(
            when (data.removal) {
                "Recommended" -> context.getColor(R.color.green)
                "Advanced" -> context.getColor(R.color.yellow)
                "Expert" -> context.getColor(R.color.red)
                else -> context.getColor(R.color.transparent)
            }
        )

        return view
    }
}
