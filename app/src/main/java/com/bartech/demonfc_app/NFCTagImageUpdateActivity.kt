package com.bartech.demonfc_app

import android.app.Dialog
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcF
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CompoundButton
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bartech.demonfc_app.databinding.ActivityNfctagImageUpdateBinding
import com.bartech.demonfc_app.databinding.LoadingDialogBinding
import com.bartech.demonfc_app.smarttag.SmartTagManager
import com.bartech.demonfc_app.smarttag.core.AioiSmartTagDetector
import com.bartech.demonfc_app.smarttag.display.SmartTagDisplayPainter
import com.bartech.demonfc_app.smarttag.model.SmartTagProductFamily
import com.google.android.material.snackbar.Snackbar
import java.io.IOException

/**
 * Pick an image from the device and upload it to an AIOI Smart Tag display.
 *
 * Flow:
 *  1. Tap the tag          -> detect model and display size (instant, from its ID)
 *  2. Move the phone away  -> Select Image, preview, dither / fill options
 *  3. Tap the SAME tag     -> verified, Upload Image is enabled
 *  4. Press Upload Image   -> keep the phone on the tag while it uploads
 */
class NFCTagImageUpdateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNfctagImageUpdateBinding

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
        READING_TAG,
        READY,
        UPLOADING
    }

    private var flowState = FlowState.WAITING_FOR_TAG

    /** True after the same tag was tapped a second time. */
    private var isVerified = false

    // ------------------------------------------------------------
    // Display / image
    // ------------------------------------------------------------

    private var displayWidth = 0
    private var displayHeight = 0

    /** Decoded image as picked by the user (already down-sampled). */
    private var sourceBitmap: Bitmap? = null

    /** Image composed to exactly the display size (before 1-bit conversion). */
    private var composedBitmap: Bitmap? = null

    /**
     * The image already converted to the tag's 1-bit format. Prepared when
     * the preview is built, so nothing has to be computed while the phone
     * is on the tag.
     */
    private var packedImage: ByteArray? = null

    private val hasImage: Boolean
        get() = packedImage != null

    private val pickImage =
        registerForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) {
                loadImage(uri)
            }
        }

    companion object {
        /** The on-screen preview is enlarged by this factor (crisp pixels). */
        private const val PREVIEW_SCALE = 3
    }

    // ============================================================
    // Lifecycle
    // ============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNfctagImageUpdateBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupClickListeners()
        resetScreen()
    }

    override fun onResume() {
        super.onResume()

        if (checkNfcCompatibility()) {
            val adapter = nfcAdapter ?: return
            val pendingIntent = nfcPendingIntent ?: return
            val filters = nfcFilters ?: return
            val techLists = nfcTechLists ?: return

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

            FlowState.READING_TAG,
            FlowState.UPLOADING -> {
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
            binding.nfcInstructionSubText.text =
                "This device does not have NFC hardware."
            updateButtons()
            showErrorResult("This device does not support NFC.")
            return false
        }

        if (!adapter.isEnabled) {
            isNfcUsable = false
            binding.nfcInstructionTitle.text = "NFC Disabled"
            binding.nfcInstructionText.text = "NFC is disabled"
            binding.nfcInstructionSubText.text =
                "Please enable NFC in device settings."
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

            nfcFilters = arrayOf(
                IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
            )

            nfcTechLists = arrayOf(
                arrayOf(NfcF::class.java.name)
            )

        } catch (e: Exception) {
            isNfcUsable = false
            showErrorResult("Unable to initialize NFC: ${e.message}")
            return false
        }

        isNfcUsable = true

        // Restore the text for the current step (e.g. after coming back
        // from the image picker).
        refreshUi()

        return true
    }

    // ============================================================
    // Step 1: detect tag and display size
    // ============================================================

    private fun detectTag(tag: Tag, idm: ByteArray) {

        if (!isNfcUsable) return

        // The model and display size are encoded in the tag's ID, so no
        // NFC session is needed here. Detection is instant and the user
        // can lift the phone straight away.
        if (!AioiSmartTagDetector.isSmartTag(idm)) {
            showErrorResult(
                "The detected NFC tag is not a supported AIOI Smart Tag."
            )
            return
        }

        val model = AioiSmartTagDetector.detectModel(idm)
        val display = AioiSmartTagDetector.getDisplayInfo(model)

        if (display == null) {
            showErrorResult(
                "The display size of this Smart Tag model is unknown."
            )
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
        binding.imageCard.visibility = View.VISIBLE

        isVerified = false
        flowState = FlowState.READY

        // If an image was chosen earlier, rebuild it for this tag's display.
        if (sourceBitmap != null) {
            rebuildPreview()
        }

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

        if (!hasImage) {
            showWarningResult("Tag verified. Now select an image to upload.")
        }
    }

    // ============================================================
    // Step 2: pick and prepare the image
    // ============================================================

    private fun launchImagePicker() {
        pickImage.launch(
            PickVisualMediaRequest(
                ActivityResultContracts.PickVisualMedia.ImageOnly
            )
        )
    }

    private fun loadImage(uri: Uri) {

        val targetW = displayWidth
        val targetH = displayHeight

        if (targetW <= 0 || targetH <= 0) {
            showWarningResult("Tap a Smart Tag first so the display size is known.")
            return
        }

        showLoading("Loading image...")

        Thread {
            try {
                val bitmap = decodeBitmap(uri, targetW, targetH)

                runOnUiThread {
                    sourceBitmap = bitmap

                    // A new image means the tag must be tapped again.
                    isVerified = false

                    rebuildPreview()
                    refreshUi()
                }

            } catch (e: Exception) {
                runOnUiThread {
                    showErrorResult(
                        "Unable to load the image: ${getRootExceptionMessage(e)}"
                    )
                }
            } finally {
                runOnUiThread { hideLoading() }
            }
        }.start()
    }

    /**
     * Decodes the image, down-sampling very large photos so memory use stays
     * small while keeping at least 2x the display resolution.
     */
    private fun decodeBitmap(uri: Uri, targetW: Int, targetH: Int): Bitmap {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // Software bitmap so pixels can be read for 1-bit conversion.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val sample = chooseSampleSize(
                    info.size.width,
                    info.size.height,
                    targetW,
                    targetH
                )
                if (sample > 1) {
                    decoder.setTargetSampleSize(sample)
                }
            }
        }

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = chooseSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                targetW,
                targetH
            )
        }

        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IOException("The selected file is not a readable image.")
    }

    private fun chooseSampleSize(
        width: Int,
        height: Int,
        targetW: Int,
        targetH: Int
    ): Int {
        var sample = 1
        while (
            width / (sample * 2) >= targetW * 2 &&
            height / (sample * 2) >= targetH * 2
        ) {
            sample *= 2
        }
        return sample
    }

    /**
     * Draws the picked image onto a white canvas of exactly the display
     * size, either fitted (whole image visible) or filled (cropped).
     */
    private fun composeForDisplay(
        source: Bitmap,
        width: Int,
        height: Int,
        fill: Boolean
    ): Bitmap {

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // White background also covers transparent PNG areas.
        canvas.drawColor(Color.WHITE)

        val scaleX = width.toFloat() / source.width
        val scaleY = height.toFloat() / source.height
        val scale = if (fill) maxOf(scaleX, scaleY) else minOf(scaleX, scaleY)

        val drawW = source.width * scale
        val drawH = source.height * scale
        val left = (width - drawW) / 2f
        val top = (height - drawH) / 2f

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        canvas.drawBitmap(
            source,
            null,
            RectF(left, top, left + drawW, top + drawH),
            paint
        )

        return result
    }

    /**
     * Rebuilds the composed image and the black-and-white preview from the
     * chosen image and the current Fill / Dither options.
     */
    private fun rebuildPreview() {

        val source = sourceBitmap ?: return

        if (displayWidth <= 0 || displayHeight <= 0) return

        val composed = composeForDisplay(
            source = source,
            width = displayWidth,
            height = displayHeight,
            fill = binding.switchFill.isChecked
        )

        composedBitmap = composed

        // Same conversion the upload uses, so the preview matches the tag.
        val painter = SmartTagDisplayPainter(displayWidth, displayHeight)
        painter.putImage(
            bitmap = composed,
            x = 0,
            y = 0,
            dither = binding.switchDither.isChecked
        )

        // Convert to the tag's 1-bit format now (same result as the preview).
        packedImage = painter.getSmartTagImage(dither = false)

        val preview = painter.getPreview()

        // Nearest-neighbour upscale keeps the pixels crisp.
        val enlarged = Bitmap.createScaledBitmap(
            preview,
            displayWidth * PREVIEW_SCALE,
            displayHeight * PREVIEW_SCALE,
            false
        )

        binding.imagePreview.setImageBitmap(enlarged)
        binding.imagePreview.visibility = View.VISIBLE
        binding.imagePlaceholder.visibility = View.GONE
    }

    // ============================================================
    // Step 4: upload
    // ============================================================

    private fun uploadImage() {

        if (!isNfcUsable) {
            showEnableNfcWarning("Enable NFC before uploading an image.")
            return
        }

        if (flowState != FlowState.READY || !isVerified) {
            showWarningResult("Tap the same tag again first, then press Upload Image.")
            return
        }

        val tag = currentTag
        val data = packedImage

        if (tag == null) {
            showWarningResult("Tap a Smart Tag first.")
            return
        }

        if (data == null) {
            showWarningResult("Select an image first.")
            return
        }

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

                // One status round-trip is enough (it also syncs the
                // command sequence number with the tag).
                smartTagManager.waitForIdle()

                if (deepClean) {
                    runOnUiThread {
                        showLoading("Clearing display...\nKeep the phone on the tag.")
                    }
                    smartTagManager.clearDisplay()
                }

                runOnUiThread {
                    showLoading("Sending image... 0%\nKeep the phone on the tag.")
                }

                var lastPercent = -1

                // Sends the packed image and waits until the display has
                // completely refreshed.
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
                                        "Sending image... $percent%\nKeep the phone on the tag."
                                    }
                                )
                            }
                        }
                    }
                )

                val seconds =
                    (System.currentTimeMillis() - startTime) / 1000.0

                runOnUiThread {
                    // Done - require another tap before the next upload.
                    isVerified = false
                    flowState = FlowState.READY

                    refreshUi()

                    binding.tagStatusValue.text =
                        "Image uploaded (%.1f s)".format(seconds)

                    binding.nfcInstructionTitle.text = "Image Uploaded Successfully"
                    binding.nfcInstructionText.text = "Display Updated"
                    binding.nfcInstructionSubText.text =
                        "You can remove the phone now. To upload again, " +
                                "tap the same tag once more."

                    showSuccessResult("Image uploaded. You can remove the phone.")
                }

            } catch (e: Exception) {
                runOnUiThread {
                    // Most likely the phone moved away. Stay verified so the
                    // user can hold the phone on the tag and press again.
                    flowState = FlowState.READY

                    binding.tagStatusValue.text = "Upload Failed"

                    refreshUi()

                    binding.nfcInstructionTitle.text = "Unable to Upload Image"
                    binding.nfcInstructionText.text = "Upload failed"
                    binding.nfcInstructionSubText.text =
                        "Hold the phone on the Smart Tag and press Upload Image again."

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

    /** Updates instruction text and button states for the current step. */
    private fun refreshUi() {

        when (flowState) {

            FlowState.WAITING_FOR_TAG -> {
                binding.nfcInstructionTitle.text = "AIOI Smart Tag"
                binding.nfcInstructionText.text = "Tap Smart Tag to Begin"
                binding.nfcInstructionSubText.text =
                    "Hold the phone near the tag to detect its display size. " +
                            "You can move away once it is detected."
            }

            FlowState.READING_TAG -> {
                binding.nfcInstructionTitle.text = "AIOI Smart Tag"
                binding.nfcInstructionText.text = "Reading Smart Tag..."
                binding.nfcInstructionSubText.text =
                    "Please keep the phone on the tag."
            }

            FlowState.READY -> {
                binding.nfcInstructionTitle.text = "Smart Tag Ready"

                when {
                    !hasImage -> {
                        binding.nfcInstructionText.text = "Step 2: Select an image"
                        binding.nfcInstructionSubText.text =
                            "Move the phone away and choose an image from your device."
                    }

                    !isVerified -> {
                        binding.nfcInstructionText.text = "Step 3: Tap the tag again"
                        binding.nfcInstructionSubText.text =
                            "Tap the same tag again to enable Upload Image."
                    }

                    else -> {
                        binding.nfcInstructionText.text = "Ready to Upload"
                        binding.nfcInstructionSubText.text =
                            "Keep the phone near the tag and press Upload Image."
                    }
                }
            }

            FlowState.UPLOADING -> {
                binding.nfcInstructionTitle.text = "Uploading Image..."
                binding.nfcInstructionText.text = "Please keep the phone on the tag"
                binding.nfcInstructionSubText.text =
                    "The display refresh can take a few seconds."
            }
        }

        updateButtons()
    }

    private fun updateButtons() {
        val ready = isNfcUsable && flowState == FlowState.READY

        binding.btnSelectImage.isEnabled = ready
        binding.switchFill.isEnabled = ready
        binding.switchDither.isEnabled = ready
        binding.switchDeepClean.isEnabled = ready

        setUploadButtonAppearance(ready && hasImage && isVerified)
    }

    /**
     * Single place that controls the Upload button: enabled state,
     * background color and text color.
     */
    private fun setUploadButtonAppearance(enabled: Boolean) {

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

    private fun resetScreen() {
        currentTag = null
        detectedTagIdm = null
        isVerified = false
        flowState = FlowState.WAITING_FOR_TAG
        smartTagManager.disconnect()

        displayWidth = 0
        displayHeight = 0
        sourceBitmap = null
        composedBitmap = null
        packedImage = null

        binding.imagePreview.setImageDrawable(null)
        binding.imagePreview.visibility = View.GONE
        binding.imagePlaceholder.visibility = View.VISIBLE

        binding.tagInformationCard.visibility = View.GONE
        binding.imageCard.visibility = View.GONE
        binding.tagIdValue.text = "--"
        binding.tagTypeValue.text = "--"
        binding.tagDisplayValue.text = "--"
        binding.tagStatusValue.text = "--"

        refreshUi()
    }

    private fun setupClickListeners() {

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnRefresh.setOnClickListener {
            resetScreen()
        }

        binding.btnClear.setOnClickListener {
            resetScreen()
        }

        binding.btnSelectImage.setOnClickListener {
            launchImagePicker()
        }

        binding.btnUpdate.setOnClickListener {
            uploadImage()
        }

        // Changing an option changes the picture, so the tag must be
        // tapped again before uploading.
        val optionListener =
            CompoundButton.OnCheckedChangeListener { _, _ ->
                if (sourceBitmap != null && flowState == FlowState.READY) {
                    isVerified = false
                    rebuildPreview()
                    refreshUi()
                }
            }

        binding.switchFill.setOnCheckedChangeListener(optionListener)
        binding.switchDither.setOnCheckedChangeListener(optionListener)
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