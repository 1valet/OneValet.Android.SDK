package com.onevalet.onevaletsdk.demo.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Builds and posts the full-screen incoming-call notification. A high-importance
 * channel plus `setFullScreenIntent` makes Android surface [IncomingCallActivity]
 * over the lock screen when a ring event arrives. Notification presentation is
 * your app's responsibility; the SDK is not involved here.
 */
object IncomingCallNotifier {

    const val CHANNEL_ID = "incoming_calls"
    private const val INFO_CHANNEL_ID = "call_updates"
    private const val NOTIFICATION_ID = 4711
    private const val INFO_NOTIFICATION_ID = 4712

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Incoming calls",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Full-screen incoming video calls"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun showIncomingCall(context: Context, roomId: String, entrySystemId: String) {
        ensureChannel(context)

        val fullScreenIntent = IncomingCallActivity.intent(context, roomId, entrySystemId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            0,
            fullScreenIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Incoming call")
            .setContentText("Demo Caller")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(true)
            .setFullScreenIntent(pendingIntent, true)
            .build()

        if (hasNotificationPermission(context)) {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /**
     * Dismisses the incoming-call surface for [roomId] — both the notification and the
     * full-screen ringing activity — when a non-Calling state says the ring is over.
     */
    fun dismiss(context: Context, roomId: String) {
        cancel(context)
        IncomingCallActivity.dismissIfRinging(roomId)
    }

    /**
     * Posts an ordinary (non-ringing) notification about a call that is over — "answered on
     * another device", "missed call". Default importance: informative, not intrusive.
     */
    fun showInfo(context: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(INFO_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        INFO_CHANNEL_ID,
                        "Call updates",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply { description = "Missed calls and calls answered elsewhere" },
                )
            }
        }

        val notification = NotificationCompat.Builder(context, INFO_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()

        if (hasNotificationPermission(context)) {
            NotificationManagerCompat.from(context).notify(INFO_NOTIFICATION_ID, notification)
        }
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
