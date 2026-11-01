package com.callagent.gateway.sms

import android.content.Context
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import com.callagent.gateway.service.CallLogEntry
import com.callagent.gateway.service.CallLogStore
import org.json.JSONObject
import kotlin.math.abs

/**
 * A second path for received SMS when an SMS_RECEIVED broadcast or the
 * Asterisk SIP-202 -> AGI handoff is lost. Only messages inserted after this
 * feature's first-run baseline are considered; old inbox rows are never
 * replayed merely because a new APK was installed.
 *
 * New rows missed by the broadcast are queued. Rows accepted by SIP are
 * audited twice using the same ID, after 1 minute and then 24 hours. The
 * PBX durable inbox deduplicates both audits. A 30-day horizon caps the scan.
 */
object SmsInboxReconciler {
    private const val TAG = "SmsInboxReconciler"
    private const val PREFS = "sms_inbox_reconcile"
    private const val KEY_BASELINE_ID = "baseline_id"
    private const val KEY_AUDITS = "audits"
    private const val FIRST_AUDIT_MS = 60L * 1000
    private const val SECOND_AUDIT_MS = 24L * 60 * 60 * 1000
    private const val HORIZON_MS = 30L * 24 * 60 * 60 * 1000

    @Synchronized
    fun scan(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_BASELINE_ID)) {
            val cursor = context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf("_id"), null, null, "_id DESC"
            ) ?: throw IllegalStateException("SMS inbox query returned null")
            val baseline = cursor.use { if (it.moveToFirst()) it.getLong(0) else 0L }
            check(prefs.edit().putLong(KEY_BASELINE_ID, baseline).commit()) {
                "Could not persist SMS inbox reconciliation baseline"
            }
            Log.i(TAG, "SMS inbox baseline saved; existing rows through id=$baseline will not replay")
            return 0
        }

        val baseline = prefs.getLong(KEY_BASELINE_ID, 0L)
        val projection = arrayOf("_id", "address", "body", "date", "date_sent", "sub_id")
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection, "_id > ?", arrayOf(baseline.toString()), "_id ASC"
        ) ?: throw IllegalStateException("SMS inbox query returned null")

        val existingLog = CallLogStore.getEntries(context)
            .filter { it.type == CallLogStore.TYPE_SMS && it.direction == "IN" }
        val pending = SmsStore.pending(context).map { it.id }.toMutableSet()
        val audits = JSONObject(prefs.getString(KEY_AUDITS, "{}") ?: "{}")
        val now = System.currentTimeMillis()
        val cutoff = now - HORIZON_MS
        val activeRows = mutableSetOf<String>()
        var queued = 0
        cursor.use { rows ->
            val idCol = rows.getColumnIndexOrThrow("_id")
            val addressCol = rows.getColumnIndexOrThrow("address")
            val bodyCol = rows.getColumnIndexOrThrow("body")
            val dateCol = rows.getColumnIndexOrThrow("date")
            val dateSentCol = rows.getColumnIndexOrThrow("date_sent")
            val subCol = rows.getColumnIndexOrThrow("sub_id")
            while (rows.moveToNext()) {
                val insertedAt = rows.getLong(dateCol)
                if (insertedAt < cutoff) continue
                val rowId = rows.getLong(idCol).toString()
                activeRows.add(rowId)
                val sender = rows.getString(addressCol) ?: ""
                val body = rows.getString(bodyCol) ?: ""
                val subId = rows.getInt(subCol)
                val dateSent = rows.getLong(dateSentCol)
                val receivedAt = if (dateSent > 0) dateSent else insertedAt
                val seen = existingLog.firstOrNull {
                    it.number == sender && it.text == body &&
                        (abs(it.timestamp - receivedAt) < 5000L ||
                            abs(it.timestamp - insertedAt) < 5000L)
                }
                val smsId = seen?.smsId?.takeIf { it.isNotEmpty() }
                    ?: SmsStore.idForInbound(sender, subId, receivedAt, body)
                if (pending.contains(smsId)) continue
                val acceptedAt = SmsStore.acceptedAt(context, smsId)
                val auditCount = audits.optInt(rowId, 0)
                if (acceptedAt > 0) {
                    if (auditCount >= 2) continue
                    val delay = if (auditCount == 0) FIRST_AUDIT_MS else SECOND_AUDIT_MS
                    if (now - acceptedAt < delay) continue
                } else if (now - insertedAt < 2L * 60 * 1000) {
                    // Give SMS_RECEIVED and the default SMS app time to finish.
                    continue
                }

                val sim = runCatching {
                    context.getSystemService(SubscriptionManager::class.java)
                        ?.getActiveSubscriptionInfo(subId)
                }.getOrNull()
                val sms = PendingSms(
                    id = smsId,
                    from = sender,
                    to = sim?.number.orEmpty(),
                    text = body,
                    receivedAt = receivedAt,
                    parts = seen?.parts?.takeIf { it > 0 } ?: 1,
                    subId = subId,
                    slot = sim?.simSlotIndex ?: seen?.simSlot ?: -1,
                    carrier = sim?.carrierName?.toString().orEmpty()
                )
                SmsStore.add(context, sms)
                pending.add(smsId)
                if (acceptedAt > 0) audits.put(rowId, auditCount + 1)
                if (seen == null) {
                    CallLogStore.addEntry(
                        context,
                        CallLogEntry(
                            direction = "IN", simSlot = sms.slot, number = sender,
                            timestamp = receivedAt, durationSec = 0,
                            type = CallLogStore.TYPE_SMS, text = body,
                            smsId = smsId, parts = sms.parts
                        )
                    )
                }
                queued++
            }
        }
        val auditKeys = audits.keys()
        val stale = mutableListOf<String>()
        while (auditKeys.hasNext()) {
            val key = auditKeys.next()
            if (key !in activeRows) stale.add(key)
        }
        stale.forEach { audits.remove(it) }
        check(prefs.edit().putString(KEY_AUDITS, audits.toString()).commit()) {
            "Could not persist SMS inbox audit state"
        }
        if (queued > 0) Log.w(TAG, "SMS inbox reconciliation queued $queued message(s)")
        return queued
    }
}
