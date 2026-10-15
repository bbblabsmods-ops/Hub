package com.bbblabs.hub

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "BBB Labs Hub is running"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            textSize = 24f
            gravity = Gravity.CENTER
        })
    }
}
