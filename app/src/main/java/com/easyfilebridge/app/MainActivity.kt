package com.easyfilebridge.app

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.database.Cursor
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
        }

        val title = TextView(this).apply {
            text = "EasyFileBridge"
            textSize = 28f
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Easy file transfer\nফাইল বেছে নিয়ে শুরু করুন"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 40)
        }

        val selectButton = Button(this).apply {
            text = "Select File / ফাইল বাছুন"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(intent, 100)
            }
        }

        status = TextView(this).apply {
            text = "No file selected"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 0)
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(selectButton)
        layout.addView(status)

        setContentView(layout)
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != 100 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        var name = "Selected file"

        try {
            val cursor: Cursor? =
                contentResolver.query(uri, null, null, null, null)

            cursor?.use {
                if (it.moveToFirst()) {
                    val index =
                        it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) name = it.getString(index)
                }
            }

            status.text = "Selected / বাছাই হয়েছে:\n$name"
        } catch (error: Exception) {
            status.text = "Could not read file information"
        }
    }
}
