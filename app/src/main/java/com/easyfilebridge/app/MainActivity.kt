package com.easyfilebridge.app

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.ServerSocket
import java.net.Socket
import java.net.NetworkInterface
import java.net.Inet4Address
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var links: TextView
    private var selectedUri: Uri? = null
    private var selectedName = "file"
    private var server: ServerSocket? = null
    private var shareLink = ""
    private val clients = Executors.newFixedThreadPool(2)
    private val activeSockets =
        java.util.Collections.synchronizedSet(mutableSetOf<Socket>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 64, 32, 32)
        }
        fun label(value: String, size: Float): TextView {
            return TextView(this).apply {
                text = value
                textSize = size
                gravity = Gravity.CENTER
                setPadding(0, 16, 0, 16)
                layout.addView(this)
            }
        }
        fun button(value: String, action: () -> Unit) {
            layout.addView(Button(this).apply {
                text = value
                setOnClickListener { action() }
            })
        }

        label("EasyFileBridge", 28f)
        label("Local file transfer / ফাইল শেয়ার", 18f)
        label(
            "দুই ফোন একই Wi-Fi-তে রাখুন অথবা এক ফোনের " +
                "হটস্পটে অন্য ফোন যুক্ত করুন।",
            16f
        )

        button("Select file / ফাইল বাছুন") {
            stopSharing()
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivityForResult(intent, 100)
        }

        status = label("কোনো ফাইল বাছাই করা হয়নি", 16f)
        button("Start sharing / শেয়ার চালু") { startSharing() }
        links = label("", 16f).apply {
            setTextIsSelectable(true)
        }
        button("Copy link / লিংক কপি") {
            if (shareLink.isNotEmpty()) {
                val clipboard =
                    getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("EasyFileBridge", shareLink)
                )
                status.text = "লিংক কপি হয়েছে"
            }
        }
        button("Stop sharing / শেয়ার বন্ধ") {
            stopSharing()
            status.text = "শেয়ার বন্ধ হয়েছে"
        }
        label(
            "শেয়ার করার সময় অ্যাপ খোলা রাখুন। " +
                "যার কাছে লিংক দেবেন, তিনি বাছাই করা ফাইল নিতে পারবেন।",
            15f
        )

        setContentView(ScrollView(this).apply {
            addView(layout)
        })
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 100 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        selectedUri = uri
        selectedName = "file"
        try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index =
                        cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        selectedName = cursor.getString(index) ?: "file"
                    }
                }
            }
        } catch (_: Exception) {
        }
        status.text = "Selected / বাছাই হয়েছে:\n$selectedName"
    }

    private fun startSharing() {
        val uri = selectedUri
        if (uri == null) {
            status.text = "আগে একটি ফাইল বাছুন"
            return
        }
        stopSharing()
        val name = selectedName
        val token = UUID.randomUUID().toString().replace("-", "")
        val socketServer: ServerSocket
        try {
            socketServer = ServerSocket(0)
            server = socketServer
        } catch (e: Exception) {
            status.text = "শেয়ার চালু হয়নি: ${e.message}"
            return
        }

        val port = socketServer.localPort
        val addresses = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                if (!network.isUp || network.isLoopback) continue
                val ips = network.inetAddresses
                while (ips.hasMoreElements()) {
                    val ip = ips.nextElement()
                    if (ip is Inet4Address && ip.isSiteLocalAddress) {
                        addresses.add(ip.hostAddress ?: continue)
                    }
                }
            }
        } catch (_: Exception) {
        }

        val localLink = "http://127.0.0.1:$port/$token"
        val networkLinks = addresses.distinct().map {
            "http://$it:$port/$token"
        }
        shareLink = networkLinks.firstOrNull() ?: localLink
        links.text =
            "অন্য ফোনের Chrome-এ লিখুন:\n" +
            (networkLinks.joinToString("\n").ifEmpty {
                "Wi-Fi বা হটস্পট চালু করে আবার শেয়ার চালু করুন"
            }) +
            "\n\nএই ফোনে পরীক্ষার লিংক:\n$localLink"
        status.text = "শেয়ার চালু:\n$name"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        Thread {
            while (!socketServer.isClosed) {
                try {
                    val client = socketServer.accept()
                    activeSockets.add(client)
                    clients.execute {
                        try {
                            serve(client, uri, name, token)
                        } finally {
                            activeSockets.remove(client)
                            try { client.close() } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }.start()
    }

    private fun serve(
        client: Socket,
        uri: Uri,
        name: String,
        token: String
    ) {
        try {
            client.soTimeout = 10000
            val input = client.getInputStream()
            val request = StringBuilder()
            var state = 0
            while (request.length < 8192) {
                val byte = input.read()
                if (byte < 0) return
                request.append(byte.toChar())
                state = when {
                    state == 0 && byte == 13 -> 1
                    state == 1 && byte == 10 -> 2
                    state == 2 && byte == 13 -> 3
                    state == 3 && byte == 10 -> 4
                    byte == 13 -> 1
                    else -> 0
                }
                if (state == 4) break
            }
            if (state != 4) return
            val first = request.toString()
                .substringBefore("\r\n").split(" ")
            val output = client.getOutputStream()
            if (first.size != 3 ||
                first[0] != "GET" ||
                first[1] != "/$token"
            ) {
                output.write(
                    ("HTTP/1.1 404 Not Found\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n")
                        .toByteArray(Charsets.US_ASCII)
                )
                output.flush()
                return
            }

            val file = contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("File unavailable")
            file.use {
                val encoded = URLEncoder.encode(name, "UTF-8")
                    .replace("+", "%20")
                val header =
                    "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/octet-stream\r\n" +
                    "Content-Disposition: attachment; " +
                    "filename=\"file\"; filename*=UTF-8''$encoded\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "X-Content-Type-Options: nosniff\r\n" +
                    "Connection: close\r\n\r\n"
                output.write(header.toByteArray(Charsets.US_ASCII))
                it.copyTo(output, 65536)
                output.flush()
            }
            runOnUiThread {
                if (server != null) {
                    status.text =
                        "ফাইলের ডাটা পাঠানো শেষ:\n$name"
                }
            }
        } catch (_: Exception) {
            runOnUiThread {
                if (server != null) {
                    status.text =
                        "ডাউনলোড সম্পূর্ণ হয়নি। আবার চেষ্টা করুন।"
                }
            }
        }
    }

    private fun stopSharing() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        synchronized(activeSockets) {
            activeSockets.forEach {
                try { it.close() } catch (_: Exception) {}
            }
            activeSockets.clear()
        }
        shareLink = ""
        if (::links.isInitialized) links.text = ""
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        stopSharing()
        clients.shutdownNow()
        super.onDestroy()
    }
}
