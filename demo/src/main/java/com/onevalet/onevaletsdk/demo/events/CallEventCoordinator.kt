package com.onevalet.onevaletsdk.demo.events

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.onevalet.onevaletsdk.demo.network.ApiCalls
import com.onevalet.onevaletsdk.demo.network.CallEventPayload
import com.onevalet.onevaletsdk.demo.network.CallStatus
import com.onevalet.onevaletsdk.demo.network.NetworkError
import com.onevalet.onevaletsdk.demo.network.PairedEventData
import com.onevalet.onevaletsdk.demo.network.Pairing
import com.onevalet.onevaletsdk.demo.network.PairingStore
import com.onevalet.onevaletsdk.demo.push.ActiveCallSession
import com.onevalet.onevaletsdk.demo.push.IncomingCallActivity
import com.onevalet.onevaletsdk.demo.push.IncomingCallNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** What the home screen renders — the pairing/connection state of this device. */
sealed interface PairingUiState {
    /** Startup, before the first connection attempt. */
    data object Loading : PairingUiState

    /**
     * Not paired: [code] is what the user enters on the portal's Demo app page
     * (null while a code is being requested), [hasError] when the portal is unreachable.
     */
    data class Unpaired(val code: String?, val hasError: Boolean = false) : PairingUiState

    /** Paired; [connected] is whether the event stream is currently open. */
    data class Paired(
        val occupantName: String,
        val buildingName: String,
        val connected: Boolean,
    ) : PairingUiState
}

/**
 * Owns the demo's connection to the Developer Portal: pairs the device, then holds the
 * SSE event stream open **while the app is in the foreground** and turns `video-call`
 * events into the ring/dismiss UI. This is the demo's stand-in for a push service — a
 * production integration receives these events as FCM pushes from its own backend and
 * can ring from the background; a socket the app holds cannot, which is why the home
 * screen says the app must stay open.
 */
object CallEventCoordinator {

    private const val TAG = "OneValetDemoEvents"

    /** VideoCallState value that rings this device. */
    private const val STATE_CALLING = "Calling"

    /** VideoCallState value sent when the call was answered (possibly by us). */
    private const val STATE_ANSWERED = "Answered"

    /** VideoCallState value sent when the ring window elapsed unanswered. */
    private const val STATE_MISSED_CALL = "MissedCall"

    private val json = Json { ignoreUnknownKeys = true }

    // Survives activity teardown, so a decline report finishes after the ringing
    // screen is gone.
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Context
    private lateinit var store: PairingStore

    private val _state = MutableStateFlow<PairingUiState>(PairingUiState.Loading)
    val state: StateFlow<PairingUiState> = _state

    /** Bumped to cancel and restart the connection loop (after Unpair). */
    private val generation = MutableStateFlow(0)

    /** Call once from [android.app.Application.onCreate]. */
    fun start(context: Context) {
        appContext = context.applicationContext
        store = PairingStore(appContext)

        val processLifecycle = ProcessLifecycleOwner.get()
        processLifecycle.lifecycleScope.launch {
            // The stream lives exactly as long as the app is visible: leaving the app
            // cancels it, coming back reconnects and re-pairs the UI state.
            processLifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                generation.collectLatest { runConnectionLoop() }
            }
        }
    }

    /** The saved pairing token, for the call actions (token/status/unlock). */
    fun pairingToken(): String? = store.load()?.token

    fun unpair() {
        store.clear()
        pendingSession = null
        generation.value++
    }

    /**
     * Declining a ring: fetch call credentials (for the participant id) and report Busy,
     * which stops the resident's other devices ringing. Best-effort — a 404 means the
     * call already ended.
     */
    fun reportDecline(roomId: String, entrySystemId: String) {
        val token = pairingToken() ?: return
        backgroundScope.launch {
            try {
                val credentials = ApiCalls.getTokenAndParticipant(roomId, token)
                ApiCalls.updateStatus(roomId, credentials.participantId, entrySystemId, CallStatus.BUSY, token)
            } catch (e: Exception) {
                Log.d(TAG, "Decline report skipped: ${e.message}")
            }
        }
    }

    /**
     * The pairing code currently on screen. Kept across background/foreground cycles so
     * the code the user may already be typing into the portal stays valid — a fresh one
     * is only minted when this expires (or its token is rejected). In memory on purpose:
     * codes are short-lived, so surviving process death buys nothing.
     */
    private var pendingSession: PendingSession? = null

    private data class PendingSession(val code: String, val token: String, val expiresAtEpochMillis: Long) {
        // A code about to expire isn't worth showing — the user would type it in vain.
        val isUsable get() = expiresAtEpochMillis - System.currentTimeMillis() > 30_000
    }

    /**
     * Lenient parse of the portal's `expiresAt`. A .NET `DateTime` with Kind=Unspecified
     * serializes without an offset (e.g. `2026-09-18T14:00:00.1234567`) — read that as UTC.
     * Anything unparseable falls back to the portal's 15-minute code lifetime rather than
     * throwing: a throw here lands in the generic retry path and mints a new pairing every
     * few seconds without ever showing a code.
     */
    private fun parsePortalDate(value: String): Long =
        runCatching { OffsetDateTime.parse(value).toInstant() }
            .recoverCatching { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC) }
            .getOrElse {
                Log.w(TAG, "Unparseable pairing expiresAt '$value'; assuming 15 minutes.")
                Instant.now().plus(Duration.ofMinutes(15))
            }
            .toEpochMilli()

    private suspend fun runConnectionLoop() {
        while (true) {
            val pairing = store.load()
            try {
                if (pairing == null) {
                    awaitPairing()
                } else {
                    listenForCalls(pairing)
                }
            } catch (e: NetworkError.HttpError) {
                if (e.statusCode == 401) {
                    if (pairing != null) {
                        // Expired or invalidated pairing — back to the code screen.
                        Log.w(TAG, "Pairing token rejected; unpairing.")
                        store.clear()
                    } else {
                        // The pending token itself was rejected — mint a fresh code.
                        pendingSession = null
                    }
                    continue
                }
                markDisconnected(pairing)
            } catch (e: Exception) {
                Log.w(TAG, "Event stream failed: ${e.message}")
                markDisconnected(pairing)
            }

            // Also reached when the server closes a healthy stream — reconnect calmly.
            delay(3_000)
        }
    }

    /**
     * Shows a pairing code (reusing the current one while it lasts) and waits on its
     * stream until the "paired" event lands — delivered live, or immediately on connect
     * when the code was activated while this app wasn't listening.
     */
    private suspend fun awaitPairing() {
        val session = pendingSession?.takeIf { it.isUsable } ?: run {
            _state.value = PairingUiState.Unpaired(code = null)
            val created = ApiCalls.createPairing()
            PendingSession(
                code = created.code,
                token = created.pendingToken,
                expiresAtEpochMillis = parsePortalDate(created.expiresAt),
            ).also { pendingSession = it }
        }

        _state.value = PairingUiState.Unpaired(code = session.code)

        ApiCalls.listenForEvents(session.token) { event, data ->
            if (event != "paired") {
                return@listenForEvents true
            }

            val paired = json.decodeFromString(PairedEventData.serializer(), data)
            store.save(
                Pairing(
                    token = paired.pairingToken,
                    occupantName = paired.occupant.name.orEmpty(),
                    buildingName = paired.building.name.orEmpty(),
                ),
            )
            pendingSession = null
            // Stop this stream; the loop reconnects with the activated token.
            false
        }

        // Stream over without pairing: drop the session if it has run out, so the next
        // iteration mints a fresh code instead of re-showing a dead one.
        if (pendingSession?.isUsable != true) {
            pendingSession = null
        }
    }

    private suspend fun listenForCalls(pairing: Pairing) {
        _state.value = PairingUiState.Paired(pairing.occupantName, pairing.buildingName, connected = true)

        ApiCalls.listenForEvents(pairing.token) { event, data ->
            if (event == "video-call") {
                handleCallEvent(json.decodeFromString(CallEventPayload.serializer(), data))
            }
            true
        }
    }

    /**
     * Rings this device. Events only arrive while the app is in the foreground, so the
     * ringing screen is started directly — that needs neither the notification permission
     * (API 33+) nor USE_FULL_SCREEN_INTENT (not auto-granted from API 34), without which
     * the notification alone would be a heads-up banner or nothing at all. The notification
     * is still posted for the ringtone and as a fallback if the launch is blocked (e.g. the
     * app went to the background a moment ago); it is skipped when the permission is denied.
     */
    private fun ring(room: String, entrySystemId: String) {
        appContext.startActivity(
            IncomingCallActivity.intent(appContext, room, entrySystemId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        IncomingCallNotifier.showIncomingCall(appContext, room, entrySystemId)
    }

    /**
     * One "Calling" event starts a call; exactly one other state ends it. Anything that
     * is not the ring means the ringing UI for that room must come down — the state says
     * how:
     *  - Answered: somebody took the call. If it was THIS device, the event is our own
     *    status report echoed back — ignore it. Otherwise dismiss and say so.
     *  - Busy (declined elsewhere) / Cancelled (visitor gave up): dismiss quietly.
     *  - MissedCall: nobody answered — dismiss and surface a missed-call notice.
     * An unknown state is treated as a dismiss: a ring must never outlive its call.
     * (The SDK isn't involved until the user answers and the room is joined — see CallScreen.)
     */
    private fun handleCallEvent(payload: CallEventPayload) {
        val room = payload.room
        Log.d(TAG, "Call event: state=${payload.videoCallState} room=$room")

        when (payload.videoCallState) {
            STATE_CALLING -> ring(room, payload.entrySystemId.orEmpty())

            STATE_ANSWERED -> {
                if (ActiveCallSession.answeredRoom == room) {
                    Log.d(TAG, "Answered echo for our own call — ignored")
                } else {
                    IncomingCallNotifier.dismiss(appContext, room)
                    IncomingCallNotifier.showInfo(appContext, "Call answered", "This call was answered on another device.")
                }
            }

            STATE_MISSED_CALL -> {
                IncomingCallNotifier.dismiss(appContext, room)
                IncomingCallNotifier.showInfo(appContext, "Missed call", "You missed a call from the entry system.")
            }

            else -> IncomingCallNotifier.dismiss(appContext, room)
        }
    }

    private fun markDisconnected(pairing: Pairing?) {
        _state.value = if (pairing == null) {
            PairingUiState.Unpaired(code = null, hasError = true)
        } else {
            PairingUiState.Paired(pairing.occupantName, pairing.buildingName, connected = false)
        }
    }
}
