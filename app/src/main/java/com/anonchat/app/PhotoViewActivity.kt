package com.anonchat.app

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.WindowManager
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

/**
 * Fullscreen photo viewer with screenshot protection.
 * FLAG_SECURE prevents screenshots, screen recording, and task switcher previews.
 */
class PhotoViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_BASE64 = "image_base64"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Block screenshots and screen recording
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_photo_view)

        val ivPhoto = findViewById<ImageView>(R.id.ivFullPhoto)
        val btnClose = findViewById<ImageView>(R.id.btnClose)

        val base64 = intent.getStringExtra(EXTRA_IMAGE_BASE64)
        if (base64 != null) {
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ivPhoto.setImageBitmap(bitmap)
            } catch (_: Exception) {
                finish()
            }
        } else {
            finish()
        }

        btnClose.setOnClickListener { finish() }
        ivPhoto.setOnClickListener { finish() }
    }
}
