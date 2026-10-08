package com.onevalet.onevaletsdk.demo.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// MARK: - Pairing

/** Response of `POST /api/demo/pairings`: the code to display and the token to wait on. */
@Serializable
data class CreatePairingResponse(
    val code: String,
    val pendingToken: String,
    val expiresAt: String,
)

/** A referenced entity in the "paired" event — id plus a display name. */
@Serializable
data class NamedRef(
    val id: String,
    val name: String? = null,
)

/**
 * Data of the "paired" SSE event: the activated pairing token this device uses from now
 * on, and who it now rings for. The names are for the UI; the token carries the binding.
 * No intercom here on purpose — which intercom is calling arrives with every ring event.
 */
@Serializable
data class PairedEventData(
    val pairingToken: String,
    val building: NamedRef,
    val occupant: NamedRef,
    val expiresAt: String,
)

// MARK: - Call events

/**
 * A "video-call" SSE event: the 1VALET `VideoCall` webhook payload, relayed verbatim by
 * the portal — PascalCase property names, exactly as a production backend receives it.
 */
@Serializable
data class CallEventPayload(
    @SerialName("EventType") val eventType: String? = null,
    @SerialName("EntrySystemId") val entrySystemId: String? = null,
    @SerialName("BuildingId") val buildingId: String? = null,
    @SerialName("OccupantId") val occupantId: String? = null,
    @SerialName("Room") val room: String = "",
    @SerialName("VideoCallState") val videoCallState: String = "",
    @SerialName("ParticipantId") val participantId: String? = null,
    @SerialName("Capabilities") val capabilities: CallCapabilities? = null,
)

/** What the call offers — false video means present the call as audio-only. */
@Serializable
data class CallCapabilities(
    @SerialName("Video") val video: Boolean = true,
    @SerialName("Audio") val audio: Boolean = true,
)

// MARK: - Call actions

@Serializable
data class UpdateCallStatusRequest(
    val participantId: String,
    val callStatus: CallStatus,

    /** The calling intercom, from the ring event's payload. */
    val entrySystemId: String,
)

/**
 * `room` ties the unlock to the video call it happened during: the backend uses it to
 * join the unlock to the call's audit timeline. `entrySystemId` is the calling intercom
 * from the ring event — the visitor is standing at that door.
 */
@Serializable
data class UnlockRequest(
    val room: String? = null,
    val entrySystemId: String,
)

// MARK: - Responses

@Serializable
data class TokenAndParticipantResponse(
    val token: String,
    val participantId: String,
)
