package com.smssync.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Reads newly-arrived MMS from content://mms/inbox and forwards them to the backend.
 *
 * The default SMS/MMS app on the device (not this app) is the one that actually downloads
 * MMS content from the carrier and writes it into this provider. Two independent triggers
 * call [drainNewMms] here: [MmsReceiver] (WAP_PUSH_RECEIVED, which some devices/OS versions
 * simply never deliver to a non-default app) and a ContentObserver registered from
 * [CommandSocketService] (fires on any change to content://mms regardless of WAP push
 * delivery, which is the reliable trigger on devices where the broadcast never arrives).
 */
object MmsForwarder {
    private const val TAG = "MmsForwarder"
    private const val POLL_INTERVAL_MS = 1500L
    private const val POLL_TIMEOUT_MS = 25_000L
    private const val MSG_TYPE_RETRIEVE_CONF = 132 // m_type once content is fully downloaded (vs. 130 = pending notification)

    private val client = OkHttpClient()
    private val gson = Gson()

    // Guards against concurrent triggers (a WAP push broadcast and a ContentObserver change,
    // or MMS arriving from different contacts at nearly the same time) forwarding the same
    // content://mms/inbox row twice.
    private val claimedMmsIds = mutableSetOf<Long>()

    private fun tryClaim(id: Long): Boolean = synchronized(claimedMmsIds) {
        claimedMmsIds.add(id)
    }

    private data class PendingMmsRow(val id: Long, val timestamp: Long, val subject: String, val msgType: Int)

    /**
     * Polls content://mms/inbox for every row newer than the last-forwarded checkpoint and
     * forwards each one independently as soon as its content finishes downloading (m_type
     * flips from NOTIFICATION_IND to RETRIEVE_CONF). Rows are claimed atomically so concurrent
     * invocations — multiple contacts sending MMS around the same time — never double-send or
     * steal each other's message. The checkpoint only advances past ids that are fully
     * resolved, so a slow-downloading MMS from one contact never gets skipped just because a
     * later id (a different contact) finished first. Safe to call repeatedly/concurrently from
     * multiple triggers (WAP push receiver, ContentObserver) — extra calls are cheap no-ops
     * once everything is caught up.
     */
    fun drainNewMms(context: Context) {
        val prefs = context.getSharedPreferences("SmsSyncPrefs", Context.MODE_PRIVATE)

        // First run ever (checkpoint never set): baseline past the existing inbox history so we
        // don't flood the backend with everything already on the device. Only rows still pending
        // download (m_type != RETRIEVE_CONF) are left above the baseline, since those are the
        // ones that just triggered this call and haven't been processed yet.
        if (!prefs.contains("last_mms_id")) {
            val historicalMax = queryPendingRows(context, 0L)
                .filter { it.msgType == MSG_TYPE_RETRIEVE_CONF }
                .maxOfOrNull { it.id } ?: 0L
            prefs.edit().putLong("last_mms_id", historicalMax).apply()
        }

        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS

        while (true) {
            val lastMmsId = prefs.getLong("last_mms_id", 0L)
            val rows = queryPendingRows(context, lastMmsId)

            for (row in rows) {
                if (row.msgType != MSG_TYPE_RETRIEVE_CONF) continue // still downloading
                if (!tryClaim(row.id)) continue // another trigger already handled it

                val sender = getMmsSender(context, row.id)
                val textBody = getMmsTextBody(context, row.id)
                val attachments = getMmsAttachments(context, row.id)

                Log.d(TAG, "MMS from: $sender, attachments: ${attachments.size}")
                sendMmsToBackend(context, sender, textBody, row.subject, row.timestamp, attachments)
            }

            // Only advance the checkpoint up to the oldest still-pending row (if any), so a
            // not-yet-downloaded MMS is never skipped by a later id resolving first.
            val newCheckpoint = rows.filter { it.msgType != MSG_TYPE_RETRIEVE_CONF }
                .minOfOrNull { it.id - 1 } ?: rows.maxOfOrNull { it.id }
            if (newCheckpoint != null && newCheckpoint > lastMmsId) {
                prefs.edit().putLong("last_mms_id", newCheckpoint).apply()
            }

            // Rows being empty does NOT mean "nothing to do": the notification row for a brand
            // new MMS is inserted by the default SMS app, not by us, and that insert can lag
            // behind this call. Keep polling until it shows up and resolves, instead of giving
            // up immediately and relying on some later trigger to pick it up "late".
            val stillWaiting = rows.isEmpty() || rows.any { it.msgType != MSG_TYPE_RETRIEVE_CONF }
            if (!stillWaiting) return
            if (System.currentTimeMillis() >= deadline) {
                if (rows.isEmpty()) {
                    Log.w(TAG, "No new MMS row appeared before timeout")
                } else {
                    Log.e(TAG, "Timed out waiting for MMS content to finish downloading")
                }
                return
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    private fun queryPendingRows(context: Context, afterId: Long): List<PendingMmsRow> {
        val rows = mutableListOf<PendingMmsRow>()
        context.contentResolver.query(
            Uri.parse("content://mms/inbox"),
            arrayOf("_id", "date", "sub", "m_type"),
            "_id > ?",
            arrayOf(afterId.toString()),
            "_id ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(
                    PendingMmsRow(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("_id")),
                        timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("date")) * 1000,
                        subject = cursor.getString(cursor.getColumnIndexOrThrow("sub")) ?: "",
                        msgType = cursor.getInt(cursor.getColumnIndexOrThrow("m_type"))
                    )
                )
            }
        }
        return rows
    }

    private const val SENDER_QUERY_RETRY_ATTEMPTS = 5
    private const val SENDER_QUERY_RETRY_DELAY_MS = 300L

    // The OS's MMS storage layer writes the pdu table's m_type and the addr table's
    // FROM row (type=137) as separate operations with no ordering guarantee visible to
    // this app, so the addr row can still be missing right after m_type flips to
    // RETRIEVE_CONF. Retry briefly instead of returning "unknown" on the first miss.
    private fun getMmsSender(context: Context, mmsId: Long): String {
        repeat(SENDER_QUERY_RETRY_ATTEMPTS) { attempt ->
            val sender = queryMmsSenderOnce(context, mmsId)
            if (sender != null) {
                if (attempt > 0) Log.d(TAG, "MMS $mmsId: sender resolved on attempt ${attempt + 1}")
                return sender
            }
            if (attempt < SENDER_QUERY_RETRY_ATTEMPTS - 1) Thread.sleep(SENDER_QUERY_RETRY_DELAY_MS)
        }
        Log.w(TAG, "MMS $mmsId: addr FROM row not resolved after $SENDER_QUERY_RETRY_ATTEMPTS attempts, falling back to unknown")
        return "unknown"
    }

    private fun queryMmsSenderOnce(context: Context, mmsId: Long): String? {
        return context.contentResolver.query(
            Uri.parse("content://mms/$mmsId/addr"),
            arrayOf("address"),
            "type = 137", // PduHeaders.FROM
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.trim()?.ifEmpty { null } else null
        }
    }

    private fun getMmsTextBody(context: Context, mmsId: Long): String {
        val sb = StringBuilder()
        context.contentResolver.query(
            Uri.parse("content://mms/part"),
            arrayOf("_id", "ct", "_data", "text"),
            "mid = ?",
            arrayOf(mmsId.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("ct")) == "text/plain") {
                    val data = cursor.getString(cursor.getColumnIndexOrThrow("_data"))
                    val text = if (data != null) {
                        readMmsTextPart(context, cursor.getLong(cursor.getColumnIndexOrThrow("_id")))
                    } else {
                        cursor.getString(cursor.getColumnIndexOrThrow("text")) ?: ""
                    }
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(text)
                }
            }
        }
        return sb.toString()
    }

    private fun readMmsTextPart(context: Context, partId: Long): String {
        return try {
            context.contentResolver.openInputStream(Uri.parse("content://mms/part/$partId"))
                ?.use { it.bufferedReader().readText() } ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Error reading MMS text part: ${e.message}")
            ""
        }
    }

    private val SUPPORTED_MEDIA = setOf(
        "image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp",
        "image/heic", "image/heif",
        "video/mp4", "video/3gpp", "audio/mpeg", "audio/amr", "audio/ogg"
    )

    private const val ATTACHMENT_QUERY_RETRY_ATTEMPTS = 5
    private const val ATTACHMENT_QUERY_RETRY_DELAY_MS = 300L

    // Same race as getMmsSender above: the OS flips m_type to RETRIEVE_CONF and inserts the
    // media `part` rows (image/video/audio binary) as separate, unordered operations, so a
    // media part can still be missing right after m_type flips. Retry briefly instead of
    // reporting "no attachments" on the first miss — a genuinely text-only MMS just stays
    // empty on every attempt, so this only adds latency when there's real media to wait for.
    private fun getMmsAttachments(context: Context, mmsId: Long): List<Map<String, String>> {
        repeat(ATTACHMENT_QUERY_RETRY_ATTEMPTS) { attempt ->
            val attachments = queryMmsAttachmentsOnce(context, mmsId)
            if (attachments.isNotEmpty()) {
                if (attempt > 0) Log.d(TAG, "MMS $mmsId: attachments resolved on attempt ${attempt + 1}")
                return attachments
            }
            if (attempt < ATTACHMENT_QUERY_RETRY_ATTEMPTS - 1) Thread.sleep(ATTACHMENT_QUERY_RETRY_DELAY_MS)
        }
        Log.w(TAG, "MMS $mmsId: no media parts found after $ATTACHMENT_QUERY_RETRY_ATTEMPTS attempts")
        return emptyList()
    }

    private fun queryMmsAttachmentsOnce(context: Context, mmsId: Long): List<Map<String, String>> {
        val attachments = mutableListOf<Map<String, String>>()
        var totalParts = 0
        context.contentResolver.query(
            Uri.parse("content://mms/part"),
            arrayOf("_id", "ct", "name"),
            "mid = ?",
            arrayOf(mmsId.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                totalParts++
                val ct = cursor.getString(cursor.getColumnIndexOrThrow("ct"))
                val partId = cursor.getLong(cursor.getColumnIndexOrThrow("_id"))
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name")) ?: "attachment"
                if (ct == null || ct !in SUPPORTED_MEDIA) {
                    Log.d(TAG, "MMS $mmsId: skipping part $partId, ct=$ct not in SUPPORTED_MEDIA")
                    continue
                }
                val data64 = readPartAsBase64(context, partId)
                if (data64 == null) {
                    Log.w(TAG, "MMS $mmsId: part $partId ct=$ct matched but could not be read")
                    continue
                }
                attachments.add(mapOf("contentType" to ct, "name" to name, "data" to data64))
                Log.d(TAG, "Attachment: $name ($ct, ${data64.length} b64 chars)")
            }
        }
        Log.d(TAG, "MMS $mmsId: $totalParts part row(s) found, ${attachments.size} matched as media")
        return attachments
    }

    private fun readPartAsBase64(context: Context, partId: Long): String? {
        return try {
            val stream = context.contentResolver.openInputStream(Uri.parse("content://mms/part/$partId"))
            if (stream == null) {
                Log.w(TAG, "openInputStream returned null for part $partId")
                return null
            }
            stream.use { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading attachment part $partId: ${e.message}")
            null
        }
    }

    private fun sendMmsToBackend(
        context: Context,
        sender: String,
        message: String,
        subject: String,
        timestamp: Long,
        attachments: List<Map<String, String>> = emptyList()
    ) {
        val prefs: SharedPreferences =
            context.getSharedPreferences("SmsSyncPrefs", Context.MODE_PRIVATE)
        val backendUrl = prefs.getString("backend_url", "")
        val apiSecret = prefs.getString("api_secret", "")

        if (backendUrl.isNullOrEmpty()) {
            Log.e(TAG, "Backend URL not configured")
            return
        }

        val mmsData = mutableMapOf<String, Any>(
            "sender" to sender,
            "message" to message,
            "timestamp" to timestamp,
            "type" to "MMS"
        )
        if (subject.isNotEmpty()) mmsData["subject"] = subject
        if (attachments.isNotEmpty()) mmsData["attachments"] = attachments

        val json = gson.toJson(mmsData)
        val requestBody = json.toRequestBody("application/json; charset=utf-8".toMediaType())

        val requestBuilder = Request.Builder()
            .url("$backendUrl/api/sms")
            .post(requestBody)

        if (!apiSecret.isNullOrEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiSecret")
        }

        client.newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to send MMS to backend: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.d(TAG, "MMS sent to backend successfully")
                } else {
                    Log.e(TAG, "Backend returned error: ${response.code}")
                }
                response.close()
            }
        })
    }
}
