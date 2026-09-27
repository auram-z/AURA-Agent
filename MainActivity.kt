package com.aura.agent

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val r = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 48, 32, 32)
            setBackgroundColor(Color.rgb(12, 16, 22))
        }

        val t = TextView(this).apply {
            text = "AURA"
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val s = TextView(this).apply {
            text = "دستیار شخصی هوشمند — هسته اولیه"
            textSize = 17f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }

        val i = EditText(this).apply {
            hint = "با AURA صحبت کنید..."
            textSize = 18f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            gravity = Gravity.RIGHT
        }

        val x = Button(this).apply {
            text = "ارسال"
        }

        val o = TextView(this).apply {
            text = "سلام. من AURA هستم.\nهسته اولیه آماده است."
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.RIGHT
            setPadding(0, 32, 0, 0)
        }

        x.setOnClickListener {
            val q = i.text.toString().trim()

            if (q.isNotEmpty()) {
                o.text = "پیام دریافت شد:\n$q\n\nهسته AI در مرحله اتصال سرویس‌هاست."
                i.text.clear()
            }
        }

        r.addView(t)
        r.addView(s)
        r.addView(
            i,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        r.addView(x)
        r.addView(
            o,
            LinearLayout.LayoutParams(-1, 0, 2f)
        )

        setContentView(r)
    }
}
