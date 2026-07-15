package com.smssync.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

private data class SendCommand(
    val command: String?,
    val requestId: String?,
    val to: String?,
    val message: String?,
    val subject: String?,
    val attachments: List<AttachmentPayload>?
)

private data class AttachmentPayload(
    val contentType: String?,
    val name: String?,
    val data: String?
)

/**
 * Keeps a persistent WebSocket connection to the backend (role=device) and executes
 * send_sms/send_mms commands issued from there, acking the result back over the same socket.
 */
class CommandSocketService : Service() {

    private val TAG = "CommandSocketService"
    private val gson = Gson()
    private val handler = Handler(Looper.getMainLooper())

    private var httpClient: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var stopped = false
    private var reconnectDelayMs = 1000L

    // Watches content://mms directly instead of relying on the WAP_PUSH_RECEIVED broadcast,
    // which some devices/OS versions never deliver to a non-default SMS app (see MmsReceiver).
    // Any insert/update the default messaging app makes while downloading an MMS fires this,
    // regardless of whether the broadcast ever arrives.
    private var mmsObserverThread: HandlerThread? = null
    private var mmsObserver: ContentObserver? = null

    companion object {
        private const val CHANNEL_ID = "sms_sync_command_channel"
        private const val NOTIFICATION_ID = 42
        private const val MAX_RECONNECT_DELAY_MS = 30_000L

        fun start(context: Context) {
            val intent = Intent(context, CommandSocketService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CommandSocketService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat("Conectando ao backend...")
        httpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        connect()
        registerMmsObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "Service stopped")
        webSocket = null
        unregisterMmsObserver()
        super.onDestroy()
    }

    private fun registerMmsObserver() {
        val thread = HandlerThread("MmsObserverThread").apply { start() }
        mmsObserverThread = thread
        val observer = object : ContentObserver(Handler(thread.looper)) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val appContext = applicationContext
                Thread {
                    try {
                        MmsForwarder.drainNewMms(appContext)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing MMS from observer: ${e.message}")
                    }
                }.start()
            }
        }
        mmsObserver = observer
        contentResolver.registerContentObserver(Uri.parse("content://mms"), true, observer)
    }

    private fun unregisterMmsObserver() {
        mmsObserver?.let { contentResolver.unregisterContentObserver(it) }
        mmsObserver = null
        mmsObserverThread?.quitSafely()
        mmsObserverThread = null
    }

    private fun connect() {
        if (stopped) return

        val prefs = getSharedPreferences("SmsSyncPrefs", Context.MODE_PRIVATE)
        val backendUrl = prefs.getString("backend_url", "")
        val apiSecret = prefs.getString("api_secret", "")

        if (backendUrl.isNullOrEmpty()) {
            Log.w(TAG, "Backend URL not configured, stopping command socket")
            stopSelf()
            return
        }

        var wsUrl = toWsUrl(backendUrl) + "?role=device"
        if (!apiSecret.isNullOrEmpty()) {
            wsUrl += "&token=" + URLEncoder.encode(apiSecret, "UTF-8")
        }

        val request = Request.Builder().url(wsUrl).build()
        webSocket = httpClient?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Command socket connected")
                reconnectDelayMs = 1000L
                updateNotification("Conectado ao backend")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleCommand(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Command socket closed: $code $reason")
                updateNotification("Desconectado")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Command socket failure: ${t.message}")
                updateNotification("Erro de conexão, tentando novamente...")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (stopped) return
        handler.postDelayed({ connect() }, reconnectDelayMs)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    private fun handleCommand(webSocket: WebSocket, text: String) {
        val command = try {
            gson.fromJson(text, SendCommand::class.java)
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "Invalid command payload: ${e.message}")
            null
        } ?: return

        val to = command.to
        if (to.isNullOrEmpty()) return

        when (command.command) {
            "send_sms" -> runCatching {
                SmsSender.sendSms(applicationContext, to, command.message ?: "")
            }.fold(
                onSuccess = { ackResult(webSocket, command.requestId, true, null) },
                onFailure = { e -> ackResult(webSocket, command.requestId, false, e.message) }
            )

            "send_mms" -> runCatching {
                val attachments = command.attachments.orEmpty().mapNotNull { att ->
                    val data = att.data ?: return@mapNotNull null
                    MmsAttachment(
                        contentType = att.contentType ?: "application/octet-stream",
                        name = att.name ?: "attachment",
                        data = data
                    )
                }
                SmsSender.sendMms(applicationContext, to, command.message ?: "", command.subject, attachments)
            }.fold(
                onSuccess = { ackResult(webSocket, command.requestId, true, null) },
                onFailure = { e -> ackResult(webSocket, command.requestId, false, e.message) }
            )

            else -> Log.w(TAG, "Unknown command: ${command.command}")
        }
    }

    private fun ackResult(webSocket: WebSocket, requestId: String?, success: Boolean, error: String?) {
        val result = mapOf(
            "type" to "send_result",
            "requestId" to requestId,
            "success" to success,
            "error" to error
        )
        webSocket.send(gson.toJson(result))
    }

    private fun toWsUrl(url: String): String {
        return when {
            url.startsWith("https://") -> url.replaceFirst("https://", "wss://")
            url.startsWith("http://") -> url.replaceFirst("http://", "ws://")
            url.startsWith("ws://") || url.startsWith("wss://") -> url
            else -> "wss://$url"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SMS Sync - Envio remoto",
                NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SMS Sync")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun startForegroundCompat(status: String) {
        val notification = buildNotification(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(status))
    }
}
