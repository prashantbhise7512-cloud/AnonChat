package com.anonchat.app

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.io.ByteArrayOutputStream

class ProfileActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FROM_LOGIN = "EXTRA_FROM_LOGIN"
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tilDisplayName: TextInputLayout
    private lateinit var etDisplayName: TextInputEditText
    private lateinit var spinnerGender: Spinner
    private lateinit var tilAge: TextInputLayout
    private lateinit var etAge: TextInputEditText
    private lateinit var tilCity: TextInputLayout
    private lateinit var etCity: TextInputEditText
    private var profileExists = false
    private lateinit var btnEditProfile: MaterialButton
    private lateinit var btnSaveProfile: MaterialButton
    private lateinit var btnContinueWithoutProfile: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var ivProfilePic: ImageView
    private lateinit var ivCameraIcon: ImageView

    private var fromLogin = false
    private var isEditMode = false

    private val genderOptions = listOf(
        "— Select —",
        "Male",
        "Female",
        "Other",
        "Prefer not to say"
    )

    private val database by lazy { FirebaseDatabase.getInstance() }

    // Image picker launcher
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleImagePicked(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        initViews()
        setupToolbar()
        setupProfilePicture()
        setupGenderSpinner()
        setupClickListeners()
        setupLogout()
        setupBlockedUsers()
        fromLogin = intent.getBooleanExtra(EXTRA_FROM_LOGIN, false)
        loadProfile()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        tilDisplayName = findViewById(R.id.tilDisplayName)
        etDisplayName = findViewById(R.id.etDisplayName)
        spinnerGender = findViewById(R.id.spinnerGender)
        tilAge = findViewById(R.id.tilAge)
        etAge = findViewById(R.id.etAge)
        tilCity = findViewById(R.id.tilCity)
        etCity = findViewById(R.id.etCity)
        btnEditProfile = findViewById(R.id.btnEditProfile)
        btnSaveProfile = findViewById(R.id.btnSaveProfile)
        btnContinueWithoutProfile = findViewById(R.id.btnContinueWithoutProfile)
        progressBar = findViewById(R.id.progressBar)
        tvError = findViewById(R.id.tvError)
        ivProfilePic = findViewById(R.id.ivProfilePic)
        ivCameraIcon = findViewById(R.id.ivCameraIcon)
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupProfilePicture() {
        // Show generated profile ID (not editable)
        val tvUniqueId = findViewById<TextView>(R.id.tvUniqueId)
        val profileId = TestSession.profileId(this)
        tvUniqueId.text = profileId

        // Load saved avatar from SharedPreferences
        val prefs = getSharedPreferences("anonchat_prefs", MODE_PRIVATE)
        val currentUid = TestSession.currentUserId(this) ?: "unknown"
        val savedAvatar = prefs.getString("avatar_$currentUid", null)
        if (savedAvatar != null) {
            val bytes = Base64.decode(savedAvatar, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ivProfilePic.setImageBitmap(bitmap)
        }

        // Click to change photo
        ivCameraIcon.setOnClickListener { pickImageLauncher.launch("image/*") }
        ivProfilePic.setOnClickListener { pickImageLauncher.launch("image/*") }
    }

    private fun handleImagePicked(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            // Display the image
            ivProfilePic.setImageBitmap(bitmap)

            // Save as base64 to SharedPreferences (compressed to keep size small)
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, outputStream)
            val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)

            val uid = TestSession.currentUserId(this) ?: "unknown"
            val prefs = getSharedPreferences("anonchat_prefs", MODE_PRIVATE)
            prefs.edit().putString("avatar_$uid", base64).apply()

            // Save to Firebase so other users can fetch it on demand
            if (!AuthActivity.TEST_MODE) {
                database.reference.child("users").child(uid).child("avatar").setValue(base64)
            }

            Toast.makeText(this, "Photo updated!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupGenderSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            genderOptions
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerGender.adapter = adapter
    }

    private fun setupClickListeners() {
        btnEditProfile.setOnClickListener {
            setEditMode(true)
        }

        btnSaveProfile.setOnClickListener {
            hideError()
            if (validateFields()) {
                saveProfile(continueAfterSave = fromLogin)
            }
        }

        btnContinueWithoutProfile.setOnClickListener {
            navigateToChatList()
        }
    }

    private fun setupLogout() {
        val btnLogout = findViewById<MaterialButton>(R.id.btnLogout)
        btnLogout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.logout_confirm_title))
                .setMessage(getString(R.string.logout_confirm_message))
                .setPositiveButton(getString(R.string.logout_confirm)) { _, _ ->
                    FirebaseAuth.getInstance().signOut()
                    TestSession.signOut(this)
                    val intent = Intent(this, AuthActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }

    // --- Validation ---

    private fun validateFields(): Boolean {
        val displayName = etDisplayName.text?.toString()?.trim() ?: ""
        val ageText = etAge.text?.toString()?.trim() ?: ""

        if (displayName.isEmpty()) {
            etDisplayName.setText("AnonUser")
        }

        val finalDisplayName = etDisplayName.text?.toString()?.trim() ?: "AnonUser"
        if (finalDisplayName.length > 50) {
            tilDisplayName.error = "Display name must be 50 characters or less"
            return false
        }
        tilDisplayName.error = null

        if (ageText.isNotEmpty()) {
            val age = ageText.toIntOrNull()
            if (age == null || age < 13 || age > 120) {
                tilAge.error = "Age must be between 13 and 120"
                return false
            }
        }
        tilAge.error = null

        return true
    }

    // --- Load Profile ---

    private fun loadProfile() {
        val uid = TestSession.currentUserId(this) ?: return

        showLoading(true)

        val profileRef = database.reference.child("users").child(uid).child("profile")
        profileRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                showLoading(false)

                if (snapshot.exists()) {
                    profileExists = true
                    val displayName = snapshot.child("displayName").getValue(String::class.java)
                    val gender = snapshot.child("gender").getValue(String::class.java)
                    val age = snapshot.child("age").getValue(Long::class.java)
                    val city = snapshot.child("city").getValue(String::class.java)

                    etDisplayName.setText(displayName ?: "AnonUser")
                    val genderIndex = if (gender != null) genderOptions.indexOf(gender) else 0
                    spinnerGender.setSelection(if (genderIndex >= 0) genderIndex else 0)

                    if (age != null) {
                        etAge.setText(age.toString())
                    }

                    etCity.setText(city ?: "")
                } else {
                    profileExists = false
                    etDisplayName.setText(
                        TestSession.cachedDisplayName(this@ProfileActivity, uid) ?: "AnonUser"
                    )
                    spinnerGender.setSelection(0)
                }
                setEditMode(fromLogin && !profileExists)
            }

            override fun onCancelled(error: DatabaseError) {
                showLoading(false)
                if (AuthActivity.TEST_MODE) {
                    profileExists = false
                    etDisplayName.setText(
                        TestSession.cachedDisplayName(this@ProfileActivity, uid) ?: "AnonUser"
                    )
                    spinnerGender.setSelection(0)
                    setEditMode(fromLogin && !profileExists)
                } else {
                    showError("Failed to load profile. Please try again.")
                }
            }
        })
    }

    // --- Save Profile ---

    private fun saveProfile(continueAfterSave: Boolean) {
        val uid = TestSession.currentUserId(this) ?: return

        showLoading(true)
        btnSaveProfile.isEnabled = false

        val displayName = etDisplayName.text?.toString()?.trim() ?: "AnonUser"
        val selectedGender = spinnerGender.selectedItemPosition
        val gender: String? = if (selectedGender == 0) null else genderOptions[selectedGender]
        val ageText = etAge.text?.toString()?.trim() ?: ""
        val age: Int? = if (ageText.isEmpty()) null else ageText.toIntOrNull()
        val cityText = etCity.text?.toString()?.trim() ?: ""
        val city: String? = if (cityText.isEmpty()) null else cityText

        val profileData = HashMap<String, Any?>()
        profileData["displayName"] = displayName
        profileData["gender"] = gender
        profileData["age"] = age
        profileData["city"] = city

        // Always cache locally so the name is available without a database round trip.
        TestSession.cacheDisplayName(this, uid, displayName)

        val profileRef = database.reference.child("users").child(uid).child("profile")
        profileRef.setValue(profileData)
            .addOnSuccessListener {
                showLoading(false)
                btnSaveProfile.isEnabled = true
                Toast.makeText(this, getString(R.string.profile_saved), Toast.LENGTH_SHORT).show()
                if (continueAfterSave) {
                    navigateToChatList()
                } else {
                    setEditMode(false)
                }
            }
            .addOnFailureListener {
                showLoading(false)
                btnSaveProfile.isEnabled = true
                if (AuthActivity.TEST_MODE) {
                    // Database write blocked/unavailable — local cache is enough for now.
                    Toast.makeText(this, getString(R.string.profile_saved), Toast.LENGTH_SHORT).show()
                    if (continueAfterSave) {
                        navigateToChatList()
                    }
                } else {
                    showError("Failed to save profile. Please try again.")
                }
            }
    }

    private fun navigateToChatList() {
        val intent = Intent(this, ChatListActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun setEditMode(enabled: Boolean) {
        isEditMode = enabled
        etDisplayName.isEnabled = enabled
        spinnerGender.isEnabled = enabled
        etAge.isEnabled = enabled
        tilCity.visibility = if (enabled && !fromLogin) View.VISIBLE else View.GONE
        etCity.isEnabled = enabled && !fromLogin
        ivCameraIcon.isEnabled = enabled
        ivProfilePic.isEnabled = enabled
        val alpha = if (enabled) 1f else 0.6f
        ivCameraIcon.alpha = alpha
        ivProfilePic.alpha = alpha

        btnSaveProfile.visibility = if (enabled) View.VISIBLE else View.GONE
        btnEditProfile.visibility = if (enabled || fromLogin) View.GONE else View.VISIBLE
        btnContinueWithoutProfile.visibility = if (fromLogin) View.VISIBLE else View.GONE

        if (!enabled) {
            btnSaveProfile.isEnabled = true
        }
    }

    // --- Blocked Users ---

    private fun setupBlockedUsers() {
        val btnShowBlocked = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnShowBlocked)
        btnShowBlocked.setOnClickListener {
            val intent = Intent(this, BlockedUsersActivity::class.java)
            startActivity(intent)
        }
    }

    // --- UI Helpers ---

    private fun showLoading(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }

    private fun hideError() {
        tvError.visibility = View.GONE
        tvError.text = ""
    }
}
