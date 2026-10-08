package com.onevalet.onevaletsdk.demo.push

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onevalet.onevaletsdk.demo.CallActivity
import com.onevalet.onevaletsdk.demo.events.CallEventCoordinator

/**
 * The full-screen incoming-call screen shown when a ring event arrives. Appears
 * over the lock screen and can turn the screen on. "Answer" launches
 * [CallActivity] (which is where the SDK joins the room); "Decline" dismisses
 * and reports Busy, so the resident's other devices stop ringing. How you
 * present an incoming call is entirely your app's choice — this is one example.
 */
class IncomingCallActivity : ComponentActivity() {

    private var roomId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over the lock screen and wake the device, like an incoming call.
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        roomId = intent.getStringExtra(EXTRA_ROOM_ID).orEmpty()
        val entrySystemId = intent.getStringExtra(EXTRA_ENTRY_SYSTEM_ID).orEmpty()
        active = this

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    IncomingCallUi(
                        onAnswer = {
                            IncomingCallNotifier.cancel(this)
                            startActivity(
                                CallActivity.intent(this, roomId, entrySystemId)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            finish()
                        },
                        onDecline = {
                            IncomingCallNotifier.cancel(this)
                            // Busy stops the resident's other devices ringing; runs on a
                            // scope that outlives this (immediately finished) activity.
                            CallEventCoordinator.reportDecline(roomId, entrySystemId)
                            finish()
                        },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ROOM_ID = "extra_room_id"
        const val EXTRA_ENTRY_SYSTEM_ID = "extra_entry_system_id"

        /**
         * The instance currently ringing, so a MissedCall push can dismiss it. Set in
         * onCreate and cleared in onDestroy — only ever one incoming-call screen at a time.
         * Main thread only: [dismissIfRinging] hops there before reading it.
         */
        private var active: IncomingCallActivity? = null

        /**
         * Finishes the ringing screen if it is showing [roomId]'s call (a blank room
         * dismisses whatever is ringing). An already-answered call lives in CallActivity
         * and is deliberately unaffected.
         *
         * Safe from any thread: call events arrive on an IO thread, so the check and the
         * finish() are posted to the main thread, where the activity's state lives.
         */
        fun dismissIfRinging(roomId: String) {
            Handler(Looper.getMainLooper()).post {
                val current = active ?: return@post
                if (roomId.isBlank() || current.roomId == roomId) current.finish()
            }
        }

        fun intent(context: Context, roomId: String, entrySystemId: String): Intent =
            Intent(context, IncomingCallActivity::class.java).apply {
                putExtra(EXTRA_ROOM_ID, roomId)
                putExtra(EXTRA_ENTRY_SYSTEM_ID, entrySystemId)
            }
    }
}

@Composable
private fun IncomingCallUi(
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.padding(top = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Incoming call", style = MaterialTheme.typography.titleMedium)
            Text("Demo Caller", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onAnswer,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
            ) { Text("Answer") }

            Button(
                onClick = onDecline,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
            ) { Text("Decline") }
        }
    }
}
