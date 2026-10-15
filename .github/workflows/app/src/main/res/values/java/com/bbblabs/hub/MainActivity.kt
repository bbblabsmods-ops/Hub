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

class AppEntry(
    val label: String,
    val pkg: String,
    val intent: Intent,
    val isGame: Boolean,
    val icon: Drawable
)

class MainActivity : Activity() {

    private var all: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private var mode = 0            // 0 = All, 1 = Games, 2 = Apps
    private var query = ""

    private val adapter = AppAdapter()
    private val chips = ArrayList<Button>()
    private lateinit var startupBtn: Button
    private lateinit var countText: TextView

    private val prefs by lazy { getSharedPreferences("hub", Context.MODE_PRIVATE) }
    private val shortcuts by lazy { settingsShortcuts() }

    // ---------------------------------------------------------------- Start

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If the app crashes anywhere, save the error so we can show it next launch.
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try {
                applicationContext.getSharedPreferences("hub", Context.MODE_PRIVATE)
                    .edit().putString("crash", Log.getStackTraceString(e)).commit()
            } catch (ignored: Throwable) {
            }
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        val saved = prefs.getString("crash", null)
        if (saved != null) {
            prefs.edit().remove("crash").commit()
            showText("BBB Labs Hub hit an error last time:\n\n$saved")
            return
        }

        try {
            buildUi()
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
            setPadding(dp(12), dp(28), dp(12), dp(8))
        }

        root.addView(TextView(this).apply {
            text = "BBB Labs Hub"
            setTextColor(Color.WHITE)
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })

        countText = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(10))
        }
        root.addView(countText)

        // Action row: Settings / Startup
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val settingsBtn = smallButton("Settings") { showSettingsDialog() }
        startupBtn = smallButton("Startup: OFF") { toggleStartup() }
        listOf(settingsBtn, startupBtn).forEach { b ->
            style(b, false)
            actions.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(6) })
        }
        root.addView(actions)

        // Filter chips
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        listOf("All", "Games", "Apps").forEachIndexed { i, name ->
            val b = smallButton(name) {
                mode = i
                styleChips()
                applyFilter()
            }
            chips.add(b)
            chipRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(6) })
        }
        root.addView(chipRow)
        styleChips()

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
        ).apply { bottomMargin = dp(8) })

        // App grid
        val grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(88)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = ColorDrawable(0x33FFFFFF)
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> openApp(shown[pos]) }
            setOnItemLongClickListener { _, _, pos, _ ->
                openAppInfo(shown[pos])
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
        if (!::startupBtn.isInitialized || !::countText.isInitialized) return
        refreshStartupLabel()
        // Reload in the background so newly installed apps appear.
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

                    val appInfo = info.applicationInfo
                    val flagGame = (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                    val catGame = Build.VERSION.SDK_INT >= 26 &&
                            appInfo.category == ApplicationInfo.CATEGORY_GAME

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
                            icon = ri.loadIcon(pm)
                        )
                    )
                } catch (e: Throwable) {
                    // Skip any single app that can't be read.
                }
            }
        }
        return out.sortedBy { it.label.lowercase() }
    }

    private fun applyFilter() {
        shown = all.filter { e ->
            val modeOk = when (mode) {
                1 -> e.isGame
                2 -> !e.isGame
                else -> true
            }
            modeOk && (query.isEmpty() || e.label.contains(query, ignoreCase = true))
        }
        countText.text = "${shown.size} shown"
        adapter.notifyDataSetChanged()
    }

    private fun openApp(e: AppEntry) {
        try {
            startActivity(e.intent)
        } catch (ex: Exception) {
            Toast.makeText(this, "Couldn't open ${e.label}", Toast.LENGTH_SHORT).show()
        }
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

    // ---------------------------------------------------------------- Startup

    private fun canOverlay() =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)

    private fun toggleStartup() {
        val turnOn = !prefs.getBoolean("autostart", false)
        prefs.edit().putBoolean("autostart", turnOn).apply()
        if (turnOn && !canOverlay()) {
            Toast.makeText(
                this,
                "Allow \"Display over other apps\" for BBB Labs Hub so it can open at startup",
                Toast.LENGTH_LONG
            ).show()
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                // Not available on this device.
            }
        }
        refreshStartupLabel()
    }

    private fun refreshStartupLabel() {
        val on = prefs.getBoolean("autostart", false)
        startupBtn.text = when {
            !on -> "Startup: OFF"
            canOverlay() -> "Startup: ON"
            else -> "Startup: needs permission"
        }
        style(startupBtn, on && canOverlay())
    }

    // ---------------------------------------------------------------- Styling helpers

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

    private fun styleChips() {
        chips.forEachIndexed { i, b -> style(b, i == mode) }
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
