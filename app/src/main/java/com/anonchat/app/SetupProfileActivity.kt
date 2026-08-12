package com.anonchat.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.database.FirebaseDatabase
import de.hdodenhof.circleimageview.CircleImageView
import java.io.ByteArrayOutputStream

/**
 * Lightweight first-time profile setup shown after OTP verification.
 * Only asks for: photo, name, gender, age.
 * User can save or skip.
 */
class SetupProfileActivity : AppCompatActivity() {

    private val genderOptions = listOf("— Select —", "Male", "Female", "Other", "Prefer not to say")
    private var avatarBase64: String? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { handleImagePicked(it) }
        }
    }

    private fun launchGalleryPicker() {
        val intent = Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }
        pickImageLauncher.launch(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup_profile)

        val ivPhoto = findViewById<CircleImageView>(R.id.ivSetupPhoto)
        val ivCamera = findViewById<android.widget.ImageView>(R.id.ivSetupCamera)
        val etName = findViewById<TextInputEditText>(R.id.etSetupName)
        val spinnerGender = findViewById<Spinner>(R.id.spinnerSetupGender)
        val etAge = findViewById<TextInputEditText>(R.id.etSetupAge)
        val btnSave = findViewById<MaterialButton>(R.id.btnSetupSave)
        val btnSkip = findViewById<MaterialButton>(R.id.btnSetupSkip)

        // Gender spinner
        spinnerGender.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, genderOptions)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        // Photo picker
        ivCamera.setOnClickListener { launchGalleryPicker() }
        ivPhoto.setOnClickListener { launchGalleryPicker() }

        // Save
        btnSave.setOnClickListener {
            val name = etName.text?.toString()?.trim() ?: ""
            val selectedGender = spinnerGender.selectedItemPosition
            val gender: String? = if (selectedGender == 0) null else genderOptions[selectedGender]
            val ageText = etAge.text?.toString()?.trim() ?: ""
            val age: Int? = if (ageText.isEmpty()) null else ageText.toIntOrNull()

            // Validate name
            if (name.isEmpty()) {
                etName.error = "Enter a display name"
                return@setOnClickListener
            }

            val uid = TestSession.currentUserId(this) ?: "unknown"
            val db = FirebaseDatabase.getInstance()

            // Save profile to Firebase
            val profileData = mutableMapOf<String, Any>("displayName" to name)
            if (gender != null) profileData["gender"] = gender
            if (age != null) profileData["age"] = age

            db.reference.child("users").child(uid).child("profile").setValue(profileData)

            // Save avatar to Firebase if set
            avatarBase64?.let { avatar ->
                db.reference.child("users").child(uid).child("avatar").setValue(avatar)
            }

            // Cache locally
            TestSession.cacheDisplayName(this, uid, name)

            // Save avatar locally
            avatarBase64?.let { avatar ->
                getSharedPreferences("anonchat_prefs", MODE_PRIVATE)
                    .edit().putString("avatar_$uid", avatar).apply()
            }

            Toast.makeText(this, "Profile saved!", Toast.LENGTH_SHORT).show()
            goToChatList()
        }

        // Skip
        btnSkip.setOnClickListener {
            val uid = TestSession.currentUserId(this) ?: "unknown"
            FirebaseDatabase.getInstance().reference
                .child("users").child(uid).child("profile")
                .child("displayName").setValue("AnnoUser")
            TestSession.cacheDisplayName(this, uid, "AnnoUser")
            goToChatList()
        }
    }

    private fun handleImagePicked(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            findViewById<CircleImageView>(R.id.ivSetupPhoto).setImageBitmap(bitmap)

            val outputStream = ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, outputStream)
            avatarBase64 = Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun goToChatList() {
        // Mark that setup is complete
        getSharedPreferences("anonchat_prefs", MODE_PRIVATE)
            .edit().putBoolean("profile_setup_done", true).apply()

        val intent = Intent(this, ChatListActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
