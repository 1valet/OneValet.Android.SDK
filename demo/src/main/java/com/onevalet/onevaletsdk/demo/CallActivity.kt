package com.onevalet.onevaletsdk.demo

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.onevalet.onevaletsdk.demo.ui.CallScreen

/**
 * Hosts the in-call UI, launched when the user answers an incoming call. It can
 * appear over the lock screen so an answered call is shown immediately.
 */
class CallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val roomId = intent.getStringExtra(EXTRA_ROOM_ID).orEmpty()
        val entrySystemId = intent.getStringExtra(EXTRA_ENTRY_SYSTEM_ID).orEmpty()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CallScreen(
                        modifier = Modifier.fillMaxSize(),
                        roomId = roomId,
                        entrySystemId = entrySystemId,
                        onLeave = { finish() },
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_ROOM_ID = "extra_room_id"
        const val EXTRA_ENTRY_SYSTEM_ID = "extra_entry_system_id"

        fun intent(context: Context, roomId: String, entrySystemId: String): Intent =
            Intent(context, CallActivity::class.java).apply {
                putExtra(EXTRA_ROOM_ID, roomId)
                putExtra(EXTRA_ENTRY_SYSTEM_ID, entrySystemId)
            }
    }
}
