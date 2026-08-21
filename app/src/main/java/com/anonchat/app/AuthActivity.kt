package com.anonchat.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import java.util.concurrent.TimeUnit

class AuthActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val lang = LocaleHelper.getLanguage(newBase)
        super.attachBaseContext(LocaleHelper.setLocale(newBase, lang))
    }

    companion object {
        // Set to true to bypass Firebase Auth (any phone + any 6-digit OTP works)
        // Set to false for production with real Firebase Auth
        const val TEST_MODE = true
    }

    // Firebase Auth
    private lateinit var auth: FirebaseAuth

    // Views - Phone Section
    private lateinit var phoneSection: View
    private lateinit var spinnerCountryCode: Spinner
    private lateinit var tilPhone: TextInputLayout
    private lateinit var etPhone: TextInputEditText
    private lateinit var btnSendCode: MaterialButton

    // Views - OTP Section
    private lateinit var otpSection: View
    private lateinit var tilOtp: TextInputLayout
    private lateinit var etOtp: TextInputEditText
    private lateinit var btnVerify: MaterialButton
    private lateinit var btnResendCode: MaterialButton
    private lateinit var tvCountdown: TextView

    // Views - Status
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var tvTestModeNotice: TextView

    // Auth state
    private var hasNavigated = false
    private var storedVerificationId: String? = null
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null
    private var resendCountdownTimer: CountDownTimer? = null
    private var appTimeoutHandler: Handler? = null
    private var appTimeoutRunnable: Runnable? = null

    // SMS Retriever
    private var smsReceiver: BroadcastReceiver? = null

    // Country codes
    private val countryCodes = listOf(
        "+1 US",
        "+44 UK",
        "+91 IN",
        "+61 AU",
        "+81 JP",
        "+49 DE",
        "+33 FR",
        "+86 CN",
        "+55 BR",
        "+7 RU",
        "+82 KR",
        "+39 IT",
        "+34 ES",
        "+52 MX",
        "+62 ID",
        "+90 TR",
        "+966 SA",
        "+971 AE",
        "+234 NG",
        "+27 ZA"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        auth = FirebaseAuth.getInstance()

        initViews()
        setupCountryCodeSpinner()
        setupClickListeners()

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (otpSection.visibility == View.VISIBLE) {
                    otpSection.visibility = View.GONE
                    phoneSection.visibility = View.VISIBLE
                } else {
                    navigateToLanguageSelection()
                }
            }
        })

        if (TEST_MODE) {
            tvTestModeNotice.visibility = View.VISIBLE
        }
    }

    private fun initViews() {
        phoneSection = findViewById(R.id.phoneSection)
        spinnerCountryCode = findViewById(R.id.spinnerCountryCode)
        tilPhone = findViewById(R.id.tilPhone)
        etPhone = findViewById(R.id.etPhone)
        btnSendCode = findViewById(R.id.btnSendCode)

        otpSection = findViewById(R.id.otpSection)
        tilOtp = findViewById(R.id.tilOtp)
        etOtp = findViewById(R.id.etOtp)
        btnVerify = findViewById(R.id.btnVerify)
        btnResendCode = findViewById(R.id.btnResendCode)
        tvCountdown = findViewById(R.id.tvCountdown)

        progressBar = findViewById(R.id.progressBar)
        tvError = findViewById(R.id.tvError)
        tvTestModeNotice = findViewById(R.id.tvTestModeNotice)
    }

    private fun setupCountryCodeSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            countryCodes
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCountryCode.adapter = adapter

        val defaultIndex = countryCodes.indexOf("+91 IN")
        if (defaultIndex != -1) {
            spinnerCountryCode.setSelection(defaultIndex)
        }
    }

    private fun setupClickListeners() {
        val btnBackToLanguage = findViewById<View>(R.id.btnBackToLanguage)
        btnBackToLanguage?.setOnClickListener {
            if (otpSection.visibility == View.VISIBLE) {
                otpSection.visibility = View.GONE
                phoneSection.visibility = View.VISIBLE
            } else {
                navigateToLanguageSelection()
            }
        }

        btnSendCode.setOnClickListener {
            hideError()
            val phoneNumber = getFullPhoneNumber()
            if (TEST_MODE) {
                // Test mode: no validation, no SMS, no OTP required. Bypass directly.
                tilPhone.error = null
                completeTestSignIn()
            } else if (validatePhoneNumber(phoneNumber)) {
                sendVerificationCode(phoneNumber)
            }
        }

        btnVerify.setOnClickListener {
            hideError()
            val code = etOtp.text?.toString()?.trim() ?: ""
            if (TEST_MODE) {
                // Test mode: any code (including an empty field) is accepted.
                tilOtp.error = null
                completeTestSignIn()
            } else if (validateOtpCode(code)) {
                verifyCode(code)
            }
        }

        btnResendCode.setOnClickListener {
            hideError()
            if (TEST_MODE) {
                etOtp.text?.clear()
                startResendCountdown()
            } else {
                resendVerificationCode()
            }
        }
    }

    // --- Test Mode Sign-In (no OTP) ---

    /**
     * Creates a local session for any phone number / any code, then tries a Firebase anonymous
     * sign-in so Realtime Database rules (auth != null) still pass. Navigation happens either
     * way, so a missing/disabled anonymous provider never blocks development.
     */
    private fun completeTestSignIn() {
        val phoneNumber = getFullPhoneNumber()
        TestSession.signIn(this, phoneNumber)

        // In test mode we use a deterministic phone-based identity, but still sign in
        // anonymously so Realtime Database rules requiring auth can succeed.
        auth.signInAnonymously()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    navigateToProfile()
                } else {
                    // Continue to profile even if anonymous auth fails, but database access
                    // may be blocked until Firebase auth works.
                    navigateToProfile()
                    android.util.Log.w("AnonChatAuth", "Anonymous sign-in failed", task.exception)
                }
            }
    }

    // --- Phone Number Validation ---

    private fun getFullPhoneNumber(): String {
        val selectedCountryCode = countryCodes[spinnerCountryCode.selectedItemPosition]
        val countryCode = selectedCountryCode.split(" ")[0] // Extract "+1", "+44", etc.
        val phoneNumber = etPhone.text?.toString()?.trim() ?: ""
        return "$countryCode$phoneNumber"
    }

    private fun validatePhoneNumber(phoneNumber: String): Boolean {
        val selectedCountryCode = countryCodes[spinnerCountryCode.selectedItemPosition]
        val countryCode = selectedCountryCode.split(" ")[0] // "+91", "+1", etc.
        val localNumber = etPhone.text?.toString()?.trim() ?: ""

        // Country-specific validation
        if (countryCode == "+91" && localNumber.length != 10) {
            tilPhone.error = "Enter a valid 10-digit mobile number"
            return false
        }

        // General E.164 format: "+" followed by 7 to 15 digits
        val e164Regex = Regex("^\\+[1-9]\\d{6,14}$")
        if (phoneNumber.isEmpty() || !e164Regex.matches(phoneNumber)) {
            tilPhone.error = "Enter a valid phone number with country code"
            return false
        }
        tilPhone.error = null
        return true
    }

    private fun validateOtpCode(code: String): Boolean {
        if (code.length != 6 || !code.all { it.isDigit() }) {
            tilOtp.error = "Enter a valid 6-digit code"
            return false
        }
        tilOtp.error = null
        return true
    }

    // --- Send Verification Code ---

    private fun sendVerificationCode(phoneNumber: String) {
        showLoading(true)
        btnSendCode.isEnabled = false

        // Start 15-second app-level timeout
        startAppTimeout()

        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(120L, TimeUnit.SECONDS)
            .setActivity(this)
            .setCallbacks(verificationCallbacks)
            .build()

        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    private fun resendVerificationCode() {
        val phoneNumber = getFullPhoneNumber()
        val token = resendToken

        if (token == null) {
            showError("Unable to resend code. Please try again.")
            return
        }

        showLoading(true)
        btnResendCode.isEnabled = false

        val optionsBuilder = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(120L, TimeUnit.SECONDS)
            .setActivity(this)
            .setCallbacks(verificationCallbacks)
            .setForceResendingToken(token)

        PhoneAuthProvider.verifyPhoneNumber(optionsBuilder.build())

        // Clear OTP input and restart countdown
        etOtp.text?.clear()
        startResendCountdown()
    }

    // --- Verification Callbacks ---

    private val verificationCallbacks =
        object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {

            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                // Auto-verification (instant verification or SMS auto-read)
                cancelAppTimeout()
                showLoading(false)

                // Auto-populate OTP field if code is available
                credential.smsCode?.let { code ->
                    etOtp.setText(code)
                }

                signInWithCredential(credential)
            }

            override fun onVerificationFailed(e: FirebaseException) {
                cancelAppTimeout()
                showLoading(false)
                btnSendCode.isEnabled = true

                val errorMessage = when {
                    e.message?.contains("too many requests", ignoreCase = true) == true ||
                    e.message?.contains("quota", ignoreCase = true) == true ->
                        "Too many attempts. Please wait before trying again."
                    e.message?.contains("network", ignoreCase = true) == true ->
                        "No internet connection. Please check your network and try again."
                    e.message?.contains("invalid", ignoreCase = true) == true ->
                        "Invalid phone number. Please check and try again."
                    else ->
                        "Could not send verification code. Please try again."
                }

                showError(errorMessage)
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                cancelAppTimeout()
                showLoading(false)

                storedVerificationId = verificationId
                resendToken = token

                showOtpSection()
                startResendCountdown()
            }
        }

    // --- OTP Verification ---

    private fun verifyCode(code: String) {
        val verificationId = storedVerificationId
        if (verificationId == null) {
            showError("Verification session expired. Please request a new code.")
            return
        }

        showLoading(true)
        btnVerify.isEnabled = false

        val credential = PhoneAuthProvider.getCredential(verificationId, code)
        signInWithCredential(credential)
    }

    private fun signInWithCredential(credential: PhoneAuthCredential) {
        showLoading(true)

        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                showLoading(false)
                btnVerify.isEnabled = true

                if (task.isSuccessful) {
                    // Sign in success — navigate to ProfileActivity
                    navigateToProfile()
                } else {
                    val exception = task.exception
                    val errorMessage = when {
                        exception?.message?.contains("invalid", ignoreCase = true) == true ||
                        exception?.message?.contains("credential", ignoreCase = true) == true ->
                            "Invalid code. Please try again."
                        exception?.message?.contains("expired", ignoreCase = true) == true -> {
                            // Code expired — clear input and enable resend
                            etOtp.text?.clear()
                            cancelResendCountdown()
                            btnResendCode.isEnabled = true
                            tvCountdown.text = ""
                            "Code has expired. Please request a new one."
                        }
                        else ->
                            "Verification failed. Please try again."
                    }
                    showError(errorMessage)
                }
            }
    }

    // --- UI State Management ---

    private fun showOtpSection() {
        phoneSection.visibility = View.GONE
        otpSection.visibility = View.VISIBLE
        if (!TEST_MODE) {
            startSmsRetriever()
        }
    }

    // --- SMS Retriever API ---

    private fun startSmsRetriever() {
        val client = SmsRetriever.getClient(this)
        val task = client.startSmsRetriever()
        task.addOnSuccessListener {
            registerSmsReceiver()
        }
        // If SMS Retriever fails to start, do nothing — manual entry remains functional
    }

    private fun registerSmsReceiver() {
        smsReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == SmsRetriever.SMS_RETRIEVED_ACTION) {
                    val extras = intent.extras
                    val status = extras?.get(SmsRetriever.EXTRA_STATUS) as? Status
                    when (status?.statusCode) {
                        CommonStatusCodes.SUCCESS -> {
                            val message = extras.getString(SmsRetriever.EXTRA_SMS_MESSAGE) ?: return
                            val code = extractOtpCode(message)
                            if (code != null) {
                                etOtp.setText(code)
                            }
                        }
                        // On timeout or error, do nothing — manual entry remains functional
                    }
                }
            }
        }

        val intentFilter = IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION)
        registerReceiver(smsReceiver, intentFilter, SmsRetriever.SEND_PERMISSION, null)
    }

    private fun extractOtpCode(message: String): String? {
        val regex = Regex("\\d{6}")
        return regex.find(message)?.value
    }

    private fun unregisterSmsReceiver() {
        smsReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
                // Receiver was not registered or already unregistered
            }
            smsReceiver = null
        }
    }

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

    // --- Resend Countdown Timer ---

    private fun startResendCountdown() {
        btnResendCode.isEnabled = false

        resendCountdownTimer?.cancel()
        resendCountdownTimer = object : CountDownTimer(60_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                tvCountdown.text = getString(R.string.auth_countdown, seconds)
            }

            override fun onFinish() {
                tvCountdown.text = ""
                btnResendCode.isEnabled = true
            }
        }.start()
    }

    private fun cancelResendCountdown() {
        resendCountdownTimer?.cancel()
        resendCountdownTimer = null
    }

    // --- App-level Timeout (15 seconds) ---

    private fun startAppTimeout() {
        cancelAppTimeout()

        appTimeoutHandler = Handler(Looper.getMainLooper())
        appTimeoutRunnable = Runnable {
            showLoading(false)
            btnSendCode.isEnabled = true
            showError("Request timed out. Please try again.")
        }
        appTimeoutHandler?.postDelayed(appTimeoutRunnable!!, 15_000L)
    }

    private fun cancelAppTimeout() {
        appTimeoutRunnable?.let { appTimeoutHandler?.removeCallbacks(it) }
        appTimeoutHandler = null
        appTimeoutRunnable = null
    }

    // --- Navigation ---

    private fun navigateToLanguageSelection() {
        if (hasNavigated || isFinishing) return
        hasNavigated = true
        val intent = Intent(this, LanguageSelectionActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun navigateToChatList() {
        if (hasNavigated || isFinishing) return
        hasNavigated = true

        val intent = Intent(this, ChatListActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun navigateToProfile() {
        if (hasNavigated || isFinishing) return
        hasNavigated = true

        val phoneNumber = getFullPhoneNumber()
        val currentUid = auth.currentUser?.uid ?: TestSession.uid(this) ?: java.util.UUID.randomUUID().toString()

        UserDatabase.findUserByPhone(this, phoneNumber) { foundUid, masterUserRecord ->
            val activeUid = foundUid ?: currentUid
            TestSession.setUserId(this, activeUid)

            if (masterUserRecord != null) {
                @Suppress("UNCHECKED_CAST")
                val profileMap = masterUserRecord["profile"] as? Map<String, Any>
                val name = masterUserRecord["displayName"] as? String
                    ?: profileMap?.get("displayName") as? String
                    ?: "AnnoUser"
                val gender = masterUserRecord["gender"] as? String
                    ?: profileMap?.get("gender") as? String
                val age = (masterUserRecord["age"] as? Number)?.toInt()
                    ?: (profileMap?.get("age") as? Number)?.toInt()
                val city = masterUserRecord["city"] as? String
                    ?: profileMap?.get("city") as? String
                val avatar = masterUserRecord["avatar"] as? String

                UserDatabase.saveUser(this, activeUid, phoneNumber, name, gender, age, city, avatar)

                getSharedPreferences("anonchat_prefs", MODE_PRIVATE)
                    .edit().putBoolean("profile_setup_done", true).apply()

                val intent = Intent(this, ChatListActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            } else {
                UserDatabase.saveUser(this, activeUid, phoneNumber, "AnnoUser")

                val setupDone = getSharedPreferences("anonchat_prefs", MODE_PRIVATE)
                    .getBoolean("profile_setup_done", false)

                val intent = if (setupDone) {
                    Intent(this, ChatListActivity::class.java)
                } else {
                    Intent(this, SetupProfileActivity::class.java)
                }
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
        }
    }

    // --- Lifecycle ---

    override fun onDestroy() {
        super.onDestroy()
        cancelResendCountdown()
        cancelAppTimeout()
        unregisterSmsReceiver()
    }
}
