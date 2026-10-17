package com.bartech.demonfc_app

import android.app.Dialog
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcF
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bartech.demonfc_app.databinding.ActivityNfctagDataReadWriteBinding
import com.bartech.demonfc_app.databinding.LoadingDialogBinding
import com.bartech.demonfc_app.smarttag.SmartTagManager
import com.bartech.demonfc_app.smarttag.model.SmartTagProductFamily
import com.bartech.demonfc_app.smarttag.model.SmartTagStatus
import com.google.android.material.snackbar.Snackbar
import java.nio.charset.StandardCharsets

class NFCTagDataReadWriteActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNfctagDataReadWriteBinding

    private var loadingBinding: LoadingDialogBinding? = null
    private var loadingDialog: Dialog? = null

    private var nfcAdapter: NfcAdapter? = null
    private var currentTag: Tag? = null
    private var isNfcUsable = false

    private var nfcPendingIntent: PendingIntent? = null
    private var nfcFilters: Array<IntentFilter>? = null
    private var nfcTechLists: Array<Array<String>>? = null

    @Volatile
    private var isProcessingTag = false

    /**
     * Screen flow:
     *
     * WAITING_FOR_READ -> tap tag            -> READING
     * READING          -> read ok            -> EDITING   (phone can be moved away)
     * EDITING          -> tap SAME tag again -> VERIFIED  (Update button enabled)
     * VERIFIED         -> press Update       -> WRITING
     * WRITING          -> write ok           -> EDITING   (tap again to write again)
     *                     write failed       -> VERIFIED  (retry)
     */
    private enum class FlowState {
        WAITING_FOR_READ,
        READING,
        EDITING,
        VERIFIED,
        WRITING
    }

    private var flowState = FlowState.WAITING_FOR_READ

    /** IDm of the tag that was read; the verification tap must match it. */
    private var readTagIdm: ByteArray? = null

    private val smartTagManager = SmartTagManager()

    companion object {
        /**
         * The demo screen exposes a text editor, so keep a fixed 512-byte
         * user-area region. AIOI limits one user-area write operation to 512 bytes.
         */
        private const val USER_DATA_SIZE = 512
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNfctagDataReadWriteBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setupClickListeners()
        resetScreen()
    }

    override fun onResume() {
        super.onResume()

        if (checkNfcCompatibility()) {

            val adapter = nfcAdapter
                ?: return

            val pendingIntent = nfcPendingIntent
                ?: return

            val filters = nfcFilters
                ?: return

            val techLists = nfcTechLists
                ?: return

            adapter.enableForegroundDispatch(
                this,
                pendingIntent,
                filters,
                techLists
            )
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

    override fun onNewIntent(intent: Intent) {

        super.onNewIntent(intent)

        val tag = getTagFromIntent(intent) ?: return
        val idm = tag.id

        when (flowState) {

            FlowState.WAITING_FOR_READ -> handleNfcTag(tag, idm)

            FlowState.EDITING,
            FlowState.VERIFIED -> verifyTag(tag, idm)

            FlowState.READING,
            FlowState.WRITING -> {
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

    // ============================================================
    // Second tap: confirm it is the same tag, then enable Update
    // ============================================================

    private fun verifyTag(tag: Tag, idm: ByteArray) {

        val expected = readTagIdm

        if (expected == null || !expected.contentEquals(idm)) {
            showWarningResult(
                "This is a different tag. Tap the same tag that was read, " +
                        "or press Clear to start over."
            )
            return
        }

        // Fresh Tag handle - the old one is stale once the phone moved away.
        currentTag = tag
        flowState = FlowState.VERIFIED

        setUpdateButtonAppearance(true)

        binding.tagStatusValue.text = "Tag verified"
        binding.nfcInstructionTitle.text = "Tag Verified"
        binding.nfcInstructionText.text = "Ready to Update"
        binding.nfcInstructionSubText.text =
            "Keep the phone near the tag and press Update Tag."
        binding.editInstructionText.text =
            "Tag verified. Press Update Tag while holding the phone near the tag."
    }

    // ============================================================
    // NFC compatibility
    // ============================================================

    private fun checkNfcCompatibility(): Boolean {

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        val adapter = nfcAdapter

        if (adapter == null) {

            isNfcUsable = false

            binding.nfcInstructionTitle.text = "NFC Not Available"
            binding.nfcInstructionText.text = "NFC is not supported"
            binding.nfcInstructionSubText.text =
                "This device does not have NFC hardware."

            setUpdateButtonAppearance(false)

            showErrorResult(
                "This device does not support NFC."
            )

            return false
        }

        if (!adapter.isEnabled) {

            isNfcUsable = false

            binding.nfcInstructionTitle.text = "NFC Disabled"
            binding.nfcInstructionText.text = "NFC is disabled"
            binding.nfcInstructionSubText.text =
                "Please enable NFC in device settings."

            setUpdateButtonAppearance(false)

            showEnableNfcWarning(
                "NFC is turned off. Enable it to continue."
            )

            return false
        }

        try {

            val intent = Intent(
                this,
                javaClass
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }

            nfcPendingIntent =
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_MUTABLE
                )

            nfcFilters = arrayOf(
                IntentFilter(
                    NfcAdapter.ACTION_TECH_DISCOVERED
                )
            )

            nfcTechLists = arrayOf(
                arrayOf(
                    NfcF::class.java.name
                )
            )

        } catch (e: Exception) {

            isNfcUsable = false

            showErrorResult(
                "Unable to initialize NFC: ${e.message}"
            )

            return false
        }

        isNfcUsable = true

        // Only show the first-step text when we are really at the first step;
        // otherwise coming back from the background would wipe the current
        // step's instructions.
        if (flowState == FlowState.WAITING_FOR_READ) {
            showWaitingForReadText()
        }

        return true
    }

//    private fun enableNfcReaderMode() {
//        val adapter = nfcAdapter ?: return
//        if (!adapter.isEnabled) return
//
//        adapter.enableReaderMode(
//            this,
//            { tag ->
//                if (isProcessingTag) return@enableReaderMode
//                isProcessingTag = true
//                runOnUiThread {
//                    handleNfcTag(tag)
//                }
//            },
//            NfcAdapter.FLAG_READER_NFC_F or
//                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
//            null
//        )
//    }
//
//    private fun disableNfcReaderMode() {
//        nfcAdapter?.disableReaderMode(this)
//    }

    // ============================================================
    // Smart Tag read
    // ============================================================

    private fun handleNfcTag(
        tag: Tag,
        idm: ByteArray
    ) {

        if (!isNfcUsable) {
            return
        }

        if (isProcessingTag) {
            return
        }

        isProcessingTag = true
        flowState = FlowState.READING

        currentTag = tag

        runOnUiThread {

            binding.tagIdValue.text =
                formatTagId(idm)

            binding.tagTypeValue.text =
                "NFC-F / AIOI Smart Tag"

            binding.tagInformationCard.visibility =
                android.view.View.VISIBLE

            binding.nfcInstructionText.text =
                "Reading Smart Tag..."

            binding.nfcInstructionSubText.text =
                "Please keep the phone on the tag."

            showLoading(
                "Reading Smart Tag..."
            )
        }

        Thread {

            var readOk = false

            try {

                val info =
                    smartTagManager.selectTag(tag)

                smartTagManager.connect()

                val status =
                    smartTagManager.checkStatus()

                if (status == SmartTagStatus.BUSY) {
                    smartTagManager.waitForIdle()
                }

                val data =
                    smartTagManager.readUserData(
                        startAddress = 0,
                        size = USER_DATA_SIZE
                    )

                // NOTE: the old "HELLO" display test was removed here.
                // Redrawing the tag's e-paper display takes seconds and
                // forced the user to keep the phone on the tag. Reading
                // user data only needs a fraction of a second.

                val text = decodeUserData(data)

                readOk = true
                readTagIdm = idm.copyOf()

                runOnUiThread {

                    binding.tagIdValue.text =
                        info.idmHex()

                    binding.tagTypeValue.text =
                        formatModel(info.model.family)

                    binding.tagStatusValue.text =
                        formatStatus(
                            status,
                            info.firmwareVersionNumber()
                        )

                    binding.tagDataInput.setText(text)

                    flowState = FlowState.EDITING

                    // Update stays disabled until the same tag is tapped again.
                    setUpdateButtonAppearance(false)

                    binding.nfcInstructionTitle.text =
                        "Smart Tag Read Successfully"

                    binding.nfcInstructionText.text =
                        "Step 2: Edit the data"

                    binding.nfcInstructionSubText.text =
                        "You can move the phone away now. After editing, " +
                                "tap the same tag again to enable Update Tag."

                    binding.editInstructionText.text =
                        "Edit the data above, then tap the same tag again " +
                                "to unlock Update Tag."

                    showTagDataSection()

                    showSuccessResult(
                        "Smart Tag read. You can move the phone away."
                    )
                }

            } catch (e: Exception) {

                runOnUiThread {

                    flowState = FlowState.WAITING_FOR_READ

                    binding.tagStatusValue.text =
                        "Read Failed"

                    binding.nfcInstructionTitle.text =
                        "Unable to Read Smart Tag"

                    binding.nfcInstructionText.text =
                        "Reading failed"

                    binding.nfcInstructionSubText.text =
                        "Keep the phone on the Smart Tag and try again."

                    showErrorResult(
                        getRootExceptionMessage(e)
                    )
                }

            } finally {

                smartTagManager.disconnect()

                runOnUiThread {

                    hideLoading()

                    isProcessingTag = false
                }
            }

        }.start()
    }

    private fun getRootExceptionMessage(
        throwable: Throwable
    ): String {

        var current: Throwable? = throwable

        var lastMessage: String? = null

        while (current != null) {

            if (!current.message.isNullOrBlank()) {
                lastMessage = current.message
            }

            current = current.cause
        }

        return lastMessage
            ?: throwable.javaClass.simpleName
            ?: "Unknown NFC error"
    }

    // ============================================================
    // Smart Tag write
    // ============================================================

    private fun updateSmartTag() {
        if (!isNfcUsable) {
            showEnableNfcWarning("Enable NFC before updating a Smart Tag.")
            return
        }

        if (flowState != FlowState.VERIFIED) {
            showWarningResult(
                "Tap the same tag again first, then press Update Tag."
            )
            return
        }

        val tag = currentTag
        if (tag == null) {
            showWarningResult("Scan a Smart Tag first before updating it.")
            return
        }

        if (isProcessingTag) {
            showWarningResult("Please wait for the current Smart Tag operation to finish.")
            return
        }

        val newText = binding.tagDataInput.text?.toString().orEmpty()
        val encoded = newText.toByteArray(StandardCharsets.UTF_8)

        if (encoded.size > USER_DATA_SIZE) {
            showWarningResult("Data is too large. Maximum size is $USER_DATA_SIZE UTF-8 bytes.")
            return
        }

        isProcessingTag = true
        flowState = FlowState.WRITING
        setUpdateButtonAppearance(false)
        binding.nfcInstructionText.text = "Updating Smart Tag..."
        binding.nfcInstructionSubText.text =
            "Please keep the phone on the tag until writing is complete."
        showLoading("Updating Smart Tag...")

        Thread {
            try {
                val info = smartTagManager.selectTag(tag)
                smartTagManager.connect()
                smartTagManager.checkStatus()
                smartTagManager.waitForIdle()

                // Write a complete fixed-size region so old text cannot remain
                // after a shorter replacement is written.
                val writeBuffer = ByteArray(USER_DATA_SIZE)
                encoded.copyInto(writeBuffer)

                smartTagManager.writeUserData(
                    startAddress = 0,
                    data = writeBuffer
                )

                smartTagManager.waitForIdle()

                runOnUiThread {
                    // Written - go back to editing. To write again the user
                    // taps the tag once more.
                    flowState = FlowState.EDITING
                    setUpdateButtonAppearance(false)

                    binding.tagIdValue.text = info.idmHex()
                    binding.tagTypeValue.text = formatModel(info.model.family)
                    binding.tagStatusValue.text = "Updated Successfully"
                    binding.nfcInstructionTitle.text = "Smart Tag Updated Successfully"
                    binding.nfcInstructionText.text = "Data Written"
                    binding.nfcInstructionSubText.text =
                        "To write again, edit the data and tap the same tag once more."
                    binding.editInstructionText.text =
                        "Edit the data, then tap the same tag again to unlock Update Tag."
                    showSuccessResult("Smart Tag updated successfully.")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    // Most likely the phone moved away. Keep Update enabled so
                    // the user can hold the phone on the tag and press again.
                    flowState = FlowState.VERIFIED
                    setUpdateButtonAppearance(true)

                    binding.tagStatusValue.text = "Write Failed"
                    binding.nfcInstructionTitle.text = "Unable to Update Smart Tag"
                    binding.nfcInstructionText.text = "Writing failed"
                    binding.nfcInstructionSubText.text =
                        "Hold the phone on the Smart Tag and press Update Tag again."
                    showErrorResult(
                        getRootExceptionMessage(e)
                    )
                }
            } finally {
                smartTagManager.disconnect()
                runOnUiThread {
                    hideLoading()
                    isProcessingTag = false
                }
            }
        }.start()
    }

    // ============================================================
    // User-area conversion
    // ============================================================

    private fun decodeUserData(data: ByteArray): String {
        var end = data.size
        while (end > 0 && (data[end - 1].toInt() and 0xFF) == 0) {
            end--
        }
        if (end == 0) return ""
        return String(data, 0, end, StandardCharsets.UTF_8)
    }

    // ============================================================
    // UI
    // ============================================================

    private fun showTagDataSection() {
        binding.tagInformationCard.visibility = android.view.View.VISIBLE
        binding.tagDataCard.visibility = android.view.View.VISIBLE
    }

    private fun resetScreen() {
        currentTag = null
        readTagIdm = null
        flowState = FlowState.WAITING_FOR_READ
        isProcessingTag = false
        smartTagManager.disconnect()

        binding.tagInformationCard.visibility = android.view.View.GONE
        binding.tagDataCard.visibility = android.view.View.GONE
        binding.tagIdValue.text = "--"
        binding.tagTypeValue.text = "--"
        binding.tagStatusValue.text = "--"
        binding.tagDataInput.setText("")
        setUpdateButtonAppearance(false)
        showWaitingForReadText()
    }

    private fun showWaitingForReadText() {
        binding.nfcInstructionTitle.text = "AIOI Smart Tag"
        binding.nfcInstructionText.text = "Tap Smart Tag to Read"
        binding.nfcInstructionSubText.text =
            "Hold the phone near the AIOI Smart Tag. You can move it away " +
                    "as soon as the data is loaded."
    }

    /**
     * Single place that controls the Update button: enabled state,
     * background color and text color. Always call this instead of
     * setting btnUpdate.isEnabled directly.
     */
    private fun setUpdateButtonAppearance(enabled: Boolean) {

        binding.btnUpdate.isEnabled = enabled

        if (enabled) {
            binding.btnUpdate.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.success)

            binding.btnUpdate.setTextColor(
                ContextCompat.getColor(this, R.color.white)
            )
        } else {
            binding.btnUpdate.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.button_disabled)

            binding.btnUpdate.setTextColor(
                ContextCompat.getColor(this, R.color.text_tertiary)
            )
        }
    }

    private fun setupClickListeners() {

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnRefresh.setOnClickListener {

            resetScreen()

            if (checkNfcCompatibility()) {

                // NFC foreground dispatch is enabled
                // automatically in onResume().
                //
                // Nothing else is required here.
            }
        }

        binding.btnClear.setOnClickListener {

            resetScreen()
        }

        binding.btnUpdate.setOnClickListener {

            updateSmartTag()
        }
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

    private fun formatStatus(status: SmartTagStatus, firmwareVersion: Int): String =
        "${status.name} / FW $firmwareVersion"

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