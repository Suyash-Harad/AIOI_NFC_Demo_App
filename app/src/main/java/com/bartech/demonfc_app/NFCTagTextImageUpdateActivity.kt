package com.bartech.demonfc_app

import android.app.Dialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcF
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bartech.demonfc_app.databinding.ActivityNfctagTextImageUpdateBinding
import com.bartech.demonfc_app.databinding.ItemTemplateFieldBinding
import com.bartech.demonfc_app.databinding.LoadingDialogBinding
import com.bartech.demonfc_app.smarttag.SmartTagManager
import com.bartech.demonfc_app.smarttag.core.AioiSmartTagDetector
import com.bartech.demonfc_app.smarttag.display.SmartTagDisplayPainter
import com.bartech.demonfc_app.smarttag.model.SmartTagProductFamily
import com.bartech.demonfc_app.template.Template
import com.bartech.demonfc_app.template.TemplateRenderer
import com.bartech.demonfc_app.template.TemplateRepository
import com.bartech.demonfc_app.template.TemplateTagCodec
import com.bartech.demonfc_app.template.TemplateTagException
import com.google.android.material.snackbar.Snackbar

/**
 * Template based update of a Smart Tag.
 *
 * Flow:
 *  1. Tap the tag          -> detect model / display size AND read the record
 *                             stored in the tag memory. If the tag already shows
 *                             a template, that template is selected in the
 *                             dropdown and its fields are filled in. Otherwise
 *                             the dropdown and fields stay blank.
 *  2. Move the phone away  -> choose a template (or design one), fill the fields
 *  3. Tap the SAME tag     -> verified, Upload is enabled
 *  4. Press Upload to Tag  -> writes the record to the tag memory, then the image
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
        READING,
        READY,
        UPLOADING,
        CLEARING
    }

    private var flowState = FlowState.WAITING_FOR_TAG

    /** True after the same tag was tapped a second time. */
    private var isVerified = false

    // ------------------------------------------------------------
    // Display / templates
    // ------------------------------------------------------------

    private var displayWidth = 0
    private var displayHeight = 0

    private lateinit var repo: TemplateRepository

    /** Entries of the dropdown (index 0 of the spinner is the "Select" hint). */
    private var templates: List<Template> = emptyList()

    /** The template currently shown in the form and preview. */
    private var activeTemplate: Template? = null

    /** Entered values by field name. Kept when switching template so equal names carry over. */
    private val fieldValues = linkedMapOf<String, String>()

    private val fieldInputs = ArrayList<EditText>()

    private val editorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val id = result.data?.getStringExtra(TemplateEditorActivity.RESULT_TEMPLATE_ID)
                onEditorFinished(id)
            }
        }

    companion object {
        private const val MAX_VALUE_LENGTH = 100
        private const val SELECT_HINT = "Select a template"
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

        repo = TemplateRepository(applicationContext)

        setupClickListeners()
        setupTemplateSpinner()
        reloadTemplates(null)
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
            FlowState.READING,
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
    // Step 1: detect the tag and read what it currently shows
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
        binding.tagStatusValue.text = "Reading tag memory..."

        binding.tagInformationCard.visibility = View.VISIBLE
        binding.formCard.visibility = View.VISIBLE
        binding.previewCard.visibility = View.VISIBLE

        isVerified = false
        flowState = FlowState.READING
        refreshUi()

        showLoading("Reading tag...\nKeep the phone on the tag.")

        Thread {
            var record: TemplateTagCodec.Record? = null
            var error: Exception? = null

            try {
                record = readTagRecord(tag)
            } catch (e: Exception) {
                error = e
            } finally {
                smartTagManager.disconnect()
            }

            runOnUiThread {
                hideLoading()
                flowState = FlowState.READY
                applyTagRecord(record, error)
            }
        }.start()
    }

    /** Reads the template record from the tag's User Area (null = blank / foreign data). */
    private fun readTagRecord(tag: Tag): TemplateTagCodec.Record? {
        smartTagManager.selectTag(tag)
        smartTagManager.connect()
        smartTagManager.waitForIdle()

        val header = smartTagManager.readUserData(
            TemplateTagCodec.RECORD_ADDRESS,
            TemplateTagCodec.HEADER_SIZE
        )

        val total = TemplateTagCodec.peekLength(header)
        if (total < 0) return null

        val full = smartTagManager.readUserData(TemplateTagCodec.RECORD_ADDRESS, total)
        return TemplateTagCodec.decode(full)
    }

    /**
     * Shows what the tag holds: selects its template in the dropdown and fills
     * the fields, or leaves everything blank.
     */
    private fun applyTagRecord(record: TemplateTagCodec.Record?, error: Exception?) {

        if (error != null) {
            binding.tagStatusValue.text = "Detected (memory not read)"
            showTagNote("Could not read the tag memory. Hold the phone on the tag and tap again " +
                    "after pressing Refresh, or just pick a template.")
            showWarningResult("Could not read the tag memory: ${rootMessage(error)}")
            refreshUi()
            return
        }

        if (record == null) {
            binding.tagStatusValue.text = "Detected - no template on tag"
            showTagNote(null)
            // Blank tag: keep whatever the user already chose, otherwise blank.
            rebuildFields()
            rebuildPreview()
            refreshUi()
            showSuccessResult("Smart Tag detected. You can move the phone away.")
            return
        }

        val local = repo.get(record.templateId)
        val fromTag = record.body

        val chosen: Template?
        var note: String? = null

        if (local != null && (fromTag == null ||
                    TemplateTagCodec.hashOf(local) == record.templateHash)
        ) {
            chosen = local
            if (fromTag == null && TemplateTagCodec.hashOf(local) != record.templateHash) {
                note = "This template was changed on the phone after it was uploaded, " +
                        "so some fields may not line up."
            }
        } else if (fromTag != null) {
            chosen = fromTag
            if (local == null) {
                // The tag carries a template this phone does not have: keep a copy.
                try {
                    repo.save(fromTag)
                } catch (_: Exception) {
                }
                note = "Template \"${fromTag.name}\" was loaded from the tag memory and saved " +
                        "on this phone."
            } else {
                note = "Showing the version stored on the tag. It differs from the one on this phone."
            }
        } else {
            chosen = null
            note = "This tag shows a template that is not on this phone and was too big to be " +
                    "stored on the tag. Select a template to continue."
        }

        fieldValues.clear()
        if (chosen != null) {
            val names = chosen.fieldNames()
            record.values.forEachIndexed { i, v ->
                names.getOrNull(i)?.let { fieldValues[it] = v }
            }
        }

        activeTemplate = chosen
        reloadTemplates(chosen?.id)
        rebuildFields()
        rebuildPreview()

        binding.tagStatusValue.text =
            if (chosen != null) "Showing: ${chosen.name}" else "Unknown template on tag"
        showTagNote(note)
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
                        "or press Refresh to start over."
            )
            return
        }

        // Fresh Tag handle - the old one is stale once the phone moved away.
        currentTag = tag
        isVerified = true

        refreshUi()

        if (activeTemplate == null) {
            showWarningResult("Tag verified. Now choose a template.")
        }
    }

    // ============================================================
    // Template dropdown
    // ============================================================

    private fun setupTemplateSpinner() {
        binding.spinnerTemplate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val chosen = if (position == 0) null else templates.getOrNull(position - 1)

                // Also fires for programmatic selections: nothing to do then.
                if (chosen?.id == activeTemplate?.id) return

                activeTemplate = chosen
                rebuildFields()
                rebuildPreview()
                refreshUi()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun reloadTemplates(selectId: String?) {
        templates = repo.list()

        val names = listOf(SELECT_HINT) + templates.map { it.name }
        binding.spinnerTemplate.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_item, names).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }

        val index = templates.indexOfFirst { it.id == selectId } + 1   // 0 = hint
        binding.spinnerTemplate.setSelection(index, false)
    }

    private fun openEditor(templateId: String?) {
        if (flowState == FlowState.UPLOADING || flowState == FlowState.CLEARING ||
            flowState == FlowState.READING
        ) {
            showWarningResult("Please wait for the current Smart Tag operation to finish.")
            return
        }

        val intent = Intent(this, TemplateEditorActivity::class.java)
        if (templateId != null) intent.putExtra(TemplateEditorActivity.EXTRA_TEMPLATE_ID, templateId)
        if (displayWidth > 0 && displayHeight > 0) {
            intent.putExtra(TemplateEditorActivity.EXTRA_WIDTH, displayWidth)
            intent.putExtra(TemplateEditorActivity.EXTRA_HEIGHT, displayHeight)
        }
        editorLauncher.launch(intent)
    }

    private fun onEditorFinished(savedId: String?) {
        val saved = savedId?.let { repo.get(it) }

        activeTemplate = saved
        reloadTemplates(saved?.id)
        rebuildFields()
        rebuildPreview()

        // The phone moved away while designing: require a fresh tap before uploading.
        if (flowState == FlowState.READY) isVerified = false

        refreshUi()
    }

    // ============================================================
    // Step 2: fields + preview
    // ============================================================

    /** One input per {Field} of the active template. */
    private fun rebuildFields() {
        binding.fieldsContainer.removeAllViews()
        fieldInputs.clear()

        val template = activeTemplate
        if (template == null) {
            binding.tvFieldsHint.text = "Choose a template to fill in its fields."
            binding.tvFieldsHint.visibility = View.VISIBLE
            binding.btnEditTemplate.isEnabled = false
            return
        }

        binding.btnEditTemplate.isEnabled = true

        val names = template.fieldNames()
        if (names.isEmpty()) {
            binding.tvFieldsHint.text = "This template has no fields. It will be uploaded as designed."
            binding.tvFieldsHint.visibility = View.VISIBLE
        } else {
            binding.tvFieldsHint.visibility = View.GONE
        }

        for (name in names) {
            val item = ItemTemplateFieldBinding.inflate(layoutInflater, binding.fieldsContainer, false)
            item.layoutField.hint = name
            item.inputField.filters = arrayOf(InputFilter.LengthFilter(MAX_VALUE_LENGTH))
            item.inputField.setText(fieldValues[name].orEmpty())

            item.inputField.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    fieldValues[name] = s?.toString().orEmpty()
                    rebuildPreview()
                    updateButtons()
                }
            })

            binding.fieldsContainer.addView(item.root)
            fieldInputs += item.inputField
        }
    }

    /** Values of the active template's fields, trimmed. */
    private fun currentValues(): Map<String, String> {
        val template = activeTemplate ?: return emptyMap()
        return template.fieldNames().associateWith { fieldValues[it].orEmpty().trim() }
    }

    private fun barcodeProblems(): List<String> {
        val template = activeTemplate ?: return emptyList()
        if (displayWidth <= 0 || displayHeight <= 0) return emptyList()
        return TemplateRenderer.barcodeProblems(template, displayWidth, displayHeight, currentValues())
    }

    /** Draws the template at exactly the display size. */
    private fun renderTemplate(template: Template): Bitmap =
        TemplateRenderer.render(
            template = template,
            width = displayWidth,
            height = displayHeight,
            values = currentValues(),
            previewMode = false,
            images = { id -> repo.loadImage(id) }
        )

    /**
     * Converts the rendering to the tag's 1-bit format. The same conversion is
     * used for the on-screen preview and for the upload, so the preview is
     * exactly what the tag will show.
     */
    private fun createPainter(template: Template): SmartTagDisplayPainter {
        val painter = SmartTagDisplayPainter(displayWidth, displayHeight)
        painter.putImage(
            bitmap = renderTemplate(template),
            x = 0,
            y = 0,
            dither = false
        )
        return painter
    }

    private fun rebuildPreview() {

        val template = activeTemplate

        if (template == null || displayWidth <= 0 || displayHeight <= 0) {
            binding.imagePreview.setImageDrawable(null)
            showWarnings(emptyList())
            return
        }

        val preview = createPainter(template).getPreview()

        // Nearest-neighbour upscale keeps the pixels crisp.
        val scale = if (displayWidth >= 400) 2 else 3
        val enlarged = Bitmap.createScaledBitmap(
            preview,
            displayWidth * scale,
            displayHeight * scale,
            false
        )

        binding.imagePreview.setImageBitmap(enlarged)
        showWarnings(barcodeProblems())
    }

    private fun showWarnings(problems: List<String>) {
        if (problems.isEmpty()) {
            binding.tvTemplateWarning.visibility = View.GONE
        } else {
            binding.tvTemplateWarning.text = problems.joinToString("\n")
            binding.tvTemplateWarning.visibility = View.VISIBLE
        }
    }

    private fun showTagNote(note: String?) {
        if (note.isNullOrBlank()) {
            binding.tvTagNote.visibility = View.GONE
        } else {
            binding.tvTagNote.text = note
            binding.tvTagNote.visibility = View.VISIBLE
        }
    }

    // ============================================================
    // Step 4: upload (record first, then the picture)
    // ============================================================

    private fun uploadCard() {

        if (!isNfcUsable) {
            showEnableNfcWarning("Enable NFC before uploading.")
            return
        }

        if (flowState != FlowState.READY || !isVerified) {
            showWarningResult("Tap the same tag again first, then press Upload to Tag.")
            return
        }

        val template = activeTemplate
        if (template == null) {
            showWarningResult("Choose a template first.")
            return
        }

        val problems = barcodeProblems()
        if (problems.isNotEmpty()) {
            showWarningResult(problems.first())
            return
        }

        val tag = currentTag
        if (tag == null) {
            showWarningResult("Tap a Smart Tag first.")
            return
        }

        // Build everything NOW, before the phone has to stay on the tag.
        val encoded = try {
            TemplateTagCodec.encode(template, currentValues())
        } catch (e: TemplateTagException) {
            showWarningResult(e.message ?: "The data does not fit into the tag memory.")
            return
        }

        hideKeyboard()

        val data = createPainter(template).getSmartTagImage(dither = false)
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

                // The record goes first: it is quick, and the long display refresh
                // finishes on its own once the picture has been sent.
                runOnUiThread {
                    showLoading("Saving template to tag memory...\nKeep the phone on the tag.")
                }
                smartTagManager.writeUserData(TemplateTagCodec.RECORD_ADDRESS, encoded.bytes)
                smartTagManager.waitForIdle()

                if (deepClean) {
                    runOnUiThread {
                        showLoading("Clearing display...\nKeep the phone on the tag.")
                    }
                    smartTagManager.clearDisplay()
                }

                runOnUiThread {
                    showLoading("Sending picture... 0%\nKeep the phone on the tag.")
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
                                        "Sending picture... $percent%\nKeep the phone on the tag."
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

                    binding.tagStatusValue.text = "Showing: ${template.name}"
                    binding.nfcInstructionTitle.text = "Template Uploaded Successfully"
                    binding.nfcInstructionText.text = "Display Updated (%.1f s)".format(seconds)
                    binding.nfcInstructionSubText.text =
                        "You can remove the phone now. To upload again, " +
                                "tap the same tag once more."

                    if (!encoded.bodyIncluded) {
                        showTagNote(
                            "The template is too big for the tag memory, so only its ID and " +
                                    "data were stored. It will be recognised on this phone only."
                        )
                    } else {
                        showTagNote(null)
                    }

                    showSuccessResult("Template uploaded. You can remove the phone.")
                }

            } catch (e: Exception) {
                runOnUiThread {
                    // Most likely the phone moved away. Stay verified so the
                    // user can hold the phone on the tag and press again.
                    flowState = FlowState.READY
                    binding.tagStatusValue.text = "Upload Failed"
                    refreshUi()

                    binding.nfcInstructionTitle.text = "Unable to Upload"
                    binding.nfcInstructionText.text = "Upload failed"
                    binding.nfcInstructionSubText.text =
                        "Hold the phone on the Smart Tag and press Upload to Tag again."

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

        if (flowState == FlowState.UPLOADING || flowState == FlowState.CLEARING ||
            flowState == FlowState.READING
        ) {
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
                "This clears the picture shown on the tag's screen and the template stored " +
                        "in its memory. Keep the phone on the tag while it works."
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

                // Erase the record header so the tag reads as blank next time.
                smartTagManager.writeUserData(
                    TemplateTagCodec.RECORD_ADDRESS,
                    ByteArray(TemplateTagCodec.HEADER_SIZE)
                )
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
                        "You can remove the phone now. To upload a new template, " +
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
                    "Hold the phone near the tag. Its display size and the template " +
                            "currently on it are read. You can move away once it is detected."
            }

            FlowState.READING -> {
                binding.nfcInstructionTitle.text = "Reading Smart Tag..."
                binding.nfcInstructionText.text = "Please keep the phone on the tag"
                binding.nfcInstructionSubText.text = "Reading the template stored in the tag memory."
            }

            FlowState.READY -> {
                binding.nfcInstructionTitle.text = "Smart Tag Ready"

                when {
                    activeTemplate == null -> {
                        binding.nfcInstructionText.text = "Step 2: Choose a template"
                        binding.nfcInstructionSubText.text =
                            "Move the phone away, pick a template from the list " +
                                    "(or create one) and fill in its fields."
                    }

                    !isVerified -> {
                        binding.nfcInstructionText.text = "Step 3: Tap the tag again"
                        binding.nfcInstructionSubText.text =
                            "Tap the same tag again to enable Upload to Tag."
                    }

                    else -> {
                        binding.nfcInstructionText.text = "Ready to Upload"
                        binding.nfcInstructionSubText.text =
                            "Keep the phone near the tag and press Upload to Tag."
                    }
                }
            }

            FlowState.UPLOADING -> {
                binding.nfcInstructionTitle.text = "Uploading..."
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
        val idle = flowState == FlowState.WAITING_FOR_TAG || flowState == FlowState.READY

        fieldInputs.forEach { it.isEnabled = ready }
        binding.spinnerTemplate.isEnabled = ready
        binding.switchDeepClean.isEnabled = ready

        binding.btnNewTemplate.isEnabled = idle
        binding.btnEditTemplate.isEnabled = idle && activeTemplate != null

        val canUpload = ready &&
                isVerified &&
                activeTemplate != null &&
                barcodeProblems().isEmpty()

        setUploadButtonAppearance(canUpload)
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

        activeTemplate = null
        fieldValues.clear()
        reloadTemplates(null)
        rebuildFields()
        showTagNote(null)
        showWarnings(emptyList())

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
        binding.btnNewTemplate.setOnClickListener { openEditor(null) }
        binding.btnEditTemplate.setOnClickListener { openEditor(activeTemplate?.id) }
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

    private fun rootMessage(throwable: Throwable) = getRootExceptionMessage(throwable)

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