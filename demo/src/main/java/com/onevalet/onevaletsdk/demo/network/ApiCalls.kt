package com.onevalet.onevaletsdk.demo.network

import com.onevalet.onevaletsdk.demo.network.ApiConfig.PORTAL_BASE_URL

/**
 * The Developer Portal's demo API — the sample's whole backend. Every call after
 * pairing carries the pairing token; the portal knows from it which building,
 * resident and virtual intercom this device is bound to, and proxies the calls
 * to the 1VALET Public API. Each call throws [NetworkError] (or a serialization
 * error) on failure, so callers wrap them in try/catch.
 */
object ApiCalls {

    private val client = NetworkClient()

    /** Start a pairing: returns the code to display and the pending token to wait on. */
    suspend fun createPairing(): CreatePairingResponse =
        client.post(urlString = "$PORTAL_BASE_URL/api/demo/pairings")

    /**
     * Listen for events. With the pending token this yields the "paired" event; with the
     * activated token it carries the "video-call" events (verbatim webhook payloads).
     * Suspends until [onEvent] returns false or the connection ends; callers reconnect
     * with backoff.
     */
    suspend fun listenForEvents(
        token: String,
        onEvent: suspend (event: String, data: String) -> Boolean,
    ) = client.readEventStream(
        urlString = "$PORTAL_BASE_URL/api/demo/events",
        bearerToken = token,
        onEvent = onEvent,
    )

    /**
     * Fetch an access token + participant id to join [roomId]. A 404 means the
     * room has no live call anymore — show "call ended", not an error.
     */
    suspend fun getTokenAndParticipant(roomId: String, pairingToken: String): TokenAndParticipantResponse =
        client.post(urlString = "$PORTAL_BASE_URL/api/demo/rooms/$roomId/tokens", bearerToken = pairingToken)

    /** Report a call-status transition (answered / hold / talking / busy / hangup). */
    suspend fun updateStatus(
        roomId: String,
        participantId: String,
        entrySystemId: String,
        status: CallStatus,
        pairingToken: String,
    ): Boolean =
        client.postForResult(
            urlString = "$PORTAL_BASE_URL/api/demo/rooms/$roomId/status",
            payload = UpdateCallStatusRequest(
                participantId = participantId,
                callStatus = status,
                entrySystemId = entrySystemId,
            ),
            bearerToken = pairingToken,
        )

    /** Unlock the calling intercom's door for the active call in [roomId]. */
    suspend fun unlock(roomId: String, entrySystemId: String, pairingToken: String): Boolean =
        client.postForResult(
            urlString = "$PORTAL_BASE_URL/api/demo/unlock",
            payload = UnlockRequest(room = roomId, entrySystemId = entrySystemId),
            bearerToken = pairingToken,
        )
}
