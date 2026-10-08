package com.onevalet.onevaletsdk.demo.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Call lifecycle status reported to the backend, sent by name on the wire
 * ("Answered", "Hold", ...) — the format the 1VALET Public API documents.
 */
@Serializable
enum class CallStatus {
    @SerialName("Answered")
    ANSWERED,

    @SerialName("Hold")
    HOLD,

    @SerialName("Talking")
    TALKING,

    /**
     * Declining a ringing call, or leaving one that never connected — this device did not
     * answer. The resident's other devices stop ringing.
     */
    @SerialName("Busy")
    BUSY,

    /**
     * Ending a call this device answered.
     */
    @SerialName("Hangup")
    HANGUP,
}
