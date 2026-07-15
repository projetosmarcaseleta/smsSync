package com.smssync.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fires on WAP_PUSH_RECEIVED/DELIVER when the OS actually delivers it to this app. On some
 * devices/OS versions it never does (this app isn't the default SMS/MMS handler), which is why
 * [CommandSocketService] also drives [MmsForwarder] via a ContentObserver as a reliable backup.
 */
class MmsReceiver : BroadcastReceiver() {

    private val TAG = "MmsReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val mimeType = intent.type ?: return

        if ((action == "android.provider.Telephony.WAP_PUSH_RECEIVED" ||
                    action == "android.provider.Telephony.WAP_PUSH_DELIVER") &&
            mimeType == "application/vnd.wap.mms-message"
        ) {
            Log.d(TAG, "MMS WAP push received")
            val pendingResult = goAsync()
            val appContext = context.applicationContext
            Thread {
                try {
                    MmsForwarder.drainNewMms(appContext)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing MMS: ${e.message}")
                } finally {
                    pendingResult.finish()
                }
            }.start()
        }
    }
}
