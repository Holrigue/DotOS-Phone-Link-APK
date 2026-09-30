package com.holrigue.argdos.dashboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Receives a .gpx route shared from another app (e.g. Gaia GPS -> Share -> this
 * app) and streams it to the watch's /gpx over BLE via [GpxPush]. Shown as a
 * small confirmation screen with live status.
 */
class GpxShareActivity : ComponentActivity() {

    private var status by mutableStateOf("Preparing…")
    private var busy by mutableStateOf(true)
    private var pendingSend: (() -> Unit)? = null

    private val connectPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pendingSend?.invoke()
            else { status = "Bluetooth permission denied"; busy = false }
        }

    private companion object { const val MAX_BYTES = 512 * 1024 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DotTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Send route to watch", style = MaterialTheme.typography.titleLarge, color = DotWhite)
                        Text(status, style = MaterialTheme.typography.bodyMedium, color = DotGrey)
                        if (!busy) {
                            Button(onClick = { finish() }) { Text("Close") }
                        }
                    }
                }
            }
        }
        handleIntent()
    }

    private fun handleIntent() {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri == null) { status = "No file to send"; busy = false; return }

        val addr = Prefs.watchAddress(this)
        if (addr == null) {
            status = "Open ARGD-OS and connect to the watch once first."
            busy = false
            return
        }

        val name = displayName(uri)
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) { null }

        if (bytes == null || bytes.isEmpty()) { status = "Couldn't read the file"; busy = false; return }
        if (bytes.size > MAX_BYTES) { status = "Route too large (max 512 KB)"; busy = false; return }

        pendingSend = { send(addr, name, bytes) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            status = "Grant Bluetooth to send…"
            connectPermLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            pendingSend?.invoke()
        }
    }

    private fun send(addr: String, name: String, bytes: ByteArray) {
        busy = true
        status = "Sending \"$name\" to the watch…"
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { GpxPush.push(this@GpxShareActivity, addr, name, bytes) }
            status = if (ok) "Sent ✓  Open Apps ▸ GPX Track on the watch to follow it."
                     else "Send failed — is the watch in range and paired?"
            busy = false
        }
    }

    // Best-effort display name from the content provider; falls back to a .gpx name.
    private fun displayName(uri: Uri): String {
        var name: String? = null
        try {
            val c: Cursor? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            c?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = it.getString(idx)
                }
            }
        } catch (_: Exception) {}
        if (name.isNullOrBlank()) name = uri.lastPathSegment ?: "route.gpx"
        var n = name!!.substringAfterLast('/')
        if (!n.lowercase().endsWith(".gpx")) n = "$n.gpx"
        return n
    }
}
