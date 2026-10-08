package com.onevalet.onevaletsdk.demo

import android.app.Application
import com.onevalet.onevaletsdk.demo.events.CallEventCoordinator

class DemoApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Pairs the device with the Developer Portal and listens for call events
        // whenever the app is in the foreground.
        CallEventCoordinator.start(this)
    }
}
