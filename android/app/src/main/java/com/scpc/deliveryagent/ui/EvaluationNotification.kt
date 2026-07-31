package com.scpc.deliveryagent.ui

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.scpc.deliveryagent.R

/**
 * Optional notification surface for an evaluation request already committed to
 * the app-local record of truth.
 *
 * This class never creates or advances a request. It only mirrors [pending] and
 * therefore cannot turn a denied permission, a killed process or a repeated
 * lifecycle callback into a completed or duplicate order.
 */
object EvaluationNotification {
    const val PERMISSION_REQUEST_CODE = 701

    private const val CHANNEL_ID = "evaluation_requests"
    private const val NOTIFICATION_ID = 701

    fun hasPermission(activity: Activity): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun requestPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission(activity)) {
            activity.requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                PERMISSION_REQUEST_CODE,
            )
        }
    }

    /** Makes the notification agree with durable product state, idempotently. */
    fun sync(activity: Activity, pending: Boolean) {
        val manager = activity.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                activity.getString(R.string.evaluation_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = activity.getString(R.string.evaluation_notification_channel_description)
            },
        )

        if (!pending) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (!hasPermission(activity)) return

        val openApp = PendingIntent.getActivity(
            activity,
            0,
            Intent(activity, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(activity, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(activity.getString(R.string.evaluation_notification_title))
                .setContentText(activity.getString(R.string.evaluation_notification_text))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }
}
