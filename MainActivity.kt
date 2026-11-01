package com.example.talkingtimer

import android.Manifest
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

class MainActivity : ComponentActivity() {

    private val cBg = Color.parseColor("#10151C")
    private val cAccent = Color.parseColor("#F2B84B")
    private val cText = Color.parseColor("#F4F1EA")
    private val cMuted = Color.parseColor("#A9B0BA")

    private lateinit var number: TextView
    private lateinit var toggle: Button
    private lateinit var down: Switch
    private lateinit var startEt: EditText
    private lateinit var stopEt: EditText
    private lateinit var intervalEt: EditText
    private lateinit var rateEt: EditText

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

        startEt = field(col, "Start from", "0")
        stopEt = field(col, "Stop at (blank: none counting up, 0 counting down)", "")
        intervalEt = field(col, "Speak every (seconds)", "1")
        rateEt = field(col, "Voice speed (1.0 = normal)", "1.0")

        toggle = button("Start", cAccent, cBg) { onToggle() }
        col.addView(toggle, lp())
        col.addView(button("Stop", Color.parseColor("#1B222C"), cText) {
            startService(Intent(this, TimerService::class.java).setAction(TimerService.ACTION_STOP))
        }, lp())
        col.addView(button("Open app settings (battery, auto-start)", Color.parseColor("#1B222C"), cMuted) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }, lp())

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
                    .putExtra(TimerService.EXTRA_START, startEt.text.toString().toIntOrNull() ?: 0)
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
