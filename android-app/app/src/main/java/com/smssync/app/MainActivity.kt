package com.smssync.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var backendUrlEditText: EditText
    private lateinit var apiSecretEditText: EditText
    private lateinit var statusTextView: TextView
    private lateinit var versionTextView: TextView
    private lateinit var saveButton: Button
    private lateinit var testButton: Button

    private val httpClient = OkHttpClient()
    private val gson = Gson()

    private val PERMISSION_REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        backendUrlEditText = findViewById(R.id.backendUrlEditText)
        apiSecretEditText = findViewById(R.id.apiSecretEditText)
        statusTextView = findViewById(R.id.statusTextView)
        versionTextView = findViewById(R.id.versionTextView)
        saveButton = findViewById(R.id.saveButton)
        testButton = findViewById(R.id.testButton)

        versionTextView.text = "v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"

        // Load saved backend URL
        val prefs = getSharedPreferences("SmsSyncPrefs", MODE_PRIVATE)
        val savedUrl = prefs.getString("backend_url", "")
        if (!savedUrl.isNullOrEmpty()) {
            backendUrlEditText.setText(savedUrl)
        } else {
            backendUrlEditText.setText("http://your-backend-url.com")
        }

        val savedSecret = prefs.getString("api_secret", "")
        if (!savedSecret.isNullOrEmpty()) {
            apiSecretEditText.setText(savedSecret)
        }

        saveButton.setOnClickListener {
            val url = backendUrlEditText.text.toString().trim()
            val secret = apiSecretEditText.text.toString().trim()
            if (url.isNotEmpty()) {
                prefs.edit()
                    .putString("backend_url", url)
                    .putString("api_secret", secret)
                    .apply()
                Toast.makeText(this, "Configuration saved!", Toast.LENGTH_SHORT).show()
                updateStatus("Backend URL: $url")
                restartCommandServiceIfReady()
            } else {
                Toast.makeText(this, "Please enter a valid URL", Toast.LENGTH_SHORT).show()
            }
        }

        testButton.setOnClickListener {
            val url = backendUrlEditText.text.toString().trim()
            val secret = apiSecretEditText.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "Enter a backend URL first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendTestSms(url, secret)
        }

        // Request permissions
        checkAndRequestPermissions()
        restartCommandServiceIfReady()
    }

    private fun sendTestSms(backendUrl: String, apiSecret: String) {
        updateStatus("Sending test SMS to $backendUrl ...")

        val payload = mapOf(
            "sender" to "+1000000000",
            "message" to "Test SMS from Android app at ${System.currentTimeMillis()}",
            "timestamp" to System.currentTimeMillis()
        )
        val body = gson.toJson(payload)
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val requestBuilder = Request.Builder()
            .url("$backendUrl/api/test-sms")
            .post(body)

        if (apiSecret.isNotEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiSecret")
        }

        val request = requestBuilder.build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    updateStatus("Test failed: ${e.message}")
                    Toast.makeText(this@MainActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                response.close()
                runOnUiThread {
                    if (code in 200..299) {
                        updateStatus("Test SMS sent successfully (HTTP $code)")
                        Toast.makeText(this@MainActivity, "Test SMS sent!", Toast.LENGTH_SHORT).show()
                    } else {
                        updateStatus("Backend returned HTTP $code")
                        Toast.makeText(this@MainActivity, "Backend error: HTTP $code", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_MMS,
            Manifest.permission.SEND_SMS,
            "android.permission.WRITE_SMS"
        )

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        } else {
            updateStatus("Permissions granted. SMS sync is active!")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                updateStatus("Permissions granted. SMS sync is active!")
                Toast.makeText(this, "SMS sync is now active!", Toast.LENGTH_SHORT).show()
                restartCommandServiceIfReady()
            } else {
                updateStatus("Permissions denied. Please grant SMS permissions.")
                Toast.makeText(
                    this,
                    "SMS permissions are required for the app to work",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun updateStatus(message: String) {
        statusTextView.text = message
    }

    private fun restartCommandServiceIfReady() {
        val prefs = getSharedPreferences("SmsSyncPrefs", MODE_PRIVATE)
        val backendUrl = prefs.getString("backend_url", "")
        val hasSendPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (!backendUrl.isNullOrEmpty() && hasSendPermission) {
            CommandSocketService.start(this)
        }
    }
}


