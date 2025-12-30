package com.example.helloandroid

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

/**
 * Main activity for the Hello Android sample app.
 *
 * This demonstrates a simple Kotlin Android app built without Gradle.
 */
class MainActivity : Activity() {
    private var clickCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val textView = findViewById<TextView>(R.id.textView)
        val button = findViewById<Button>(R.id.button)

        textView.text = "Hello from Kotlin!"

        button.setOnClickListener {
            clickCount++
            textView.text = "Clicked $clickCount times!"
        }
    }
}
