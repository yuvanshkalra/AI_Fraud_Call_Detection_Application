package com.fraudcalldetector.core.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object PermissionUtils {
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        context.startActivity(intent)
    }
}

object DateUtils {
    private val dateTimeFormatter = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun formatDateTime(timestampMs: Long): String =
        dateTimeFormatter.format(Date(timestampMs))

    fun formatDuration(durationMs: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(durationMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    /**
     * Returns a human-readable relative time string for [timestampMs].
     * e.g. "Just now", "2 min ago", "1 hr ago", "Yesterday", "Mar 5".
     */
    fun formatRelativeTime(timestampMs: Long): String {
        val diffMs = System.currentTimeMillis() - timestampMs
        return when {
            diffMs < 60_000L              -> "Just now"
            diffMs < 3_600_000L           -> "${diffMs / 60_000} min ago"
            diffMs < 86_400_000L          -> "${diffMs / 3_600_000} hr ago"
            diffMs < 172_800_000L         -> "Yesterday"
            else                          -> SimpleDateFormat("MMM d", Locale.getDefault())
                                                .format(Date(timestampMs))
        }
    }
}

object PhoneUtils {
    fun formatPhoneNumber(number: String): String {
        val digits = number.replace(Regex("[^\\d+]"), "")
        return when {
            digits.length == 10 -> "(${digits.substring(0, 3)}) ${digits.substring(3, 6)}-${digits.substring(6)}"
            digits.length == 11 && digits.startsWith("1") -> "+1 (${digits.substring(1, 4)}) ${digits.substring(4, 7)}-${digits.substring(7)}"
            else -> number
        }
    }
}
