package com.bartech.demonfc_app

import android.app.Dialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcF
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Patterns
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bartech.demonfc_app.databinding.ActivityNfctagTextImageUpdateBinding
import com.bartech.demonfc_app.databinding.LoadingDialogBinding
import com.bartech.demonfc_app.smarttag.SmartTagManager
import com.bartech.demonfc_app.smarttag.core.AioiSmartTagDetector
import com.bartech.demonfc_app.smarttag.display.SmartTagDisplayPainter
import com.bartech.demonfc_app.smarttag.display.SmartTagIdCardRenderer
import com.bartech.demonfc_app.smarttag.model.SmartTagProductFamily
import com.google.android.material.snackbar.Snackbar

/**
 * Employee ID card: enter Employee ID, Name, Email and Phone, combine them with the
 * Bartech logo into one picture sized for the tag's display, and upload that
 * picture to the Smart Tag.
 *
 * Flow:
 *  1. Tap the tag          -> detect model and display size (instant)
 *  2. Move the phone away  -> fill in Name / Email / Phone (live preview)
 *  3. Tap the SAME tag     -> verified, Upload ID Card is enabled
 *  4. Press Upload ID Card -> keep the phone on the tag while it uploads
 */
class NFCTagTextImageUpdateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNfctagTextImageUpdateBinding

    private var loadingBinding: LoadingDialogBinding? = null
    private var loadingDialog: Dialog? = null

    // ------------------------------------------------------------
    // NFC
    // ------------------------------------------------------------

    private var nfcAdapter: NfcAdapter? = null
    private var isNfcUsable = false

    private var nfcPendingIntent: PendingIntent? = null
    private var nfcFilters: Array<IntentFilter>? = null
    private var nfcTechLists: Array<Array<String>>? = null

    private val smartTagManager = SmartTagManager()

    private var currentTag: Tag? = null

    /** IDm of the tag detected in step 1; the verification tap must match. */
    private var detectedTagIdm: ByteArray? = null

    // ------------------------------------------------------------
    // Flow state
    // ------------------------------------------------------------

    private enum class FlowState {
        WAITING_FOR_TAG,
        READY,
        UPLOADING,
        CLEARING
    }

    private var flowState = FlowState.WAITING_FOR_TAG

    /** True after the same tag was tapped a second time. */
    private var isVerified = false

    // ------------------------------------------------------------
    // Display / card
    // ------------------------------------------------------------

    private var displayWidth = 0
    private var displayHeight = 0

    /** Logo is decoded once and reused for every preview / upload. */
    private val logo: Bitmap? by lazy { loadLogo() }

    companion object {
        private const val MAX_EMPLOYEE_ID_LENGTH = 15
        private const val MAX_NAME_LENGTH = 40
        private const val MAX_EMAIL_LENGTH = 50
        private val EMPLOYEE_ID_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9\\-_/]*$")
        private val PHONE_REGEX = Regex("^\\+?[0-9 ()\\-]{7,20}$")
    }

    // ============================================================
    // Lifecycle
    // ============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNfctagTextImageUpdateBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupClickListeners()
        setupTextWatchers()
        resetScreen()
    }

    override fun onResume() {
        super.onResume()

        if (checkNfcCompatibility()) {
            val adapter = nfcAdapter ?: return
            val pendingIntent = nfcPendingIntent ?: return
            val filters = nfcFilters ?: return
            val techLists = nfcTechLists ?: return

            adapter.enableForegroundDispatch(this, pendingIntent, filters, techLists)
        }
    }

    override fun onPause() {
        try {
            nfcAdapter?.disableForegroundDispatch(this)
        } catch (_: Exception) {
        }
        super.onPause()
    }

    override fun onDestroy() {
        hideLoading()
        smartTagManager.disconnect()
        super.onDestroy()
    }

    // ============================================================
    // NFC tap handling
    // ============================================================

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val tag = getTagFromIntent(intent) ?: return
        val idm = tag.id

        when (flowState) {
            FlowState.WAITING_FOR_TAG -> detectTag(tag, idm)
            FlowState.READY -> verifyTag(tag, idm)
            FlowState.UPLOADING,
            FlowState.CLEARING -> {
                // Busy - ignore extra taps.
            }
        }
    }

    private fun getTagFromIntent(intent: Intent): Tag? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }

    private fun checkNfcCompatibility(): Boolean {

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        val adapter = nfcAdapter

        if (adapter == null) {
            isNfcUsable = false
            binding.nfcInstructionTitle.text = "NFC Not Available"
            binding.nfcInstructionText.text = "NFC is not supported"
            binding.nfcInstructionSubText.text = "This device does not have NFC hardware."
            updateButtons()
            showErrorResult("This device does not support NFC.")
            return false
        }

        if (!adapter.isEnabled) {
            isNfcUsable = false
            binding.nfcInstructionTitle.text = "NFC Disabled"
            binding.nfcInstructionText.text = "NFC is disabled"
            binding.nfcInstructionSubText.text = "Please enable NFC in device settings."
            updateButtons()
            showEnableNfcWarning("NFC is turned off. Enable it to continue.")
            return false
        }

        try {
            val intent = Intent(this, javaClass).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }

            nfcPendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_MUTABLE
            )

            nfcFilters = arrayOf(IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED))
            nfcTechLists = arrayOf(arrayOf(NfcF::class.java.name))

        } catch (e: Exception) {
            isNfcUsable = false
            showErrorResult("Unable to initialize NFC: ${e.message}")
            return false
        }

        isNfcUsable = true
        refreshUi()
        return true
    }

    // ============================================================
    // Step 1: detect tag and display size
    // ============================================================

    private fun detectTag(tag: Tag, idm: ByteArray) {

        if (!isNfcUsable) return

        if (!AioiSmartTagDetector.isSmartTag(idm)) {
            showErrorResult("The detected NFC tag is not a supported AIOI Smart Tag.")
            return
        }

        val model = AioiSmartTagDetector.detectModel(idm)
        val display = AioiSmartTagDetector.getDisplayInfo(model)

        if (display == null) {
            showErrorResult("The display size of this Smart Tag model is unknown.")
            return
        }

        currentTag = tag
        detectedTagIdm = idm.copyOf()
        displayWidth = display.width
        displayHeight = display.height

        binding.tagIdValue.text = formatTagId(idm)
        binding.tagTypeValue.text = formatModel(model.family)
        binding.tagDisplayValue.text = "${display.width} x ${display.height} px"
        binding.tagStatusValue.text = "Detected"

        binding.tagInformationCard.visibility = View.VISIBLE
        binding.formCard.visibility = View.VISIBLE
        binding.previewCard.visibility = View.VISIBLE

        isVerified = false
        flowState = FlowState.READY

        rebuildPreview()
        refreshUi()
        showSuccessResult("Smart Tag detected. You can move the phone away.")
    }

    // ============================================================
    // Step 3: second tap of the same tag enables Upload
    // ============================================================

    private fun verifyTag(tag: Tag, idm: ByteArray) {

        val expected = detectedTagIdm

        if (expected == null || !expected.contentEquals(idm)) {
            showWarningResult(
                "This is a different tag. Tap the same tag that was detected, " +
                        "or press Clear to start over."
            )
            return
        }

        // Fresh Tag handle - the old one is stale once the phone moved away.
        currentTag = tag
        isVerified = true

        refreshUi()

        if (!isFormValid(showErrors = false)) {
            showWarningResult("Tag verified. Now fill in the employee details.")
        }
    }

    // ============================================================
    // Step 2: form + ID card
    // ============================================================

    private fun setupTextWatchers() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Editing the text does NOT require another tap: the card is
                // rebuilt from the current text at the moment Upload is pressed.
                validateFieldsQuietly()
                rebuildPreview()
                updateButtons()
            }
        }

        binding.inputEmployeeId.addTextChangedListener(watcher)
        binding.inputName.addTextChangedListener(watcher)
        binding.inputEmail.addTextChangedListener(watcher)
        binding.inputPhone.addTextChangedListener(watcher)
    }

    private fun employeeIdText() = binding.inputEmployeeId.text?.toString().orEmpty().trim()
    private fun nameText() = binding.inputName.text?.toString().orEmpty().trim()
    private fun emailText() = binding.inputEmail.text?.toString().orEmpty().trim()
    private fun phoneText() = binding.inputPhone.text?.toString().orEmpty().trim()

    private fun isEmployeeIdValid(v: String) =
        v.isNotEmpty() && v.length <= MAX_EMPLOYEE_ID_LENGTH && EMPLOYEE_ID_REGEX.matches(v)

    private fun isNameValid(v: String) = v.isNotEmpty() && v.length <= MAX_NAME_LENGTH

    private fun isEmailValid(v: String) =
        v.isNotEmpty() && v.length <= MAX_EMAIL_LENGTH &&
                Patterns.EMAIL_ADDRESS.matcher(v).matches()

    private fun isPhoneValid(v: String) = PHONE_REGEX.matches(v)

    /** Shows an error under a field only after something was typed in it. */
    private fun validateFieldsQuietly() {
        binding.layoutEmployeeId.error =
            if (employeeIdText().isNotEmpty() && !isEmployeeIdValid(employeeIdText()))
                "Use letters, numbers, - _ / only" else null
        binding.layoutName.error =
            if (nameText().isNotEmpty() && !isNameValid(nameText())) "Name is too long" else null
        binding.layoutEmail.error =
            if (emailText().isNotEmpty() && !isEmailValid(emailText())) "Enter a valid email" else null
        binding.layoutPhone.error =
            if (phoneText().isNotEmpty() && !isPhoneValid(phoneText())) "Enter a valid phone number" else null
    }

    private fun isFormValid(showErrors: Boolean): Boolean {
        val idOk = isEmployeeIdValid(employeeIdText())
        val nameOk = isNameValid(nameText())
        val emailOk = isEmailValid(emailText())
        val phoneOk = isPhoneValid(phoneText())

        if (showErrors) {
            if (!idOk) binding.layoutEmployeeId.error = "Enter the employee ID"
            if (!nameOk) binding.layoutName.error = "Enter the employee name"
            if (!emailOk) binding.layoutEmail.error = "Enter a valid email"
            if (!phoneOk) binding.layoutPhone.error = "Enter a valid phone number"
        }

        return idOk && nameOk && emailOk && phoneOk
    }

    private fun loadLogo(): Bitmap? = try {
        // Down-sample a very large logo; the tag is at most 480 px wide.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(resources, R.drawable.bartech_logo, bounds)

        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inScaled = false
        }
        BitmapFactory.decodeResource(resources, R.drawable.bartech_logo, options)
    } catch (_: Exception) {
        null
    }

    /** Draws the card at exactly the display size. */
    private fun renderCard(): Bitmap =
        SmartTagIdCardRenderer.render(
            width = displayWidth,
            height = displayHeight,
            logo = logo,
            employeeId = employeeIdText(),
            name = nameText(),
            email = emailText(),
            phone = phoneText()
        )

    /**
     * Converts the card to the tag's 1-bit format. The same conversion is
     * used for the on-screen preview and for the upload, so the preview is
     * exactly what the tag will show.
     */
    private fun createPainter(): SmartTagDisplayPainter {
        val painter = SmartTagDisplayPainter(displayWidth, displayHeight)
        painter.putImage(
            bitmap = renderCard(),
            x = 0,
            y = 0,
            dither = false
        )
        return painter
    }

    private fun rebuildPreview() {

        if (displayWidth <= 0 || displayHeight <= 0) return

        val preview = createPainter().getPreview()

        // Nearest-neighbour upscale keeps the pixels crisp.
        val scale = if (displayWidth >= 400) 2 else 3
        val enlarged = Bitmap.createScaledBitmap(
            preview,
            displayWidth * scale,
            displayHeight * scale,
            false
        )

        binding.imagePreview.setImageBitmap(enlarged)
    }

    // ============================================================
    // Step 4: upload
    // ============================================================

    private fun uploadCard() {

        if (!isNfcUsable) {
            showEnableNfcWarning("Enable NFC before uploading.")
            return
        }

        if (flowState != FlowState.READY || !isVerified) {
            showWarningResult("Tap the same tag again first, then press Upload ID Card.")
            return
        }

        if (!isFormValid(showErrors = true)) {
            showWarningResult("Please fix the highlighted fields.")
            return
        }

        val tag = currentTag
        if (tag == null) {
            showWarningResult("Tap a Smart Tag first.")
            return
        }

        hideKeyboard()

        // Build the 1-bit image NOW, before the phone has to stay on the tag.
        val data = createPainter().getSmartTagImage(dither = false)
        val width = displayWidth
        val height = displayHeight
        val deepClean = binding.switchDeepClean.isChecked

        flowState = FlowState.UPLOADING
        refreshUi()

        showLoading("Connecting to tag...\nKeep the phone on the tag.")

        Thread {
            val startTime = System.currentTimeMillis()

            try {
                smartTagManager.selectTag(tag)
                smartTagManager.connect()
                smartTagManager.waitForIdle()

                if (deepClean) {
                    runOnUiThread {
                        showLoading("Clearing display...\nKeep the phone on the tag.")
                    }
                    smartTagManager.clearDisplay()
                }

                runOnUiThread {
                    showLoading("Sending ID card... 0%\nKeep the phone on the tag.")
                }

                var lastPercent = -1

                smartTagManager.showImageData(
                    imageData = data,
                    x = 0,
                    y = 0,
                    width = width,
                    height = height,
                    onProgress = { sent, total ->
                        val percent = sent * 100 / total
                        if (percent != lastPercent) {
                            lastPercent = percent
                            runOnUiThread {
                                showLoading(
                                    if (sent == total) {
                                        "Refreshing display...\nKeep the phone on the tag."
                                    } else {
                                        "Sending ID card... $percent%\nKeep the phone on the tag."
                                    }
                                )
                            }
                        }
                    }
                )

                val seconds = (System.currentTimeMillis() - startTime) / 1000.0

                runOnUiThread {
                    // Done - require another tap before the next upload.
                    isVerified = false
                    flowState = FlowState.READY
                    refreshUi()

                    binding.tagStatusValue.text = "ID card uploaded (%.1f s)".format(seconds)
                    binding.nfcInstructionTitle.text = "ID Card Uploaded Successfully"
                    binding.nfcInstructionText.text = "Display Updated"
                    binding.nfcInstructionSubText.text =
                        "You can remove the phone now. To upload again, " +
                                "tap the same tag once more."

                    showSuccessResult("ID card uploaded. You can remove the phone.")
                }

            } catch (e: Exception) {
                runOnUiThread {
                    // Most likely the phone moved away. Stay verified so the
                    // user can hold the phone on the tag and press again.
                    flowState = FlowState.READY
                    binding.tagStatusValue.text = "Upload Failed"
                    refreshUi()

                    binding.nfcInstructionTitle.text = "Unable to Upload ID Card"
                    binding.nfcInstructionText.text = "Upload failed"
                    binding.nfcInstructionSubText.text =
                        "Hold the phone on the Smart Tag and press Upload ID Card again."

                    showErrorResult(getRootExceptionMessage(e))
                }
            } finally {
                smartTagManager.disconnect()
                runOnUiThread { hideLoading() }
            }
        }.start()
    }

    // ============================================================
    // Clear options: clear the form, or clear the image on the tag
    // ============================================================

    private fun showClearOptions() {

        if (flowState == FlowState.UPLOADING || flowState == FlowState.CLEARING) {
            showWarningResult("Please wait for the current Smart Tag operation to finish.")
            return
        }

        val options = arrayOf(
            "Clear form",
            "Clear tag image"
        )

        AlertDialog.Builder(this)
            .setTitle("Clear")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> resetScreen()
                    1 -> confirmClearTagImage()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClearTagImage() {

        if (!isNfcUsable) {
            showEnableNfcWarning("Enable NFC before clearing a Smart Tag.")
            return
        }

        if (flowState != FlowState.READY) {
            showWarningResult("Tap a Smart Tag first.")
            return
        }

        if (!isVerified) {
            showWarningResult("Tap the same tag again first, then choose Clear tag image.")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Clear tag image")
            .setMessage(
                "This clears the picture shown on the tag's screen. " +
                        "Keep the phone on the tag while it works."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ -> clearTagImage() }
            .show()
    }

    private fun clearTagImage() {

        val tag = currentTag

        if (tag == null) {
            showWarningResult("Tap a Smart Tag first.")
            return
        }

        flowState = FlowState.CLEARING
        refreshUi()

        showLoading("Clearing tag image...\nKeep the phone on the tag.")

        Thread {
            try {
                smartTagManager.selectTag(tag)
                smartTagManager.connect()
                smartTagManager.waitForIdle()

                // Clears the screen and waits until it has really refreshed.
                smartTagManager.clearDisplay()

                runOnUiThread {
                    // Done - require another tap before the next operation.
                    isVerified = false
                    flowState = FlowState.READY
                    refreshUi()

                    binding.tagStatusValue.text = "Image cleared"
                    binding.nfcInstructionTitle.text = "Tag Image Cleared"
                    binding.nfcInstructionText.text = "Display Cleared"
                    binding.nfcInstructionSubText.text =
                        "You can remove the phone now. To upload a new ID card, " +
                                "tap the same tag once more."

                    showSuccessResult("Tag image cleared. You can remove the phone.")
                }

            } catch (e: Exception) {
                runOnUiThread {
                    // Most likely the phone moved away. Stay verified so the
                    // user can hold the phone on the tag and try again.
                    flowState = FlowState.READY
                    binding.tagStatusValue.text = "Clear Failed"
                    refreshUi()

                    binding.nfcInstructionTitle.text = "Unable to Clear Tag Image"
                    binding.nfcInstructionText.text = "Clearing failed"
                    binding.nfcInstructionSubText.text =
                        "Hold the phone on the Smart Tag and choose Clear tag image again."

                    showErrorResult(getRootExceptionMessage(e))
                }
            } finally {
                smartTagManager.disconnect()
                runOnUiThread { hideLoading() }
            }
        }.start()
    }

    // ============================================================
    // UI
    // ============================================================

    private fun refreshUi() {

        when (flowState) {

            FlowState.WAITING_FOR_TAG -> {
                binding.nfcInstructionTitle.text = "AIOI Smart Tag"
                binding.nfcInstructionText.text = "Tap Smart Tag to Begin"
                binding.nfcInstructionSubText.text =
                    "Hold the phone near the tag to detect its display size. " +
                            "You can move away once it is detected."
            }

            FlowState.READY -> {
                binding.nfcInstructionTitle.text = "Smart Tag Ready"

                when {
                    !isFormValid(showErrors = false) -> {
                        binding.nfcInstructionText.text = "Step 2: Enter employee details"
                        binding.nfcInstructionSubText.text =
                            "Move the phone away and fill in Employee ID, Name, Email and Phone."
                    }

                    !isVerified -> {
                        binding.nfcInstructionText.text = "Step 3: Tap the tag again"
                        binding.nfcInstructionSubText.text =
                            "Tap the same tag again to enable Upload ID Card."
                    }

                    else -> {
                        binding.nfcInstructionText.text = "Ready to Upload"
                        binding.nfcInstructionSubText.text =
                            "Keep the phone near the tag and press Upload ID Card."
                    }
                }
            }

            FlowState.UPLOADING -> {
                binding.nfcInstructionTitle.text = "Uploading ID Card..."
                binding.nfcInstructionText.text = "Please keep the phone on the tag"
                binding.nfcInstructionSubText.text =
                    "The display refresh can take a few seconds."
            }

            FlowState.CLEARING -> {
                binding.nfcInstructionTitle.text = "Clearing Tag Image..."
                binding.nfcInstructionText.text = "Please keep the phone on the tag"
                binding.nfcInstructionSubText.text =
                    "The display refresh can take a few seconds."
            }
        }

        updateButtons()
    }

    private fun updateButtons() {
        val ready = isNfcUsable && flowState == FlowState.READY

        binding.inputEmployeeId.isEnabled = ready
        binding.inputName.isEnabled = ready
        binding.inputEmail.isEnabled = ready
        binding.inputPhone.isEnabled = ready
        binding.switchDeepClean.isEnabled = ready

        setUploadButtonAppearance(ready && isVerified && isFormValid(showErrors = false))
    }

    private fun setUploadButtonAppearance(enabled: Boolean) {

        binding.btnUpdate.isEnabled = enabled

        if (enabled) {
            binding.btnUpdate.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.success)
            binding.btnUpdate.setTextColor(ContextCompat.getColor(this, R.color.white))
        } else {
            binding.btnUpdate.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.button_disabled)
            binding.btnUpdate.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
        }
    }

    private fun resetScreen() {
        currentTag = null
        detectedTagIdm = null
        isVerified = false
        flowState = FlowState.WAITING_FOR_TAG
        smartTagManager.disconnect()

        displayWidth = 0
        displayHeight = 0

        binding.inputEmployeeId.setText("")
        binding.inputName.setText("")
        binding.inputEmail.setText("")
        binding.inputPhone.setText("")
        binding.layoutEmployeeId.error = null
        binding.layoutName.error = null
        binding.layoutEmail.error = null
        binding.layoutPhone.error = null

        binding.imagePreview.setImageDrawable(null)

        binding.tagInformationCard.visibility = View.GONE
        binding.formCard.visibility = View.GONE
        binding.previewCard.visibility = View.GONE
        binding.tagIdValue.text = "--"
        binding.tagTypeValue.text = "--"
        binding.tagDisplayValue.text = "--"
        binding.tagStatusValue.text = "--"

        refreshUi()
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { resetScreen() }
        binding.btnClear.setOnClickListener { showClearOptions() }
        binding.btnUpdate.setOnClickListener { uploadCard() }
    }

    private fun hideKeyboard() {
        val view: View = currentFocus ?: binding.root
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
        (currentFocus as? EditText)?.clearFocus()
    }

    private fun formatTagId(id: ByteArray): String = id.joinToString(" ") {
        "%02X".format(it.toInt() and 0xFF)
    }

    private fun formatModel(family: SmartTagProductFamily): String = when (family) {
        SmartTagProductFamily.SMARTTAG_20 -> "AIOI SmartTag 2.0"
        SmartTagProductFamily.SMARTTAG_27 -> "AIOI SmartTag 2.7"
        SmartTagProductFamily.SMARTCARD_29 -> "AIOI SmartCard 2.9"
        SmartTagProductFamily.SMARTTAG_29 -> "AIOI SmartTag 2.9"
        SmartTagProductFamily.SMARTTAG_37 -> "AIOI SmartTag 3.7"
        SmartTagProductFamily.UNKNOWN -> "Unknown AIOI Smart Tag"
    }

    private fun getRootExceptionMessage(throwable: Throwable): String {
        var current: Throwable? = throwable
        var lastMessage: String? = null

        while (current != null) {
            if (!current.message.isNullOrBlank()) {
                lastMessage = current.message
            }
            current = current.cause
        }

        return lastMessage ?: throwable.javaClass.simpleName
    }

    // ============================================================
    // Loading dialog
    // ============================================================

    private fun showLoading(message: String = "Please wait...") {
        if (loadingDialog == null) {
            loadingBinding = LoadingDialogBinding.inflate(layoutInflater)
            loadingDialog = Dialog(this).apply {
                setCancelable(false)
                setContentView(loadingBinding!!.root)
                window?.setBackgroundDrawableResource(android.R.color.transparent)
            }
        }

        loadingBinding?.tvLoadingMessage?.text = message

        if (loadingDialog?.isShowing != true) {
            loadingDialog?.show()
            loadingDialog?.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    private fun hideLoading() {
        loadingDialog?.dismiss()
        loadingDialog = null
        loadingBinding = null
    }

    // ============================================================
    // Snackbar
    // ============================================================

    private fun showSuccessResult(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setBackgroundTint(ContextCompat.getColor(this, R.color.success))
            .setTextColor(Color.WHITE)
            .show()
    }

    private fun showWarningResult(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setBackgroundTint(ContextCompat.getColor(this, R.color.warning))
            .setTextColor(Color.WHITE)
            .show()
    }

    private fun showErrorResult(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setBackgroundTint(ContextCompat.getColor(this, R.color.error))
            .setTextColor(Color.WHITE)
            .show()
    }

    private fun showEnableNfcWarning(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setBackgroundTint(ContextCompat.getColor(this, R.color.warning))
            .setTextColor(Color.WHITE)
            .setAction("Enable") {
                startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
            }
            .setActionTextColor(Color.WHITE)
            .show()
    }
}