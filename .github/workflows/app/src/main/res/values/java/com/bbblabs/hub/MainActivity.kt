package com.bbblabs.hub

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.DateFormat
import java.util.Date

class AppEntry(
    val label: String,
    val pkg: String,
    val intent: Intent,
    val isGame: Boolean,
    val isUnknown: Boolean,
    val installer: String?,
    val installedAt: Long,
    val updatedAt: Long,
    val size: Long,
    val icon: Drawable
)

class MainActivity : Activity() {

    private var all: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private var filter = 0      // 0 All, 1 Games, 2 Apps, 3 Unknown Sources
    private var sort = 0        // 0 A-Z, 1 Newest, 2 Updated, 3 Biggest
    private var query = ""

    private val adapter = AppAdapter()
    private val filterBtns = ArrayList<Button>()
    private val sortBtns = ArrayList<Button>()
    private lateinit var countText: TextView
    private var ready = false

    private val shortcuts by lazy { settingsShortcuts() }

    // Installers we treat as "a real store". Anything else counts as Unknown Sources.
    private val knownStores = setOf(
        "com.android.vending",
        "com.oculus.horizon",
        "com.oculus.store",
        "com.amazon.venezia",
        "com.sec.android.app.samsungapps"
    )

    // ---------------------------------------------------------------- Start

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            buildUi()
            ready = true
        } catch (t: Throwable) {
            showText("BBB Labs Hub error while starting:\n\n" + Log.getStackTraceString(t))
        }
    }

    private fun showText(msg: String) {
        val tv = TextView(this).apply {
            text = msg
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(16), dp(32), dp(16), dp(16))
            setTextIsSelectable(true)
        }
        val sv = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }
        sv.addView(tv)
        setContentView(sv)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(12), dp(24), dp(12), dp(8))
        }

        // Title + Settings
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = "BBB Labs Hub"
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(smallButton("Settings") { showSettingsDialog() })
        root.addView(titleRow)

        countText = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(countText)

        // Show buttons
        root.addView(label("Show"))
        root.addView(buttonRow(listOf("All", "Games", "Apps", "Unknown Sources"), filterBtns) { i ->
            filter = i
            styleAll()
            applyFilter()
        })

        // Sort buttons
        root.addView(label("Sort by"))
        root.addView(buttonRow(listOf("A–Z", "Newest", "Updated", "Biggest"), sortBtns) { i ->
            sort = i
            styleAll()
            applyFilter()
        })
        styleAll()

        // Search
        val search = EditText(this).apply {
            hint = "Search apps and games"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 14f
            isSingleLine = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                setStroke(dp(1), Color.GRAY)
                cornerRadius = dp(10).toFloat()
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim().orEmpty()
                    applyFilter()
                }
            })
        }
        root.addView(search, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8); bottomMargin = dp(8) })

        // App grid
        val grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(88)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = ColorDrawable(0x33FFFFFF)
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> openApp(shown[pos]) }
            setOnItemLongClickListener { _, _, pos, _ ->
                showDetails(shown[pos])
                true
            }
        }
        root.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (!ready) return
        Thread {
            try {
                val list = loadApps()
                runOnUiThread {
                    all = list
                    applyFilter()
                }
            } catch (t: Throwable) {
                runOnUiThread { countText.text = "Couldn't load apps: ${t.message}" }
            }
        }.start()
    }

    // ---------------------------------------------------------------- Apps

    @Suppress("DEPRECATION")
    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        val seen = HashSet<String>()
        val out = ArrayList<AppEntry>()

        val launcherQuery = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val vrQuery = Intent(Intent.ACTION_MAIN).addCategory("com.oculus.intent.category.VR")

        for ((index, q) in listOf(launcherQuery, vrQuery).withIndex()) {
            val isVrQuery = index == 1
            val results = try { pm.queryIntentActivities(q, 0) } catch (e: Exception) { emptyList() }
            for (ri in results) {
                try {
                    val info = ri.activityInfo ?: continue
                    val pkg = info.packageName
                    if (pkg == packageName) continue
                    if (!seen.add(pkg)) continue

                    val ai = info.applicationInfo
                    val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                            (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                    if (isSystem) continue

                    val installer: String? = try {
                        pm.getInstallSourceInfo(pkg).installingPackageName
                    } catch (e: Throwable) { null }

                    val pi = try { pm.getPackageInfo(pkg, 0) } catch (e: Throwable) { null }
                    val size = try { File(ai.sourceDir).length() } catch (e: Throwable) { 0L }

                    val flagGame = (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                    val catGame = ai.category == ApplicationInfo.CATEGORY_GAME

                    val launch = Intent(Intent.ACTION_MAIN).apply {
                        setClassName(pkg, info.name)
                        addCategory(if (isVrQuery) "com.oculus.intent.category.VR" else Intent.CATEGORY_LAUNCHER)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    out.add(
                        AppEntry(
                            label = ri.loadLabel(pm).toString(),
                            pkg = pkg,
                            intent = launch,
                            isGame = flagGame || catGame || isVrQuery,
                            isUnknown = installer == null || installer !in knownStores,
                            installer = installer,
                            installedAt = pi?.firstInstallTime ?: 0L,
                            updatedAt = pi?.lastUpdateTime ?: 0L,
                            size = size,
                            icon = ri.loadIcon(pm)
                        )
                    )
                } catch (e: Throwable) {
                    // Skip any single app that can't be read.
                }
            }
        }
        return out
    }

    private fun applyFilter() {
        val filtered = all.filter { e ->
            val ok = when (filter) {
                1 -> e.isGame
                2 -> !e.isGame
                3 -> e.isUnknown
                else -> true
            }
            ok && (query.isEmpty() || e.label.contains(query, ignoreCase = true))
        }
        shown = when (sort) {
            1 -> filtered.sortedByDescending { it.installedAt }
            2 -> filtered.sortedByDescending { it.updatedAt }
            3 -> filtered.sortedByDescending { it.size }
            else -> filtered.sortedBy { it.label.lowercase() }
        }
        countText.text = "${shown.size} shown  •  ${all.count { it.isUnknown }} from unknown sources"
        adapter.notifyDataSetChanged()
    }

    private fun openApp(e: AppEntry) {
        try {
            startActivity(e.intent)
        } catch (ex: Exception) {
            Toast.makeText(this, "Couldn't open ${e.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDetails(e: AppEntry) {
        val fmt = DateFormat.getDateTimeInstance()
        val msg = buildString {
            append("Package: ${e.pkg}\n")
            append("Installed by: ${e.installer ?: "unknown (sideloaded)"}\n")
            append("Installed: ${fmt.format(Date(e.installedAt))}\n")
            append("Updated: ${fmt.format(Date(e.updatedAt))}\n")
            append("Size: ${String.format("%.1f", e.size / 1048576.0)} MB")
        }
        AlertDialog.Builder(this)
            .setTitle(e.label)
            .setMessage(msg)
            .setPositiveButton("App info") { _, _ -> openAppInfo(e) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openAppInfo(e: AppEntry) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.pkg}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (ex: Exception) {
            Toast.makeText(this, "App info isn't available", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- Settings

    private fun showSettingsDialog() {
        val labels = shortcuts.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Settings shortcuts")
            .setItems(labels) { _, i -> launch(shortcuts[i]) }
            .show()
    }

    private fun launch(sc: Shortcut) {
        for (intent in sc.tries) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            } catch (e: Exception) {
                // Try the next fallback.
            }
        }
        Toast.makeText(this, "${sc.label} isn't available on this device", Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- Styling helpers

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.GRAY)
        textSize = 11f
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun buttonRow(
        names: List<String>,
        store: MutableList<Button>,
        onPick: (Int) -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        names.forEachIndexed { i, name ->
            val b = smallButton(name) { onPick(i) }
            store.add(b)
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(6) })
        }
        return row
    }

    private fun smallButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 12f
        minHeight = 0
        minimumHeight = dp(40)
        setPadding(dp(6), dp(4), dp(6), dp(4))
        style(this, false)
        setOnClickListener { onClick() }
    }

    private fun style(b: Button, selected: Boolean) {
        b.setTextColor(if (selected) Color.BLACK else Color.WHITE)
        b.background = GradientDrawable().apply {
            setColor(if (selected) Color.WHITE else Color.BLACK)
            setStroke(dp(2), Color.WHITE)
            cornerRadius = dp(12).toFloat()
        }
    }

    private fun styleAll() {
        filterBtns.forEachIndexed { i, b -> style(b, i == filter) }
        sortBtns.forEachIndexed { i, b -> style(b, i == sort) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- Adapter

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
                addView(ImageView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
                })
                addView(TextView(this@MainActivity).apply {
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(6), 0, 0)
                })
            }
            val e = shown[position]
            (row.getChildAt(0) as ImageView).setImageDrawable(e.icon)
            (row.getChildAt(1) as TextView).text = e.label
            return row
        }
    }
}
