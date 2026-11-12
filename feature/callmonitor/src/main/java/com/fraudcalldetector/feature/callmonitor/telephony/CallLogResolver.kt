package com.fraudcalldetector.feature.callmonitor.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CallLog
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * Reads the most recent call-log entry to determine:
 *   • Whether the last call was outgoing
 *   • The phone number when the system cannot supply it (API 31+)
 *
 * Requires READ_CALL_LOG permission; returns null gracefully when absent.
 */
internal object CallLogResolver {

    /**
     * Returns the phone number of the most recent call-log entry, or null.
     * Used to recover the number on API 31+ where [TelephonyCallback] omits it.
     */
    fun getMostRecentNumber(context: Context): String? {
        if (!hasCallLogPermission(context)) return null
        return queryCallLog(context, limit = 1) { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
            } else null
        }
    }

    /**
     * Returns true if the most recent call-log entry is an outgoing call.
     */
    fun isMostRecentCallOutgoing(context: Context): Boolean {
        if (!hasCallLogPermission(context)) return false
        return queryCallLog(context, limit = 1) { cursor ->
            if (cursor.moveToFirst()) {
                val type = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                type == CallLog.Calls.OUTGOING_TYPE
            } else false
        } ?: false
    }

    /**
     * Returns the duration (seconds) of the most recent call-log entry.
     * Used to cross-check active-call duration when ENDED transition arrives.
     */
    fun getMostRecentDurationSeconds(context: Context): Long {
        if (!hasCallLogPermission(context)) return 0L
        return queryCallLog(context, limit = 1) { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION))
            } else 0L
        } ?: 0L
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun <T> queryCallLog(
        context: Context,
        limit  : Int,
        block  : (Cursor) -> T
    ): T? {
        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION),
                null, null,
                "${CallLog.Calls.DATE} DESC LIMIT $limit"
            )?.use(block)
        } catch (e: SecurityException) {
            Timber.w("CallLog query denied: ${e.message}")
            null
        } catch (e: Exception) {
            Timber.e(e, "CallLog query failed")
            null
        }
    }

    private fun hasCallLogPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED
}
