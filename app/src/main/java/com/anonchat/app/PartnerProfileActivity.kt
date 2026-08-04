package com.anonchat.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
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

    private var currentAvatarBase64: String? = null

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
        currentAvatarBase64 = avatarBase64

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

        val partnerAccountId = intent.getStringExtra(EXTRA_PARTNER_ACCOUNT_ID)
        if (!partnerAccountId.isNullOrBlank()) {
            loadLatestProfile(partnerAccountId, tvName, tvGender, tvAge, tvCity, ivAvatar)
        }

        ivAvatar.setOnClickListener {
            if (!currentAvatarBase64.isNullOrEmpty()) {
                val intent = Intent(this, PhotoViewActivity::class.java)
                intent.putExtra(PhotoViewActivity.EXTRA_IMAGE_BASE64, currentAvatarBase64)
                startActivity(intent)
            }
        }

        btnBack.setOnClickListener { finish() }
    }

    private fun loadLatestProfile(
        partnerAccountId: String,
        tvName: TextView,
        tvGender: TextView,
        tvAge: TextView,
        tvCity: TextView,
        ivAvatar: CircleImageView
    ) {
        val fallbackName = intent.getStringExtra(EXTRA_PARTNER_NAME).orEmpty().ifEmpty { "AnonUser" }
        val fallbackGender = intent.getStringExtra(EXTRA_PARTNER_GENDER).orEmpty().ifEmpty { "Not specified" }
        val fallbackAge = intent.getIntExtra(EXTRA_PARTNER_AGE, -1)
        val fallbackCity = intent.getStringExtra(EXTRA_PARTNER_CITY).orEmpty().ifEmpty { "Not specified" }
        val fallbackAvatar = intent.getStringExtra(EXTRA_PARTNER_AVATAR_BASE64)

        fun applyFallback() {
            tvName.text = fallbackName
            tvGender.text = fallbackGender
            tvAge.text = if (fallbackAge >= 0) fallbackAge.toString() else "Not specified"
            tvCity.text = fallbackCity
            currentAvatarBase64 = fallbackAvatar
            ivAvatar.setImageResource(R.drawable.ic_default_avatar)
            if (!fallbackAvatar.isNullOrEmpty()) {
                try {
                    val bytes = Base64.decode(fallbackAvatar, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ivAvatar.setImageBitmap(bitmap)
                } catch (_: Exception) {}
            }
        }

        val cachedName = TestSession.cachedProfileDisplayName(this, partnerAccountId)
        val cachedGender = TestSession.cachedProfileGender(this, partnerAccountId)
        val cachedAge = TestSession.cachedProfileAge(this, partnerAccountId)
        val cachedCity = TestSession.cachedProfileCity(this, partnerAccountId)
        val cachedAvatar = TestSession.cachedProfileAvatar(this, partnerAccountId)
        if (!cachedName.isNullOrBlank() || !cachedGender.isNullOrBlank() || cachedAge != null || !cachedCity.isNullOrBlank() || !cachedAvatar.isNullOrBlank()) {
            tvName.text = cachedName ?: fallbackName
            tvGender.text = cachedGender ?: fallbackGender
            tvAge.text = if (cachedAge != null && cachedAge >= 0) cachedAge.toString() else if (fallbackAge >= 0) fallbackAge.toString() else "Not specified"
            tvCity.text = cachedCity ?: fallbackCity
            currentAvatarBase64 = cachedAvatar ?: fallbackAvatar
            ivAvatar.setImageResource(R.drawable.ic_default_avatar)
            if (!currentAvatarBase64.isNullOrEmpty()) {
                try {
                    val bytes = Base64.decode(currentAvatarBase64, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ivAvatar.setImageBitmap(bitmap)
                } catch (_: Exception) {}
            }
        }

        if (!AuthActivity.TEST_MODE) {
            FirebaseDatabase.getInstance().reference
                .child("users").child(partnerAccountId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val profile = snapshot.child("profile")
                        val fetchedName = profile.child("displayName").getValue(String::class.java)
                            ?.takeIf { it.isNotBlank() }
                            ?: cachedName ?: fallbackName
                        val fetchedGender = profile.child("gender").getValue(String::class.java)
                            ?.takeIf { it.isNotBlank() }
                            ?: cachedGender ?: fallbackGender
                        val fetchedAge = profile.child("age").getValue(Long::class.java)?.toInt() ?: cachedAge
                        val fetchedCity = profile.child("city").getValue(String::class.java)
                            ?.takeIf { it.isNotBlank() }
                            ?: cachedCity ?: fallbackCity
                        val fetchedAvatar = snapshot.child("avatar").getValue(String::class.java)
                            ?: cachedAvatar ?: fallbackAvatar

                        tvName.text = fetchedName
                        tvGender.text = fetchedGender
                        tvAge.text = if (fetchedAge != null && fetchedAge >= 0) fetchedAge.toString() else "Not specified"
                        tvCity.text = fetchedCity

                        currentAvatarBase64 = fetchedAvatar
                        ivAvatar.setImageResource(R.drawable.ic_default_avatar)
                        if (!fetchedAvatar.isNullOrEmpty()) {
                            try {
                                val bytes = Base64.decode(fetchedAvatar, Base64.DEFAULT)
                                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                ivAvatar.setImageBitmap(bitmap)
                            } catch (_: Exception) {}
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        applyFallback()
                    }
                })
        } else {
            applyFallback()
        }
    }
}
