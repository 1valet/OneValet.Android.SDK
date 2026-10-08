# OneValetSDK for Android

Two-way audio/video **Calls** for the 1VALET platform, as a reusable Android
library built with Kotlin coroutines and `StateFlow`, distributed as a single
`.aar`. This repository is the **integration reference**: the API
documentation below plus a complete, runnable [sample app](#sample-app).

The underlying video engine is wrapped internally and **never appears on the
public API** — callers only ever hold the SDK's own types (`CallManager`,
`CallVideoTrack`, `CallVideoView`). The engine is bundled inside the distributed
`.aar`, so you never declare it yourself — see [Installation](#installation).

- [Requirements](#requirements)
- [Installation](#installation)
- [Permissions](#permissions)
- [Quickstart](#quickstart)
- [Threading & lifecycle](#threading--lifecycle)
- [What the SDK does *not* do](#what-the-sdk-does-not-do)
- [API reference](#api-reference)
- [Error handling](#error-handling)
- [Troubleshooting](#troubleshooting)
- [Sample app](#sample-app)

---

## Requirements

| | |
|---|---|
| minSdk | 29 |
| compileSdk | 36 |
| JDK | 17 |
| Kotlin | 2.2.x |
| Dependencies | Video engine bundled; the rest you declare — see [Installation](#installation) |

The SDK enables **core library desugaring**, so the consuming app must enable it
too:

```groovy
android {
    compileOptions {
        coreLibraryDesugaringEnabled true
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}
dependencies {
    coreLibraryDesugaring "com.android.tools:desugar_jdk_libs:1.1.5"
}
```

> **minSdk note:** the SDK's minSdk is 29. If your app targets a lower minSdk,
> add `com.onevalet.onevaletsdk` to your manifest's
> `<uses-sdk tools:overrideLibrary="…"/>` and gate call code behind
> `Build.VERSION.SDK_INT >= 29`.

---

## Installation

Add the 1VALET Maven repository, then declare one dependency:

```groovy
// settings.gradle — inside dependencyResolutionManagement { repositories { … } }
mavenCentral()
maven { url "https://raw.githubusercontent.com/1valet/OneValetSDK-Android/maven" }
```

```groovy
// app/build.gradle
dependencies {
    implementation "com.onevalet:onevaletsdk:1.2.0"
}
```

That is the whole installation. Everything the SDK needs — Kotlin coroutines,
`androidx.core`, `appcompat`, the Compose UI artifacts, and the video engine —
is declared in the published POM and resolved for you.

> **No credentials, at build time.** Both the 1VALET repository and Maven Central
> are plain anonymous HTTPS; nothing here asks you to log in or hold a token. You
> do need a 1VALET API credential **at runtime**, because your backend calls our
> API to mint the access token you pass to `joinRoom` — see
> [Quickstart](#quickstart).

> **The video engine stays internal.** It arrives as a normal transitive
> dependency, but it is resolved at *runtime* scope, so it is deliberately absent
> from your compile classpath: you cannot `import` it, and it never appears on the
> SDK's public API. You only ever hold 1VALET's own types.

---

## Permissions

The SDK's manifest already declares the permissions it needs, so they merge into
your app automatically — you don't add them yourself:

| Permission | Needed for |
|---|---|
| `CAMERA` | Local camera video in calls. |
| `RECORD_AUDIO` | Microphone audio in calls. |
| `MODIFY_AUDIO_SETTINGS` | Routing call audio (communication mode). |

**Requesting the runtime permissions is the integrating app's responsibility** —
request `CAMERA` and `RECORD_AUDIO` before calling `joinRoom`.

---

## Quickstart

Calls run through an observable `CallManager`. Your backend obtains the room
credentials from the 1VALET API; the SDK connects, publishes local audio, and
exposes the remote video track for rendering.

> **You provide the credentials.** `roomId` and `token` come from the 1VALET API
> via **your** backend. Never hardcode or ship an access token in the app.

```kotlin
// 1. Construct and own the lifecycle (or use CallManager.shared(context)).
val calls = CallManager(context)

// 2. Join a room. The access token comes from YOUR backend.
lifecycleScope.launch {
    try {
        calls.joinRoom(roomId = roomId, token = token)
    } catch (e: CallError) {
        // FailedToConnect / DisconnectedBeforeConnecting
    }
}

// 3. Drive the UI from state + render remote video (Compose).
@Composable
fun CallScreen(calls: CallManager) {
    val state by calls.state.collectAsStateWithLifecycle()
    val remote by calls.remoteVideoTrack.collectAsStateWithLifecycle()
    val local by calls.localVideoTrack.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        CallVideo(track = remote, modifier = Modifier.fillMaxSize())
        CallVideo(track = local, mirror = true, modifier = Modifier.size(120.dp))
        Text(
            when (state) {
                CallState.IDLE -> "Idle"
                CallState.CONNECTING -> "Connecting…"
                CallState.ACTIVE -> "Connected"
                CallState.ENDED -> "Call ended"
            }
        )
    }
}

// 4. Controls.
calls.muteMicrophone(); calls.unmuteMicrophone()
calls.flipCamera()
calls.holdCall(onHold = true)
calls.disconnect()

// 5. Teardown — you own it.
calls.release()   // or calls.close()
```

Rendering from XML instead of Compose? Use the `CallVideoView` directly:

```kotlin
binding.remoteVideo.setTrack(remoteTrack)   // pass null to clear
binding.localVideo.mirror = true
```

---

## Threading & lifecycle

- All observable state is exposed as `StateFlow`; collect it from the UI
  (`collectAsStateWithLifecycle`, `repeatOnLifecycle`, …). The underlying media
  callbacks arrive on the main thread, so the published values update there.
- `CallManager` is **not a forced singleton** — build one, hold it while the
  call UI lives, and call `release()` / `close()` when done. A process-wide
  convenience instance is available via `CallManager.shared(context)` for apps
  that only ever run one call at a time.
- `joinRoom` is a `suspend` function that returns once the room is connected and
  throws `CallError` otherwise.

---

## What the SDK does *not* do

The SDK is the **media engine only**. Your app owns everything around it:

- **Access tokens.** The SDK does not talk to any backend. Fetch the access
  token (and room name) from your own backend and pass them to `joinRoom`.
- **Incoming-call push / notifications.** There is no FCM or full-screen
  incoming-call UI in the SDK — wire up your own push + notification flow and
  call `joinRoom` when the user answers. The [sample's `push/`](#sample-app)
  package shows the notification/full-screen-UI half; the push half (an FCM
  service fed by your backend's fan-out) is covered in the Developer Portal's
  "Ring your app" page.
- **Door unlock and other business actions.** These are plain requests your app
  makes through your backend. The SDK only provides `sendData(...)` if you want
  to signal the other participant over the in-call data channel.

---

## API reference

### `CallManager(context)` — `Closeable`

**State (`StateFlow`)**

| Property | Type | Meaning |
|---|---|---|
| `state` | `CallState` | Call lifecycle — the single source of truth for UI. |
| `remoteVideoTrack` | `CallVideoTrack?` | Remote party's video, or `null`. |
| `localVideoTrack` | `CallVideoTrack?` | Local camera track, or `null`. |
| `isRemoteCameraAvailable` | `Boolean` | Whether the remote party is publishing video. |
| `isLocalCameraAvailable` | `Boolean` | Whether the local camera is capturing. |
| `isUsingFrontCamera` | `Boolean` | Bind a local `CallVideoView`'s `mirror` to this. |
| `isMuted` | `Boolean` | Whether the local mic is muted. |

**Functions**

| Member | Description |
|---|---|
| `suspend joinRoom(roomId, token)` | Connects; suspends until established, throws `CallError`. |
| `disconnect()` | Leaves the room (`state` → `ENDED`). |
| `muteMicrophone()` / `unmuteMicrophone()` | Toggle the local mic; reflected in `isMuted`. |
| `holdCall(onHold)` | Hold/resume by toggling local audio + video; room stays connected. |
| `activateCamera()` / `initiateLocalVideo(): Boolean` | Create, then publish, the local camera track. |
| `deactivateCamera()` / `flipCamera()` | Stop / switch (front↔back) the local camera. |
| `enableAudioDevice()` / `disableAudioDevice()` | Route / restore call audio (e.g. around a Telecom `ConnectionService` audio session). |
| `setRemoteAudioPlaybackEnabled(enabled)` | Gate remote audio playback (set `false` before connecting to hold until answered). |
| `isLocalAudioEnabled()` / `isLocalVideoEnabled(): Boolean` | Current enabled state of the local tracks. |
| `sendData(message)` | Send a string to other participants over the data channel. |
| `release()` / `close()` | Tear down the call and free resources. You own this. |
| `CallManager.shared(context)` | Process-wide convenience instance. |

### `CallState`

| State | Meaning |
|---|---|
| `IDLE` | No call in progress. |
| `CONNECTING` | Connecting to the room; waiting for remote media. |
| `ACTIVE` | Remote media is flowing — the call is live. |
| `ENDED` | Remote party left or the connection failed. |

### `CallVideoView` / `CallVideo`

An opaque renderer for a `CallVideoTrack` (the underlying video view is kept
private inside it, scaled to fill).

```kotlin
// XML / View:
CallVideoView(context).apply { mirror = true; setTrack(track) }

// Compose:
CallVideo(track = track, modifier = Modifier.fillMaxSize(), mirror = false)
```

### `CallVideoTrack`

An opaque handle to a video track. You never construct it — collect it from
`remoteVideoTrack` / `localVideoTrack` and hand it to a `CallVideoView`.

### `OneValetSdk`

| Member | Description |
|---|---|
| `OneValetSdk.VERSION` | The SDK version of this build, e.g. `"1.2.0"`. |

Quote `OneValetSdk.VERSION` in bug reports. It is compiled into the artifact, so
it reports the build you are actually running even when the resolved version is
not obvious from your build files.

`0.0.0-dev` means an untagged local build rather than a release, and should never
appear in a version resolved from the repository.

---

## Error handling

`joinRoom` throws `CallError`:

| Case | When |
|---|---|
| `CallError.FailedToConnect(cause)` | The room failed to connect; the underlying error is attached as `cause`. |
| `CallError.DisconnectedBeforeConnecting` | Disconnected before the connection finished establishing. |

```kotlin
try {
    calls.joinRoom(roomId, token)
} catch (e: CallError.FailedToConnect) {
    // Show a retry UI; inspect e.cause.
} catch (e: CallError) {
    // DisconnectedBeforeConnecting, etc.
}
```

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Manifest merge fails on `minSdkVersion` | App minSdk < 29 — add `com.onevalet.onevaletsdk` to `tools:overrideLibrary` and gate calls behind API 29. |
| `joinRoom` never returns / throws `FailedToConnect` | Bad/expired `token`, wrong `roomId`, or the room isn't open yet — verify with your backend. |
| Black remote video but audio works | Remote party isn't publishing video — check `isRemoteCameraAvailable`. |
| Local preview looks un-mirrored | Bind `CallVideoView.mirror` to `isUsingFrontCamera`. |
| Desugaring errors at build time | Enable `coreLibraryDesugaringEnabled` in the consuming app (see [Requirements](#requirements)). |

---

## Sample app

The [`demo`](demo) module is a complete, runnable integration built on the SDK —
the app-side plumbing you would otherwise write yourself: the API client, the
incoming-call push handling, and the in-call UI.

Its SDK dependency is currently wired for **1VALET-internal development** (a
local `.aar` built from the sibling source), because no version has been
published yet. To build it the way you will build your own app, swap that line
for the commented coordinate in [`demo/build.gradle`](demo/build.gradle) — the
repository it resolves from is already declared in
[`settings.gradle`](settings.gradle).

The demo pairs with the **1VALET Developer Portal**: it displays a short code,
you enter it on the portal's Demo app page (Mobile SDK → Demo app) and pick the resident it rings
for, and from then on it receives real intercom calls — no backend of your own,
no Firebase project, no configuration beyond the portal URL in `ApiConfig`.

Rings arrive over an event stream the app holds open, so the demo rings
**while the app is in the foreground**. A production integration delivers
rings as push notifications from its own backend (FCM here, VoIP push on iOS),
which is what the Developer Portal's "Ring your app" page documents — the
notification and full-screen-UI code in `push/` is the same either way; only
the transport differs.

**What it shows**

- **Pairing + events** ([`demo/.../events`](demo/src/main/java/com/onevalet/onevaletsdk/demo/events)) —
  `CallEventCoordinator` pairs the device and holds the SSE stream open while
  the app is visible, turning `video-call` events (verbatim 1VALET webhook
  payloads) into ring/dismiss handling.
- **Demo API client** ([`demo/.../network`](demo/src/main/java/com/onevalet/onevaletsdk/demo/network)) —
  a small `HttpURLConnection` + `kotlinx.serialization` layer for the portal's
  demo API: pairing, the event stream, call tokens, status reports, and door
  unlock. The portal URL lives in `ApiConfig`.
- **Calls** — `CallScreen` fetches the token for a room, joins, renders remote
  video with a mirrored self-view, and offers mute / unlock / end.
- **Incoming-call UI** — the [`demo/.../push`](demo/src/main/java/com/onevalet/onevaletsdk/demo/push)
  package raises a full-screen `IncomingCallActivity` (started directly, since
  rings only arrive while the app is visible) and posts a high-priority call
  notification for the ringtone and as a fallback; answering launches `CallActivity`, declining reports Busy. The SDK itself
  does not provide the incoming-call UI.

**Running it**

> **Integrating, rather than working on the SDK?** Skip step 1. In
> `demo/build.gradle`, swap the local `.aar` line for the commented
> `implementation "com.onevalet:onevaletsdk:<version>"` above it — the repository
> it resolves from is already declared in `settings.gradle`. That is the supported
> path and needs no access to the SDK source.
>
> Step 1 is for 1VALET developers running the sample against unreleased SDK
> changes. It builds the bundled variant, because a local `.aar` carries no POM
> and so cannot pull the video engine in on its own.

1. **Install the SDK `.aar`.** The demo depends on
   `demo/libs/onevaletsdk-fat.aar`, which is deliberately **not** committed — it
   is a ~32 MiB build output, not source. One command builds it from the sibling
   `OneValetSDK.Android` project and copies it in:
   ```bash
   ./gradlew :demo:updateSdkAar
   ```
   Forget this on a fresh clone and the build stops with a message telling you to
   run exactly that, so there is nothing to memorise.

   **Re-run it whenever the SDK source changes.** Nothing detects a stale `.aar`
   — the demo will happily build against yesterday's SDK.

   If your SDK checkout is not beside this project, point at it:
   ```bash
   ./gradlew :demo:updateSdkAar -PoneValetSdkDir=/path/to/OneValetSDK.Android
   ```
2. Set the Developer Portal URL in
   [`ApiConfig`](demo/src/main/java/com/onevalet/onevaletsdk/demo/network/ApiConfig.kt)
   if you are not using the default.
3. Build/run, then pair the app from the portal's Demo app page:
   ```bash
   ./gradlew :demo:assembleDebug
   ```

---

## Support

Questions or integration help: **developer support @ 1VALET** (add your real
support channel / SLA here before publishing).
