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

import android.content.Context

/**
 * Lightweight first-time profile setup shown after OTP verification.
 * Only asks for: photo, name, gender, age.
 * User can save or skip.
 */
class SetupProfileActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val lang = LocaleHelper.getLanguage(newBase)
        super.attachBaseContext(LocaleHelper.setLocale(newBase, lang))
    }

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
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup_profile)

        val ivPhoto = findViewById<CircleImageView>(R.id.ivSetupPhoto)
        val ivCamera = findViewById<android.widget.ImageView>(R.id.ivSetupCamera)
        val etName = findViewById<TextInputEditText>(R.id.etSetupName)
        val spinnerGender = findViewById<Spinner>(R.id.spinnerSetupGender)
        val etAge = findViewById<TextInputEditText>(R.id.etSetupAge)
        val etCity = findViewById<TextInputEditText>(R.id.etSetupCity)
        val btnSave = findViewById<MaterialButton>(R.id.btnSetupSave)
        val btnSkip = findViewById<MaterialButton>(R.id.btnSetupSkip)

        val tvAddPhoto = findViewById<android.widget.TextView>(R.id.tvSetupAddPhoto)
        val tvRemovePhoto = findViewById<android.widget.TextView>(R.id.tvSetupRemovePhoto)

        // Gender spinner
        spinnerGender.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, genderOptions)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        // Photo picker
        ivCamera.setOnClickListener { launchGalleryPicker() }
        ivPhoto.setOnClickListener { launchGalleryPicker() }
        tvAddPhoto?.setOnClickListener { launchGalleryPicker() }

        tvRemovePhoto.setOnClickListener {
            avatarBase64 = null
            ivPhoto.setImageResource(R.drawable.ic_default_avatar)
            tvRemovePhoto.visibility = android.view.View.GONE
        }

        // Save
        btnSave.setOnClickListener {
            val name = etName.text?.toString()?.trim() ?: ""
            val selectedGender = spinnerGender.selectedItemPosition
            val gender: String? = if (selectedGender == 0) null else genderOptions[selectedGender]
            val ageText = etAge.text?.toString()?.trim() ?: ""
            val age: Int? = if (ageText.isEmpty()) null else ageText.toIntOrNull()
            val city = etCity.text?.toString()?.trim() ?: ""

            // Validate name
            if (name.isEmpty()) {
                etName.error = "Enter a display name"
                return@setOnClickListener
            }

            val uid = TestSession.currentUserId(this) ?: "unknown"
            val prefs = getSharedPreferences("anonchat_prefs", MODE_PRIVATE)
            val phoneNumber = prefs.getString("current_phone_number", "") ?: ""

            // Save user info linked to unique phone number
            UserDatabase.saveUser(
                context = this,
                uid = uid,
                phoneNumber = phoneNumber,
                displayName = name,
                gender = gender,
                age = age,
                city = city.ifEmpty { null },
                avatarBase64 = avatarBase64
            )

            // Cache display name locally
            TestSession.cacheDisplayName(this, uid, name)

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
            findViewById<android.widget.TextView>(R.id.tvSetupRemovePhoto)?.visibility = android.view.View.VISIBLE

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
