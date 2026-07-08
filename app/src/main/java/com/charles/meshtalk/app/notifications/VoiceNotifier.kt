package com.charles.meshtalk.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.charles.meshtalk.app.EXTRA_OPEN_WALKIE_TALKIE_TARGET
import com.charles.meshtalk.app.MainActivity

/** Posts a heads-up, sound-alerting notification for an incoming voice message, and deep-links
 * into the Walkie-Talkie tab (not the text DM thread, which doesn't show voice clips at all). */
object VoiceNotifier {
    // "v2" because notification channel sound/attributes are locked after first creation on
    // Android — the "voice_messages" channel from initial testing had an invalid AudioAttributes
    // combination that silently failed to play a sound, so this bumps to a fresh channel id.
    private const val CHANNEL_ID = "voice_messages_v2"

    /** Sentinel [EXTRA_OPEN_WALKIE_TALKIE_TARGET] value meaning "open the Public tab", as opposed
     * to a specific peer's signing-pubkey hex for a DM voice message. */
    const val TARGET_PUBLIC = "public"

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, "Voice messages", NotificationManager.IMPORTANCE_HIGH)
            // USAGE_NOTIFICATION_COMMUNICATION_INSTANT paired with CONTENT_TYPE_SONIFICATION is an
            // invalid AudioAttributes combination on-device (confirmed via logcat: "AudioSystem:
            // invalid attributes ... when converting to stream" — the sound silently failed to
            // play). USAGE_NOTIFICATION is the standard, well-supported pairing.
            channel.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            channel.enableVibration(true)
            manager.createNotificationChannel(channel)
        }
    }

    /** [target] is [TARGET_PUBLIC] or the sender's signing-pubkey hex for a DM — also used as the
     * deep-link target and the notification id, so a new clip from the same sender/feed replaces
     * (rather than stacks under) the previous one, matching "only the last transmission matters". */
    fun notifyNewVoice(context: Context, target: String, senderNickname: String) {
        ensureChannel(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_WALKIE_TALKIE_TARGET, target)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, target.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(senderNickname)
            .setContentText("🔊 Voice message")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(target.hashCode(), notification)
    }
}
