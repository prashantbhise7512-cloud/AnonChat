package com.anonchat.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import de.hdodenhof.circleimageview.CircleImageView

class PartnerProfileActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PARTNER_NAME = "EXTRA_PARTNER_NAME"
        const val EXTRA_PARTNER_GENDER = "EXTRA_PARTNER_GENDER"
        const val EXTRA_PARTNER_AGE = "EXTRA_PARTNER_AGE"
        const val EXTRA_PARTNER_CITY = "EXTRA_PARTNER_CITY"
        const val EXTRA_PARTNER_AVATAR_BASE64 = "EXTRA_PARTNER_AVATAR_BASE64"
        const val EXTRA_PARTNER_ACCOUNT_ID = "EXTRA_PARTNER_ACCOUNT_ID"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_partner_profile)

        val ivAvatar = findViewById<CircleImageView>(R.id.ivPartnerProfileAvatar)
        val tvName = findViewById<TextView>(R.id.tvPartnerProfileName)
        val tvGender = findViewById<TextView>(R.id.tvPartnerProfileGender)
        val tvAge = findViewById<TextView>(R.id.tvPartnerProfileAge)
        val tvCity = findViewById<TextView>(R.id.tvPartnerProfileCity)
        val btnBack = findViewById<ImageView>(R.id.btnBack)

        val name = intent.getStringExtra(EXTRA_PARTNER_NAME).orEmpty().ifEmpty { "AnonUser" }
        val gender = intent.getStringExtra(EXTRA_PARTNER_GENDER).orEmpty().ifEmpty { "Not specified" }
        val age = intent.getIntExtra(EXTRA_PARTNER_AGE, -1)
        val city = intent.getStringExtra(EXTRA_PARTNER_CITY).orEmpty().ifEmpty { "Not specified" }
        val avatarBase64 = intent.getStringExtra(EXTRA_PARTNER_AVATAR_BASE64)

        tvName.text = name
        tvGender.text = gender
        tvAge.text = if (age >= 0) age.toString() else "Not specified"
        tvCity.text = city

        ivAvatar.setImageResource(R.drawable.ic_default_avatar)
        if (!avatarBase64.isNullOrEmpty()) {
            try {
                val bytes = Base64.decode(avatarBase64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ivAvatar.setImageBitmap(bitmap)
            } catch (_: Exception) {}
        }

        ivAvatar.setOnClickListener {
            if (!avatarBase64.isNullOrEmpty()) {
                val intent = Intent(this, PhotoViewActivity::class.java)
                intent.putExtra(PhotoViewActivity.EXTRA_IMAGE_BASE64, avatarBase64)
                startActivity(intent)
            }
        }

        btnBack.setOnClickListener { finish() }
    }
}
