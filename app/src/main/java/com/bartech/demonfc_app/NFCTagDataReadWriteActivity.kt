package com.bartech.demonfc_app

import android.app.Dialog
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcF
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

        if (isProcessingTag) {
            return
        }

        val tag =
            intent.getParcelableExtra<Tag>(
                NfcAdapter.EXTRA_TAG
            )
                ?: return

        val idm =
            intent.getByteArrayExtra(
                NfcAdapter.EXTRA_ID
            )
                ?: tag.id

        currentTag = tag

        handleNfcTag(
            tag = tag,
            idm = idm
        )
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

        binding.nfcInstructionTitle.text =
            "AIOI Smart Tag"

        binding.nfcInstructionText.text =
            "Tap Smart Tag to Read"

        binding.nfcInstructionSubText.text =
            "Hold the phone near the AIOI Smart Tag."

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
                "Please keep the phone near the tag."

            showLoading(
                "Reading Smart Tag..."
            )
        }

        Thread {

            try {

                /*
                 * IMPORTANT:
                 *
                 * selectTag() only selects the NFC-F technology.
                 * connect() happens immediately afterwards on
                 * the same worker operation.
                 */

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

                // --------------------------------------------------
                // FIRST DISPLAY TEST
                // --------------------------------------------------

                val display = smartTagManager.createDisplay()

                display.showText(
                    text = "HELLO",
                    x = 20,
                    y = 20,
                    font = 3,
                    fieldNo = 1
                )

                val text = decodeUserData(data)

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

                    binding.nfcInstructionTitle.text =
                        "Smart Tag Read Successfully"

                    binding.nfcInstructionText.text =
                        "Data Loaded"

                    binding.nfcInstructionSubText.text =
                        "Edit the data and tap Update Tag."

                    binding.btnUpdate.isEnabled =
                        true

                    showTagDataSection()

                    showSuccessResult(
                        "Smart Tag data read successfully."
                    )
                }

            } catch (e: Exception) {

                runOnUiThread {

                    binding.tagStatusValue.text =
                        "Read Failed"

                    binding.nfcInstructionTitle.text =
                        "Unable to Read Smart Tag"

                    binding.nfcInstructionText.text =
                        "Reading failed"

                    binding.nfcInstructionSubText.text =
                        "Keep the phone near the Smart Tag and try again."

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
        setUpdateButtonAppearance(false)
        binding.nfcInstructionText.text = "Updating Smart Tag..."
        binding.nfcInstructionSubText.text =
            "Please keep the phone near the tag until writing is complete."
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
                    binding.tagIdValue.text = info.idmHex()
                    binding.tagTypeValue.text = formatModel(info.model.family)
                    binding.tagStatusValue.text = "Updated Successfully"
                    binding.nfcInstructionTitle.text = "Smart Tag Updated Successfully"
                    binding.nfcInstructionText.text = "Data Written"
                    binding.nfcInstructionSubText.text =
                        "The new data has been written to the Smart Tag."
                    setUpdateButtonAppearance(true)
                    showSuccessResult("Smart Tag updated successfully.")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.tagStatusValue.text = "Write Failed"
                    binding.nfcInstructionTitle.text = "Unable to Update Smart Tag"
                    binding.nfcInstructionText.text = "Writing failed"
                    binding.nfcInstructionSubText.text =
                        "Keep the phone near the Smart Tag and try again."
                    setUpdateButtonAppearance(true)
                    showErrorResult(e.message ?: "Unable to update the Smart Tag.")
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
        isProcessingTag = false
        smartTagManager.disconnect()

        binding.tagInformationCard.visibility = android.view.View.GONE
        binding.tagDataCard.visibility = android.view.View.GONE
        binding.tagIdValue.text = "--"
        binding.tagTypeValue.text = "--"
        binding.tagStatusValue.text = "--"
        binding.tagDataInput.setText("")
        setUpdateButtonAppearance(false)
        binding.nfcInstructionTitle.text = "AIOI Smart Tag"
        binding.nfcInstructionText.text = "Tap Smart Tag to Read"
        binding.nfcInstructionSubText.text =
            "Hold the phone near the AIOI Smart Tag to read its stored data."
    }

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
