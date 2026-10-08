package com.onevalet.onevaletsdk.demo.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onevalet.onevaletsdk.demo.events.CallEventCoordinator
import com.onevalet.onevaletsdk.demo.events.PairingUiState

/**
 * Entry screen. Unpaired, it shows the pairing code to enter on the Developer
 * Portal's Demo app page; paired, it shows who this device rings for and that
 * calls only arrive while the app is open.
 */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val pairingState by CallEventCoordinator.state.collectAsStateWithLifecycle()

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted or not — the demo works either way */ }

    LaunchedEffect(Unit) {
        // Notification permission (Android 13+) for the full-screen incoming-call UI.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("OneValetSDK Demo", fontWeight = FontWeight.Bold)

        when (val state = pairingState) {
            PairingUiState.Loading -> {
                CircularProgressIndicator()
            }

            is PairingUiState.Unpaired -> {
                Text("Pair this device", fontWeight = FontWeight.SemiBold)
                Text(
                    "Sign in to the 1VALET Developer Portal, open Mobile SDK → Demo app, and " +
                        "enter this code to choose which resident this device rings for:",
                )
                if (state.code != null) {
                    Text(
                        text = state.code,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 8.sp,
                    )
                    Text("Waiting for the code to be entered…")
                } else if (state.hasError) {
                    Text("Could not reach the Developer Portal — retrying…")
                } else {
                    CircularProgressIndicator()
                }
            }

            is PairingUiState.Paired -> {
                Text("Paired", fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        append("This device rings for ")
                        append(state.occupantName.ifBlank { "the paired resident" })
                        if (state.buildingName.isNotBlank()) {
                            append(" in ${state.buildingName}")
                        }
                        append(".")
                    },
                )
                Text(if (state.connected) "Listening for calls." else "Reconnecting…")
                Text(
                    "Rings arrive while this app is open. A production integration " +
                        "delivers rings as push notifications instead, so they also " +
                        "arrive in the background.",
                    style = MaterialTheme.typography.bodySmall,
                )

                OutlinedButton(onClick = { CallEventCoordinator.unpair() }) {
                    Text("Unpair")
                }
            }
        }
    }
}
