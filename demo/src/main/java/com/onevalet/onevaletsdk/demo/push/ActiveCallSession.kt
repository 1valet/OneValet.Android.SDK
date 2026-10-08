package com.onevalet.onevaletsdk.demo.push

/**
 * Tracks the room THIS device is answering, so the "Answered" push — which the backend fans out
 * to every device, including the one whose own status report triggered it — can be told apart
 * from "answered on another device". Set the moment the call screen opens (before the status
 * report races the push back to us) and cleared when it closes.
 *
 * The push also carries the answerer's `participantId` (the id minted with its call token); an
 * app holding on to its token response could compare that instead — this room-keyed flag is
 * just the simplest correct implementation for the demo.
 */
object ActiveCallSession {

    @Volatile
    var answeredRoom: String? = null
}
