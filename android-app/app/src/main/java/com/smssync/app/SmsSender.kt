package com.smssync.app

import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import com.google.android.mms.MMSPart
import com.klinker.android.send_message.Transaction
import java.io.File

data class MmsAttachment(
    val contentType: String,
    val name: String,
    val data: String // base64-encoded bytes
)

/**
 * Sends SMS/MMS on behalf of a backend command, bypassing Transaction.sendNewMessage()
 * for both: that Klinker codepath builds PendingIntents without FLAG_IMMUTABLE/FLAG_MUTABLE,
 * which throws on API 31+ for SMS and is silently swallowed (its logger defaults to disabled)
 * for MMS. Transaction.getBytes() is still used to compose the MMS PDU bytes (no PendingIntent
 * involved there), but the actual send goes through SmsManager directly.
 */
object SmsSender {
    private const val TAG = "SmsSender"

    fun sendSms(context: Context, to: String, body: String) {
        val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
        val parts = smsManager.divideMessage(body)
        if (parts.size > 1) {
            smsManager.sendMultipartTextMessage(to, null, parts, null, null)
        } else {
            smsManager.sendTextMessage(to, null, body, null, null)
        }
        Log.d(TAG, "SMS sent to $to")
    }

    fun sendMms(
        context: Context,
        to: String,
        body: String,
        subject: String?,
        attachments: List<MmsAttachment>
    ) {
        val parts = mutableListOf<MMSPart>()
        if (body.isNotEmpty()) {
            parts.add(MMSPart().apply {
                Name = "text"
                MimeType = "text/plain"
                Data = body.toByteArray()
            })
        }
        for (attachment in attachments) {
            parts.add(MMSPart().apply {
                Name = attachment.name
                MimeType = attachment.contentType
                Data = Base64.decode(attachment.data, Base64.DEFAULT)
            })
        }

        val info = Transaction.getBytes(context, false, "", arrayOf(to), parts.toTypedArray(), subject)

        val fileName = "send.${System.currentTimeMillis()}.dat"
        val file = File(context.cacheDir, fileName).apply { writeBytes(info.bytes) }
        val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.MmsFileProvider", file)

        // sendMultimediaMessage() requires the mms service to be granted read access to the PDU file.
        context.grantUriPermission(
            "com.android.mms.service",
            contentUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
        smsManager.sendMultimediaMessage(context, contentUri, null, null, null)
        Log.d(TAG, "MMS sent to $to with ${attachments.size} attachment(s)")
    }
}
