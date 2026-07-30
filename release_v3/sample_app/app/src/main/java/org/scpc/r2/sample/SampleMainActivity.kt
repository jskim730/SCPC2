package org.scpc.r2.sample

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class SampleMainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = "SCPC Probe sample production surface"
                textSize = 18f
                setPadding(32, 64, 32, 32)
            },
        )
    }
}
