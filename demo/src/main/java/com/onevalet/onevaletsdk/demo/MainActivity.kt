package com.onevalet.onevaletsdk.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.onevalet.onevaletsdk.demo.ui.HomeScreen

/**
 * Single-activity host for the home screen. Calls run in their own
 * [CallActivity], launched when an incoming call is answered.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DemoApp()
                }
            }
        }
    }
}

@Composable
private fun DemoApp() {
    Scaffold { padding ->
        HomeScreen(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}
