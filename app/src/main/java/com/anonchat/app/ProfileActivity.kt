package com.anonchat.app

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
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

import android.content.Context

class ProfileActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FROM_LOGIN = "EXTRA_FROM_LOGIN"
    }

    private var currentLang: String? = null

    override fun attachBaseContext(newBase: Context) {
        val lang = LocaleHelper.getLanguage(newBase)
        super.attachBaseContext(LocaleHelper.setLocale(newBase, lang))
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

    private lateinit var llProfileViewMode: View
    private lateinit var llProfileEditMode: View
    private lateinit var tvViewName: TextView
    private lateinit var tvViewGender: TextView
    private lateinit var tvViewAge: TextView
    private lateinit var tvViewCity: TextView
    private lateinit var btnEditProfileDetails: MaterialButton
    private lateinit var btnCancelEdit: MaterialButton
    private lateinit var btnRemovePhoto: TextView
    private var cardTheme: View? = null
    private var cardAccountPrivacy: View? = null

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
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { handleImagePicked(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        currentLang = LocaleHelper.getLanguage(this)
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        initViews()
        setupToolbar()
        setupProfilePicture()
        setupGenderSpinner()
        setupClickListeners()
        setupAccountPrivacy()
        setupThemeSelector()
        fromLogin = intent.getBooleanExtra(EXTRA_FROM_LOGIN, false)
        loadProfile()
    }

    override fun onResume() {
        super.onResume()
        val lang = LocaleHelper.getLanguage(this)
        if (currentLang != null && currentLang != lang) {
            currentLang = lang
            recreate()
        }
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

        llProfileViewMode = findViewById(R.id.llProfileViewMode)
        llProfileEditMode = findViewById(R.id.llProfileEditMode)
        tvViewName = findViewById(R.id.tvViewName)
        tvViewGender = findViewById(R.id.tvViewGender)
        tvViewAge = findViewById(R.id.tvViewAge)
        tvViewCity = findViewById(R.id.tvViewCity)
        btnEditProfileDetails = findViewById(R.id.btnEditProfileDetails)
        btnCancelEdit = findViewById(R.id.btnCancelEdit)
        btnRemovePhoto = findViewById(R.id.btnRemovePhoto)
        cardTheme = findViewById(R.id.cardTheme)
        cardAccountPrivacy = findViewById(R.id.cardAccountPrivacy)
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
            btnRemovePhoto.visibility = View.VISIBLE
        } else {
            ivProfilePic.setImageResource(R.drawable.ic_default_avatar)
            btnRemovePhoto.visibility = View.GONE
        }

        // Click camera icon or "Change Photo" text to change photo
        ivCameraIcon.setOnClickListener { launchGalleryPicker() }
        val tvChangePhoto = findViewById<TextView>(R.id.tvChangePhoto)
        tvChangePhoto?.setOnClickListener { launchGalleryPicker() }

        // Click profile picture to view it full-screen
        ivProfilePic.setOnClickListener {
            val currentUid = TestSession.currentUserId(this) ?: "unknown"
            val avatarBase64 = prefs.getString("avatar_$currentUid", null)
            if (avatarBase64 != null) {
                val intent = Intent(this, PhotoViewActivity::class.java).apply {
                    putExtra(PhotoViewActivity.EXTRA_IMAGE_BASE64, avatarBase64)
                }
                startActivity(intent)
            }
        }

        // Remove photo listener
        btnRemovePhoto.setOnClickListener {
            prefs.edit().remove("avatar_$currentUid").apply()
            ivProfilePic.setImageResource(R.drawable.ic_default_avatar)
            btnRemovePhoto.visibility = View.GONE
            database.reference.child("users").child(currentUid).child("avatar").removeValue()
            Toast.makeText(this, "Profile photo removed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchGalleryPicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }
        pickImageLauncher.launch(intent)
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
            database.reference.child("users").child(uid).child("avatar").setValue(base64)

            btnRemovePhoto.visibility = View.VISIBLE
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
        btnEditProfileDetails.setOnClickListener {
            setEditMode(true)
        }

        btnCancelEdit.setOnClickListener {
            loadProfile()
            setEditMode(false)
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
                    val intent = Intent(this, LanguageSelectionActivity::class.java).apply {
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

        // 1. Immediately populate from local cache (TestSession & SharedPreferences profile_$uid)
        val cachedName = TestSession.cachedProfileDisplayName(this, uid)
            ?: TestSession.cachedDisplayName(this, uid)
        val cachedGender = TestSession.cachedProfileGender(this, uid)
        val cachedAge = TestSession.cachedProfileAge(this, uid)
        val cachedCity = TestSession.cachedProfileCity(this, uid)
        val cachedAvatar = TestSession.cachedProfileAvatar(this, uid)
            ?: getSharedPreferences("anonchat_prefs", MODE_PRIVATE).getString("avatar_$uid", null)

        if (!cachedName.isNullOrEmpty()) {
            etDisplayName.setText(cachedName)
        }
        if (cachedGender != null) {
            val genderIndex = genderOptions.indexOf(cachedGender)
            if (genderIndex >= 0) spinnerGender.setSelection(genderIndex)
        }
        if (cachedAge != null) {
            etAge.setText(cachedAge.toString())
        }
        if (cachedCity != null) {
            etCity.setText(cachedCity)
        }
        if (!cachedAvatar.isNullOrEmpty()) {
            try {
                val bytes = Base64.decode(cachedAvatar, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) ivProfilePic.setImageBitmap(bitmap)
            } catch (_: Exception) {}
        }

        updateViewModeDisplays()

        // 2. Fetch latest from Firebase Realtime Database
        val userRef = database.reference.child("users").child(uid)
        userRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                showLoading(false)

                val profileSnap = snapshot.child("profile")
                if (profileSnap.exists()) {
                    profileExists = true
                    val displayName = profileSnap.child("displayName").getValue(String::class.java)
                    val gender = profileSnap.child("gender").getValue(String::class.java)
                    val age = profileSnap.child("age").getValue(Long::class.java)
                    val city = profileSnap.child("city").getValue(String::class.java)

                    if (!displayName.isNullOrEmpty()) etDisplayName.setText(displayName)
                    val genderIndex = if (gender != null) genderOptions.indexOf(gender) else 0
                    if (genderIndex >= 0) spinnerGender.setSelection(genderIndex)
                    if (age != null) etAge.setText(age.toString())
                    if (city != null) etCity.setText(city)

                    val avatar = snapshot.child("avatar").getValue(String::class.java)
                    if (!avatar.isNullOrEmpty()) {
                        try {
                            val bytes = Base64.decode(avatar, Base64.DEFAULT)
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) ivProfilePic.setImageBitmap(bitmap)
                        } catch (_: Exception) {}
                    }
                } else {
                    profileExists = !cachedName.isNullOrEmpty()
                }

                updateViewModeDisplays()
                setEditMode(fromLogin && !profileExists)
            }

            override fun onCancelled(error: DatabaseError) {
                showLoading(false)
                updateViewModeDisplays()
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

        val prefs = getSharedPreferences("anonchat_prefs", MODE_PRIVATE)
        val phoneNumber = prefs.getString("current_phone_number", "") ?: ""

        // Always cache locally and sync with UserDatabase
        TestSession.cacheDisplayName(this, uid, displayName)
        TestSession.cacheProfile(this, uid, displayName, gender, age, city, null)
        UserDatabase.saveUser(this, uid, phoneNumber, displayName, gender, age, city)

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

    private fun updateViewModeDisplays() {
        val name = etDisplayName.text?.toString()?.trim()
        val selectedGenderPos = spinnerGender.selectedItemPosition
        val gender = if (selectedGenderPos > 0) genderOptions[selectedGenderPos] else null
        val age = etAge.text?.toString()?.trim()
        val city = etCity.text?.toString()?.trim()

        tvViewName.text = if (!name.isNullOrEmpty()) name else "AnnoUser"
        tvViewGender.text = gender ?: "Not specified"
        tvViewAge.text = if (!age.isNullOrEmpty()) "$age yrs" else "Not specified"
        tvViewCity.text = if (!city.isNullOrEmpty()) city else "Not specified"
    }

    private fun setEditMode(enabled: Boolean) {
        isEditMode = enabled
        etDisplayName.isEnabled = enabled
        spinnerGender.isEnabled = enabled
        etAge.isEnabled = enabled
        tilCity.visibility = if (enabled && !fromLogin) View.VISIBLE else View.GONE
        etCity.isEnabled = enabled && !fromLogin
        ivCameraIcon.isEnabled = enabled
        val alpha = if (enabled) 1f else 0.6f
        ivCameraIcon.alpha = alpha
        // Profile pic is always clickable (opens full-screen view)
        ivProfilePic.isEnabled = true
        ivProfilePic.alpha = 1f

        if (enabled) {
            llProfileViewMode.visibility = View.GONE
            llProfileEditMode.visibility = View.VISIBLE
            cardTheme?.visibility = View.GONE
            cardAccountPrivacy?.visibility = View.GONE
        } else {
            updateViewModeDisplays()
            llProfileEditMode.visibility = View.GONE
            llProfileViewMode.visibility = View.VISIBLE
            cardTheme?.visibility = View.VISIBLE
            cardAccountPrivacy?.visibility = View.VISIBLE
        }

        btnSaveProfile.isEnabled = true
    }

    // --- Account & Privacy ---

    private fun setupAccountPrivacy() {
        val btnOpenAccountPrivacy = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnOpenAccountPrivacy)
        btnOpenAccountPrivacy?.setOnClickListener {
            val intent = Intent(this, AccountPrivacyActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupThemeSelector() {
        val themeBlue = findViewById<View>(R.id.themeBlue) ?: return
        val themeGreen = findViewById<View>(R.id.themeGreen) ?: return
        val themePurple = findViewById<View>(R.id.themePurple) ?: return
        val themeRed = findViewById<View>(R.id.themeRed) ?: return
        val themeTeal = findViewById<View>(R.id.themeTeal) ?: return
        val themeDark = findViewById<View>(R.id.themeDark) ?: return

        val checkBlue = findViewById<View>(R.id.checkBlue)
        val checkGreen = findViewById<View>(R.id.checkGreen)
        val checkPurple = findViewById<View>(R.id.checkPurple)
        val checkRed = findViewById<View>(R.id.checkRed)
        val checkTeal = findViewById<View>(R.id.checkTeal)
        val checkDark = findViewById<View>(R.id.checkDark)

        val updateChecks = { selectedKey: String ->
            checkBlue?.visibility = if (selectedKey == ThemeManager.THEME_BLUE) View.VISIBLE else View.GONE
            checkGreen?.visibility = if (selectedKey == ThemeManager.THEME_GREEN) View.VISIBLE else View.GONE
            checkPurple?.visibility = if (selectedKey == ThemeManager.THEME_PURPLE) View.VISIBLE else View.GONE
            checkRed?.visibility = if (selectedKey == ThemeManager.THEME_RED) View.VISIBLE else View.GONE
            checkTeal?.visibility = if (selectedKey == ThemeManager.THEME_TEAL) View.VISIBLE else View.GONE
            checkDark?.visibility = if (selectedKey == ThemeManager.THEME_DARK) View.VISIBLE else View.GONE
        }

        updateChecks(ThemeManager.getSelectedTheme(this))

        val selectTheme = { key: String ->
            ThemeManager.setSelectedTheme(this, key)
            updateChecks(key)
            recreate()
        }

        themeBlue.setOnClickListener { selectTheme(ThemeManager.THEME_BLUE) }
        themeGreen.setOnClickListener { selectTheme(ThemeManager.THEME_GREEN) }
        themePurple.setOnClickListener { selectTheme(ThemeManager.THEME_PURPLE) }
        themeRed.setOnClickListener { selectTheme(ThemeManager.THEME_RED) }
        themeTeal.setOnClickListener { selectTheme(ThemeManager.THEME_TEAL) }
        themeDark.setOnClickListener { selectTheme(ThemeManager.THEME_DARK) }
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
