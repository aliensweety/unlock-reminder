package com.suvye.unlockreminder

import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** 带搜索和图标的应用多选页。完成时同步提醒列表：新勾选加入（默认 10 分钟），取消勾选移除。 */
class AppPickerActivity : AppCompatActivity() {

    private data class AppEntry(val pkg: String, val label: String, val icon: Drawable)

    private val handler = Handler(Looper.getMainLooper())
    private val apps = ArrayList<AppEntry>()
    private val checked = HashSet<String>()
    private val existingDurations = HashMap<String, Long>()
    private var shown = emptyList<AppEntry>()

    private lateinit var listView: ListView
    private lateinit var search: EditText
    private lateinit var btnDone: Button
    private lateinit var adapter: AppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        listView = findViewById(R.id.appList)
        search = findViewById(R.id.searchApps)
        btnDone = findViewById(R.id.btnDone)
        adapter = AppAdapter()
        listView.adapter = adapter

        for (rule in RulesStore.overrides(this)) {
            checked.add(rule.pkg)
            existingDurations[rule.pkg] = rule.thresholdSec
        }
        refreshDoneLabel()

        listView.setOnItemClickListener { _, _, position, _ ->
            val entry = shown[position]
            if (entry.pkg in checked) checked.remove(entry.pkg) else checked.add(entry.pkg)
            adapter.notifyDataSetChanged()
            refreshDoneLabel()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        btnDone.setOnClickListener {
            commitSelection()
            finish()
        }

        Thread {
            val loaded = loadLauncherApps()
            handler.post {
                apps.clear()
                apps.addAll(loaded)
                applyFilter()
            }
        }.start()
    }

    private fun loadLauncherApps(): List<AppEntry> {
        val pm = packageManager
        val infos: List<ResolveInfo> = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        return infos
            .filter {
                val pkg = it.activityInfo.packageName
                pkg != packageName && pkg != "com.android.settings"
            }
            .distinctBy { it.activityInfo.packageName }
            .map { ri ->
                val pkg = ri.activityInfo.packageName
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) {
                    pkg
                }
                val icon = try {
                    pm.getApplicationIcon(pkg)
                } catch (_: Exception) {
                    getDrawable(android.R.drawable.sym_def_app_icon)!!
                }
                AppEntry(pkg, label, icon)
            }
            .sortedBy { it.label }
    }

    private fun applyFilter() {
        val q = search.text?.toString()?.trim().orEmpty()
        shown = if (q.isEmpty()) {
            apps.toList()
        } else {
            apps.filter { it.label.contains(q, ignoreCase = true) || it.pkg.contains(q, ignoreCase = true) }
        }
        adapter.notifyDataSetChanged()
    }

    private fun refreshDoneLabel() {
        btnDone.text = getString(R.string.rules_pick_done, checked.size)
    }

    private fun commitSelection() {
        val current = RulesStore.overrides(this)
        val keep = current.filter { it.pkg in checked }.toMutableList()
        val keptPkgs = keep.map { it.pkg }.toSet()
        for (pkg in checked) {
            if (pkg in keptPkgs) continue
            val label = apps.firstOrNull { it.pkg == pkg }?.label ?: pkg
            val sec = existingDurations[pkg] ?: RulesStore.DEFAULT_THRESHOLD_SEC
            keep.add(RulesStore.AppRule(pkg, label, sec))
        }
        RulesStore.saveOverrides(this, keep)
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): AppEntry = shown[position]
        override fun getItemId(position: Int): Long = shown[position].pkg.hashCode().toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_pick, parent, false)
            val entry = shown[position]
            view.findViewById<ImageView>(R.id.appIcon).setImageDrawable(entry.icon)
            view.findViewById<TextView>(R.id.appLabel).text = entry.label
            view.findViewById<CheckBox>(R.id.appCheck).isChecked = entry.pkg in checked
            return view
        }
    }
}
