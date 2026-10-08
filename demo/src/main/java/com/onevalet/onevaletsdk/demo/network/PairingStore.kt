package com.onevalet.onevaletsdk.demo.network

import android.content.Context
import android.content.SharedPreferences

/**
 * The saved pairing: the token the portal minted at activation plus the display names it
 * came with. The token is the credential for every demo API call — treat a real session
 * credential with more care than a demo's SharedPreferences.
 */
data class Pairing(
    val token: String,
    val occupantName: String,
    val buildingName: String,
)

/**
 * Persists the pairing across launches, so the app pairs once and then just reconnects.
 */
class PairingStore(context: Context) {

    private val preferences: SharedPreferences =
        context.getSharedPreferences("demo_pairing", Context.MODE_PRIVATE)

    fun load(): Pairing? {
        val token = preferences.getString(KEY_TOKEN, null) ?: return null
        return Pairing(
            token = token,
            occupantName = preferences.getString(KEY_OCCUPANT_NAME, "").orEmpty(),
            buildingName = preferences.getString(KEY_BUILDING_NAME, "").orEmpty(),
        )
    }

    fun save(pairing: Pairing) {
        preferences.edit()
            .putString(KEY_TOKEN, pairing.token)
            .putString(KEY_OCCUPANT_NAME, pairing.occupantName)
            .putString(KEY_BUILDING_NAME, pairing.buildingName)
            .apply()
    }

    /** Forget the pairing — after an expired/rejected token, or the Unpair button. */
    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val KEY_TOKEN = "pairing_token"
        const val KEY_OCCUPANT_NAME = "occupant_name"
        const val KEY_BUILDING_NAME = "building_name"
    }
}
