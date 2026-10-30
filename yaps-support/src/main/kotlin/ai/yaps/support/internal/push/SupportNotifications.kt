package ai.yaps.support.internal.push

import ai.yaps.support.R
import ai.yaps.support.SupportActivity
import ai.yaps.support.internal.core.Log
import ai.yaps.support.internal.core.PushRecognizer
import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Support notifications: their own channel, one group per conversation, and a tap that opens
 * [SupportActivity] directly. Android 12+ blocks activity starts from trampolines (receivers or
 * services started by a notification), so the PendingIntent targets the activity itself, stacked on
 * the host's launcher activity so Back returns to the app.
 */
internal object SupportNotifications {
    const val CHANNEL_ID = "yaps_support"
    private const val SUMMARY_ID = 0
    private const val GROUP_PREFIX = "ai.yaps.support.conversation."
    private const val TAG_PREFIX = "yaps_support:"

    /** Message lines per conversation for the group summary. In memory only; cleared when the chat opens. */
    private val lines = HashMap<String, ArrayDeque<String>>()

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.yaps_support_notification_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.yaps_support_notification_channel_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    @SuppressLint("MissingPermission") // canPost() checks it
    fun show(context: Context, data: Map<String, String>, smallIcon: Int, accentColor: Int?) {
        ensureChannel(context)
        if (!canPost(context)) {
            Log.i("Support push received but notifications are not permitted")
            return
        }
        val conversationId = PushRecognizer.conversationId(data) ?: "default"
        val messageId = PushRecognizer.messageId(data) ?: System.currentTimeMillis().toString()
        val title = data["title"]?.takeIf { it.isNotBlank() } ?: appLabel(context)
        val body = data["body"].orEmpty()
        val group = GROUP_PREFIX + conversationId
        val tag = TAG_PREFIX + conversationId
        val tap = contentIntent(context, conversationId)

        val history = synchronized(lines) {
            lines.getOrPut(conversationId) { ArrayDeque() }.apply {
                addLast(body)
                while (size > 6) removeFirst()
            }.toList()
        }

        val message = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setGroup(group)
            .setContentIntent(tap)
            .setNumber(PushRecognizer.badge(data) ?: 0)
            .apply { accentColor?.let(::setColor) }
            .build()

        val inbox = NotificationCompat.InboxStyle().setBigContentTitle(title)
        history.forEach { inbox.addLine(it) }
        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(inbox)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(group)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .apply { accentColor?.let(::setColor) }
            .build()

        try {
            val nm = NotificationManagerCompat.from(context)
            nm.notify(tag, messageId.hashCode().let { if (it == SUMMARY_ID) 1 else it }, message)
            nm.notify(tag, SUMMARY_ID, summary)
        } catch (e: SecurityException) {
            Log.w("Posting the support notification was refused", e)
        }
    }

    /** Clears every notification of [conversationId] (or all support notifications when `null`). */
    fun cancel(context: Context, conversationId: String?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        synchronized(lines) { if (conversationId == null) lines.clear() else lines.remove(conversationId) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                manager.activeNotifications
                    .filter { it.tag?.startsWith(TAG_PREFIX) == true && (conversationId == null || it.tag == TAG_PREFIX + conversationId) }
                    .forEach { manager.cancel(it.tag, it.id) }
            }
        }
    }

    private fun contentIntent(context: Context, conversationId: String): PendingIntent {
        val chat = SupportActivity.intent(context).apply {
            putExtra(SupportActivity.EXTRA_FROM_PUSH, true)
            putExtra(SupportActivity.EXTRA_CONVERSATION_ID, conversationId)
        }
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val requestCode = conversationId.hashCode()
        return if (launcher != null) {
            launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            PendingIntent.getActivities(context, requestCode, arrayOf(launcher, chat), flags)
        } else {
            chat.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            PendingIntent.getActivity(context, requestCode, chat, flags)
        }
    }

    private fun appLabel(context: Context): String =
        runCatching { context.applicationInfo.loadLabel(context.packageManager).toString() }.getOrDefault("")
}
