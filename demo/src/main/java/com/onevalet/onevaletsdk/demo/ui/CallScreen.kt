package com.onevalet.onevaletsdk.demo.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onevalet.onevaletsdk.calls.CallError
import com.onevalet.onevaletsdk.calls.CallManager
import com.onevalet.onevaletsdk.calls.CallState
import com.onevalet.onevaletsdk.calls.CallVideo
import com.onevalet.onevaletsdk.demo.events.CallEventCoordinator
import com.onevalet.onevaletsdk.demo.network.ApiCalls
import com.onevalet.onevaletsdk.demo.network.CallStatus
import com.onevalet.onevaletsdk.demo.network.NetworkError
import com.onevalet.onevaletsdk.demo.push.ActiveCallSession
import kotlinx.coroutines.launch

/**
 * Drives a [CallManager] end-to-end, mirroring the iOS `CallView`:
 *
 *  1. requests mic/camera permission,
 *  2. fetches an access token + participant id for [roomId],
 *  3. joins the room and renders remote video (with a mirrored self-view),
 *  4. reports call status (answered / hold / talking / busy) as the user acts,
 *  5. can unlock the entry-console door, mute, and leave.
 *
 * The room and the calling intercom arrive from the ring event (see
 * `CallEventCoordinator`); which resident this device answers for travels
 * inside the pairing token.
 */
@Composable
fun CallScreen(
    modifier: Modifier = Modifier,
    roomId: String,
    entrySystemId: String,
    onLeave: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val callManager = remember { CallManager(context) }
    val pairingToken = remember { CallEventCoordinator.pairingToken() }

    val state by callManager.state.collectAsStateWithLifecycle()
    val remoteTrack by callManager.remoteVideoTrack.collectAsStateWithLifecycle()
    val localTrack by callManager.localVideoTrack.collectAsStateWithLifecycle()

    var participantId by remember { mutableStateOf("") }
    var muted by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var permissionGranted by remember { mutableStateOf(false) }

    // Reports a status transition to the backend; failures are non-fatal.
    suspend fun reportStatus(status: CallStatus): Boolean =
        try {
            pairingToken != null && ApiCalls.updateStatus(
                roomId = roomId,
                participantId = participantId,
                entrySystemId = entrySystemId,
                status = status,
                pairingToken = pairingToken,
            )
        } catch (e: Exception) {
            false
        }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionGranted = result[Manifest.permission.CAMERA] == true &&
            result[Manifest.permission.RECORD_AUDIO] == true
        if (!permissionGranted) error = "Camera & microphone permission are required."
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
        )
    }

    // Once permission is granted: fetch the token, then join the room.
    LaunchedEffect(permissionGranted) {
        if (!permissionGranted) return@LaunchedEffect
        if (pairingToken == null) {
            error = "Not paired — pair this device first."
            return@LaunchedEffect
        }
        isBusy = true
        try {
            // Your code: fetch the access token from your backend. The SDK never
            // talks to a backend — token acquisition is always your app's job.
            val response = ApiCalls.getTokenAndParticipant(roomId = roomId, pairingToken = pairingToken)
            participantId = response.participantId

            callManager.joinRoom(
                roomId = roomId,
                token = response.token
            )
        } catch (e: CallError) {
            error = e.message ?: "Failed to connect."
        } catch (e: NetworkError.HttpError) {
            // 404: no live call for this room anymore — it ended before we joined.
            error = if (e.statusCode == 404) "Call ended." else "Could not fetch call credentials."
        } catch (e: Exception) {
            error = e.message ?: "Could not fetch call credentials."
        }
        isBusy = false
    }

    // Report "answered" once media is flowing; leave when the call ends.
    LaunchedEffect(state) {
        when (state) {
            CallState.ACTIVE -> reportStatus(CallStatus.ANSWERED)
            CallState.ENDED -> onLeave()
            else -> Unit
        }
    }

    // Mark this room as ours the moment the call screen opens — before the Answered status
    // report can race its own event echo back to this device (see CallEventCoordinator).
    DisposableEffect(roomId) {
        ActiveCallSession.answeredRoom = roomId
        onDispose {
            if (ActiveCallSession.answeredRoom == roomId) {
                ActiveCallSession.answeredRoom = null
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { callManager.release() }
    }

    LoadingOverlay(isShowing = isBusy) {
        Box(modifier = modifier.fillMaxSize()) {
            // Remote video fills the screen once flowing.
            CallVideo(track = remoteTrack, modifier = Modifier.fillMaxSize())

            // Local self-view (mirrored).
            CallVideo(
                track = localTrack,
                mirror = true,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .fillMaxWidth(0.32f),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val statusText = error ?: when (state) {
                    CallState.IDLE -> "Idle"
                    CallState.CONNECTING -> "Connecting…"
                    CallState.ACTIVE -> "Connected"
                    CallState.ENDED -> "Call ended"
                }
                Text(statusText, color = Color.White)

                Button(
                    onClick = {
                        val nextMuted = !muted
                        scope.launch {
                            isBusy = true
                            // Match iOS: hold when muting, talking when unmuting.
                            val ok = reportStatus(if (nextMuted) CallStatus.HOLD else CallStatus.TALKING)
                            if (ok) {
                                muted = nextMuted
                                if (muted) callManager.muteMicrophone() else callManager.unmuteMicrophone()
                            }
                            isBusy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (muted) "Unmute" else "Mute") }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            isBusy = true
                            try {
                                // The calling intercom's door; `room` ties the unlock to
                                // this call's audit timeline.
                                val success = pairingToken != null &&
                                    ApiCalls.unlock(
                                        roomId = roomId,
                                        entrySystemId = entrySystemId,
                                        pairingToken = pairingToken,
                                    )
                                error = if (success) null else "Unlock failed."
                            } catch (e: Exception) {
                                error = "Unlock failed: ${e.message}"
                            }
                            isBusy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Unlock door") }

                Button(
                    onClick = {
                        scope.launch {
                            isBusy = true
                            // Answered on this device → HANGUP. Never connected → BUSY (a decline).
                            reportStatus(if (state == CallState.ACTIVE) CallStatus.HANGUP else CallStatus.BUSY)
                            callManager.disconnect()
                            isBusy = false
                            onLeave()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("End call") }
            }
        }
    }
}
