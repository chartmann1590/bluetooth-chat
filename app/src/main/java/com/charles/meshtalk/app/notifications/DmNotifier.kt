package com.charles.meshtalk.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.charles.meshtalk.app.EXTRA_OPEN_DM_PEER_KEY
import com.charles.meshtalk.app.MainActivity

/** Posts a heads-up-capable notification for an incoming DM; separate from the low-importance
 * foreground-service "mesh networking" channel so it can actually alert the user. */
object DmNotifier {
    private const val CHANNEL_ID = "dm_messages"

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Direct messages", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    fun notifyNewDm(context: Context, peerKeyHex: String, senderNickname: String, preview: String) {
        ensureChannel(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setPackage(context.packageName)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_DM_PEER_KEY, peerKeyHex)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, peerKeyHex.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(senderNickname)
            .setContentText(preview)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(peerKeyHex.hashCode(), notification)
    }
}
