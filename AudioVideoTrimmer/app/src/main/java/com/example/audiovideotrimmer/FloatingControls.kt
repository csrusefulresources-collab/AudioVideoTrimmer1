package com.example.audiovideotrimmer

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources

/**
 * Small floating panel shown above all apps while recording:
 *  [ 00:12 ]  [ pause / resume ]  [ stop ]
 * Big buttons, spoken labels, draggable by the time field.
 * (It appears in screen recordings too – you can trim it away in the editor.)
 */
class FloatingControls(
    private val context: Context,
    private val onToggle: () -> Unit,
    private val onStop: () -> Unit
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var timeView: TextView? = null
    private var toggleButton: ImageButton? = null
    private lateinit var params: WindowManager.LayoutParams

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    fun show(): Boolean {
        if (root != null) return true
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(0xE6212121.toInt())
                cornerRadius = dp(36).toFloat()
            }
            accessibilityPaneTitle = "Recording controls"
        }
        val time = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(dp(8), 0, dp(12), 0)
            text = "00:00"
            contentDescription = "Recording time. Drag to move the controls."
        }
        val toggle = makeButton(R.drawable.ic_pause, "Pause recording", 0xFF455A64.toInt()) { onToggle() }
        val stop = makeButton(R.drawable.ic_stop, "Stop recording and save", 0xFFC62828.toInt()) { onStop() }
        (stop.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)

        panel.addView(time)
        panel.addView(toggle)
        panel.addView(stop)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(140)
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        time.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    try {
                        wm.updateViewLayout(panel, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                else -> false
            }
        }

        return try {
            wm.addView(panel, params)
            root = panel
            timeView = time
            toggleButton = toggle
            true
        } catch (e: Exception) {
            false
        }
    }

    fun update(phase: Phase, elapsedMs: Long) {
        val paused = phase == Phase.PAUSED
        timeView?.text = TimeFormat.clock(elapsedMs)
        toggleButton?.apply {
            setImageDrawable(AppCompatResources.getDrawable(context, if (paused) R.drawable.ic_play else R.drawable.ic_pause))
            setColorFilter(Color.WHITE)
            contentDescription = if (paused) "Resume recording" else "Pause recording"
        }
    }

    fun hide() {
        root?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        root = null
        timeView = null
        toggleButton = null
    }

    private fun makeButton(icon: Int, description: String, color: Int, click: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            setImageDrawable(AppCompatResources.getDrawable(context, icon))
            setColorFilter(Color.WHITE)
            contentDescription = description
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setOnClickListener { click() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
    }
}
