package com.airwatch.wear

import android.app.Application

class AirApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Engine.init(this)
    }
}
