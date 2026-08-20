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
        tvAge.text = if (age >= 0) "$age yrs" else "Not specified"
        tvCity.text = city

        ivAvatar.setImageResource(R.drawable.ic_default_avatar)
        if (!avatarBase64.isNullOrEmpty()) {
            try {
                val bytes = Base64.decode(avatarBase64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) ivAvatar.setImageBitmap(bitmap)
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

        val cachedName = TestSession.cachedProfileDisplayName(this, partnerAccountId)
        val cachedGender = TestSession.cachedProfileGender(this, partnerAccountId)
        val cachedAge = TestSession.cachedProfileAge(this, partnerAccountId)
        val cachedCity = TestSession.cachedProfileCity(this, partnerAccountId)
        val cachedAvatar = TestSession.cachedProfileAvatar(this, partnerAccountId)
            ?: getSharedPreferences("anonchat_prefs", MODE_PRIVATE).getString("avatar_$partnerAccountId", null)

        val initialName = cachedName ?: fallbackName
        val initialGender = cachedGender ?: fallbackGender
        val initialAge = if (cachedAge != null && cachedAge >= 0) cachedAge else fallbackAge
        val initialCity = cachedCity ?: fallbackCity
        val initialAvatar = cachedAvatar ?: fallbackAvatar

        tvName.text = initialName
        tvGender.text = initialGender
        tvAge.text = if (initialAge >= 0) "$initialAge yrs" else "Not specified"
        tvCity.text = initialCity

        if (!initialAvatar.isNullOrEmpty()) {
            currentAvatarBase64 = initialAvatar
            try {
                val bytes = Base64.decode(initialAvatar, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) ivAvatar.setImageBitmap(bitmap)
            } catch (_: Exception) {}
        }

        // Unconditionally fetch real profile & avatar from Firebase Realtime Database node /users/$partnerAccountId
        FirebaseDatabase.getInstance().reference
            .child("users").child(partnerAccountId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) return

                    val profileSnap = snapshot.child("profile")
                    val dbName = profileSnap.child("displayName").getValue(String::class.java)
                        ?: snapshot.child("displayName").getValue(String::class.java)
                        ?: snapshot.child("userName").getValue(String::class.java)

                    val dbGender = profileSnap.child("gender").getValue(String::class.java)
                        ?: snapshot.child("gender").getValue(String::class.java)

                    val dbAge = (profileSnap.child("age").value ?: snapshot.child("age").value)?.toString()?.toIntOrNull()

                    val dbCity = profileSnap.child("city").getValue(String::class.java)
                        ?: snapshot.child("city").getValue(String::class.java)

                    val dbAvatar = snapshot.child("avatar").getValue(String::class.java)
                        ?: profileSnap.child("avatar").getValue(String::class.java)

                    if (!dbName.isNullOrEmpty()) tvName.text = dbName
                    if (!dbGender.isNullOrEmpty()) tvGender.text = dbGender
                    if (dbAge != null && dbAge >= 0) tvAge.text = "$dbAge yrs"
                    if (!dbCity.isNullOrEmpty()) tvCity.text = dbCity

                    if (!dbAvatar.isNullOrEmpty()) {
                        currentAvatarBase64 = dbAvatar
                        try {
                            val bytes = Base64.decode(dbAvatar, Base64.DEFAULT)
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) ivAvatar.setImageBitmap(bitmap)
                        } catch (_: Exception) {}
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }
}
