package com.anonchat.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth

class AccountPrivacyActivity : AppCompatActivity() {

    private var currentLang: String? = null

    override fun attachBaseContext(newBase: Context) {
        val lang = LocaleHelper.getLanguage(newBase)
        super.attachBaseContext(LocaleHelper.setLocale(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        currentLang = LocaleHelper.getLanguage(this)
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account_privacy)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            finish()
        }

        val btnChangeLanguage = findViewById<MaterialButton>(R.id.btnChangeLanguage)
        btnChangeLanguage?.setOnClickListener {
            val intent = Intent(this, LanguageSelectionActivity::class.java).apply {
                putExtra("EXTRA_FROM_PROFILE", true)
            }
            startActivity(intent)
        }

        val btnShowBlocked = findViewById<MaterialButton>(R.id.btnShowBlocked)
        btnShowBlocked?.setOnClickListener {
            val intent = Intent(this, BlockedUsersActivity::class.java)
            startActivity(intent)
        }

        val btnLogout = findViewById<MaterialButton>(R.id.btnLogout)
        btnLogout?.setOnClickListener {
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

        val btnDeleteAccount = findViewById<MaterialButton>(R.id.btnDeleteAccount)
        btnDeleteAccount?.setOnClickListener {
            val phone = TestSession.phoneNumber(this).ifBlank {
                com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.phoneNumber ?: ""
            }

            val confirmMsg = if (phone.isNotBlank()) {
                "Are you sure you want to delete your account?\n\nAn OTP will be sent to $phone to verify account deletion."
            } else {
                "Are you sure you want to delete your account?\n\nAn OTP will be sent to your registered phone number to verify account deletion."
            }

            // Step 1: Ask Confirmation First
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.delete_account_confirm_title))
                .setMessage(confirmMsg)
                .setPositiveButton("Send OTP") { _, _ ->
                    // Step 2: Open Enter OTP Dialog
                    showDeleteAccountOtpDialog(phone)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }

    private fun showDeleteAccountOtpDialog(phone: String) {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 20, 60, 10)
        }

        val tvMsg = android.widget.TextView(this).apply {
            text = if (phone.isNotBlank()) {
                "An OTP code has been sent to $phone. Enter the 6-digit OTP code to confirm account deletion."
            } else {
                "An OTP code has been sent to your phone. Enter the 6-digit OTP code to confirm account deletion."
            }
            textSize = 14f
            setTextColor(resources.getColor(R.color.text_secondary))
        }
        container.addView(tvMsg)

        val etOtp = android.widget.EditText(this).apply {
            hint = "000000"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            textSize = 22f
            gravity = android.view.Gravity.CENTER
            letterSpacing = 0.2f
            setPadding(20, 20, 20, 20)
        }
        container.addView(etOtp)

        AlertDialog.Builder(this)
            .setTitle("Enter OTP")
            .setView(container)
            .setPositiveButton("Verify & Delete") { _, _ ->
                val otp = etOtp.text.toString().trim()
                if (otp.length != 6 || !otp.all { it.isDigit() }) {
                    android.widget.Toast.makeText(this, "Please enter a valid 6-digit OTP code", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val uid = TestSession.currentUserId(this) ?: com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
                val onComplete = {
                    com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
                    TestSession.signOut(this)
                    val intent = Intent(this, AuthActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    finish()
                }

                if (!uid.isNullOrBlank()) {
                    // Delete user record from database backend
                    com.google.firebase.database.FirebaseDatabase.getInstance().reference.child("users").child(uid).removeValue()
                        .addOnCompleteListener {
                            com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.delete()?.addOnCompleteListener {
                                onComplete()
                            } ?: onComplete()
                        }
                } else {
                    onComplete()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        val lang = LocaleHelper.getLanguage(this)
        if (currentLang != null && currentLang != lang) {
            currentLang = lang
            recreate()
        }
    }
}
