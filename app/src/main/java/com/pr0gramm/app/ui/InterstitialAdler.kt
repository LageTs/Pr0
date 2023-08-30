package com.pr0gramm.app.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.pr0gramm.app.Logger

class InterstitialAdler(private val activity: Activity) {
    private val logger = Logger("InterstitialAdler")
    private val handler = Handler(Looper.getMainLooper())

    fun runWithAd(block: () -> Unit) {
        return block()
    }
}
