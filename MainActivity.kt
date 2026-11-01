package com.example.talkingtimer

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private val cBg = Color.parseColor("#10151C")
    private val cAccent = Color.parseColor("#F2B84B")
    private val cText = Color.parseColor("#F4F1EA")
    private val cMuted = Color.parseColor("#A9B0BA")
    private val cCard = Color.parseColor("#1B222C")

    private lateinit var number: TextView
    private lateinit var toggle: Button
    private lateinit var down: Switch
    private lateinit var repeat: Switch
    private lateinit var startEt: EditText
    private lateinit var stopEt: EditText
    private lateinit var stepEt: EditText
    private lateinit var intervalEt: EditText
    private lateinit var rateEt: EditText
    private lateinit var presetBox: LinearLayout

    private val prefs by lazy { getSharedPreferences("presets", MODE_PRIVATE) }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }

        col.addView(label("Talking Timer", 22f, cText))

        number = TextView(this).apply {
            textSize = 120f
            setTextColor(cAccent)
            gravity = Gravity.CENTER
            this.text = "0"
        }
        col.addView(number, LinearLayout.LayoutParams(-1, dp(220)))

        down = Switch(this).apply {
            this.text = "Count down"
            setTextColor(cText)
            minHeight = dp(48)
        }
        col.addView(down)

        repeat = Switch(this).apply {
            this.text = "Repeat when finished"
            setTextColor(cText)
            minHeight = dp(48)
        }
        col.addView(repeat)
        col.addView(label("Repeat needs a stop number when counting up.", 13f, cMuted))

        startEt = field(col, "Start from", "0")
        stopEt = field(col, "Stop at (blank: none counting up, 0 counting down)", "")
        stepEt = field(col, "Count by (1 = 1, 2, 3... / 5 = 5, 10, 15...)", "1")
        intervalEt = field(col, "Speak every (seconds)", "1")
        rateEt = field(col, "Voice speed (1.0 = normal)", "1.0")

        toggle = button("Start", cAccent, cBg) { onToggle() }
        col.addView(toggle, lp())
        col.addView(button("Stop", cCard, cText) {
            startService(Intent(this, TimerService::class.java).setAction(TimerService.ACTION_STOP))
        }, lp())

        col.addView(label("Presets", 18f, cText).apply { setPadding(0, dp(28), 0, dp(4)) })
        col.addView(button("Save current settings as preset", cCard, cAccent) { askPresetName() }, lp())
        col.addView(label("Tap a preset to load it. Long-press to delete.", 13f, cMuted).apply {
            setPadding(0, dp(8), 0, 0)
        })
        presetBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(presetBox)
        renderPresets()

        col.addView(button("Open app settings (battery, auto-start)", cCard, cMuted) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }, lp().apply { topMargin = dp(28) })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(cBg)
            addView(col)
        })
    }

    override fun onStart() {
        super.onStart()
        TimerService.onChange = { refresh() }
        refresh()
    }

    override fun onStop() {
        TimerService.onChange = null
        super.onStop()
    }

    private fun onToggle() {
        when {
            !TimerService.active -> {
                val i = Intent(this, TimerService::class.java)
                    .setAction(TimerService.ACTION_START)
                    .putExtra(TimerService.EXTRA_DOWN, down.isChecked)
                    .putExtra(TimerService.EXTRA_REPEAT, repeat.isChecked)
                    .putExtra(TimerService.EXTRA_START, startEt.text.toString().toIntOrNull() ?: 0)
                    .putExtra(TimerService.EXTRA_STEP, stepEt.text.toString().toIntOrNull() ?: 1)
                    .putExtra(TimerService.EXTRA_INTERVAL, intervalEt.text.toString().toIntOrNull() ?: 1)
                    .putExtra(TimerService.EXTRA_RATE, rateEt.text.toString().toFloatOrNull() ?: 1f)
                stopEt.text.toString().toIntOrNull()?.let { i.putExtra(TimerService.EXTRA_STOP, it) }
                ContextCompat.startForegroundService(this, i)
            }
            TimerService.running ->
                startService(Intent(this, TimerService::class.java).setAction(TimerService.ACTION_PAUSE))
            else ->
                startService(Intent(this, TimerService::class.java).setAction(TimerService.ACTION_RESUME))
        }
    }

    private fun refresh() {
        number.text = TimerService.current.toString()
        toggle.text = when {
            !TimerService.active -> "Start"
            TimerService.running -> "Pause"
            else -> "Resume"
        }
    }

    // ---- Presets ----

    private fun loadPresets(): MutableList<JSONObject> {
        val arr = JSONArray(prefs.getString("list", "[]"))
        return MutableList(arr.length()) { arr.getJSONObject(it) }
    }

    private fun savePresets(list: List<JSONObject>) {
        prefs.edit().putString("list", JSONArray(list).toString()).apply()
    }

    private fun askPresetName() {
        val input = EditText(this)
        input.hint = "Preset name"
        AlertDialog.Builder(this)
            .setTitle("Save preset")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Preset" }
                val o = JSONObject()
                    .put("name", name)
                    .put("down", down.isChecked)
                    .put("repeat", repeat.isChecked)
                    .put("start", startEt.text.toString())
                    .put("stop", stopEt.text.toString())
                    .put("step", stepEt.text.toString())
                    .put("interval", intervalEt.text.toString())
                    .put("rate", rateEt.text.toString())
                val list = loadPresets().filter { it.getString("name") != name }.toMutableList()
                list.add(o)
                savePresets(list)
                renderPresets()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyPreset(p: JSONObject) {
        down.isChecked = p.optBoolean("down")
        repeat.isChecked = p.optBoolean("repeat")
        startEt.setText(p.optString("start", "0"))
        stopEt.setText(p.optString("stop", ""))
        stepEt.setText(p.optString("step", "1"))
        intervalEt.setText(p.optString("interval", "1"))
        rateEt.setText(p.optString("rate", "1.0"))
    }

    private fun confirmDelete(p: JSONObject) {
        val name = p.getString("name")
        AlertDialog.Builder(this)
            .setTitle("Delete \"$name\"?")
            .setPositiveButton("Delete") { _, _ ->
                savePresets(loadPresets().filter { it.getString("name") != name })
                renderPresets()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderPresets() {
        presetBox.removeAllViews()
        val list = loadPresets()
        if (list.isEmpty()) {
            presetBox.addView(label("No saved presets yet.", 14f, cMuted).apply { setPadding(0, dp(12), 0, 0) })
            return
        }
        for (p in list) {
            val dir = if (p.optBoolean("down")) "down" else "up"
            val stop = p.optString("stop", "").ifEmpty { "no stop" }
            val rep = if (p.optBoolean("repeat")) ", repeat" else ""
            val summary = "${p.optString("start")} to $stop, $dir by ${p.optString("step")}, " +
                "every ${p.optString("interval")}s$rep"
            val b = button(p.getString("name") + "\n" + summary, cCard, cText) { applyPreset(p) }
            b.minHeight = dp(64)
            b.setOnLongClickListener { confirmDelete(p); true }
            presetBox.addView(b, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
    }

    // ---- UI helpers ----

    private fun lp() = LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(12) }

    private fun label(t: String, size: Float, color: Int) = TextView(this).apply {
        this.text = t; textSize = size; setTextColor(color)
    }

    private fun field(parent: LinearLayout, hint: String, default: String): EditText {
        parent.addView(label(hint, 14f, cMuted).apply { setPadding(0, dp(12), 0, 0) })
        return EditText(this).apply {
            setText(default)
            setTextColor(cText)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or
                InputType.TYPE_NUMBER_FLAG_DECIMAL
            minHeight = dp(48)
            parent.addView(this, ViewGroup.LayoutParams(-1, -2))
        }
    }

    private fun button(t: String, bgc: Int, fg: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = t
        setTextColor(fg)
        setBackgroundColor(bgc)
        setAllCaps(false)
        textSize = 16f
        setOnClickListener { onClick() }
    }
}
