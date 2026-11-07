package com.example.audiovideotrimmer

import android.app.Application

class TrimmerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        AppLog.init(this)
    }
}
