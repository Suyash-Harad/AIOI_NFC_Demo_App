package com.bartech.demonfc_app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bartech.demonfc_app.databinding.ActivityTemplateEditorBinding
import com.bartech.demonfc_app.template.BarcodeKind
import com.bartech.demonfc_app.template.ComponentType
import com.bartech.demonfc_app.template.Template
import com.bartech.demonfc_app.template.TemplateComponent
import com.bartech.demonfc_app.template.TemplateConstants
import com.bartech.demonfc_app.template.TemplateRepository
import com.bartech.demonfc_app.template.TemplateTagCodec
import kotlin.math.roundToInt

/**
 * Template designer ("paint" screen).
 *
 * Add text, shapes, lines, images and barcodes, then drag / resize / rotate
 * them on a canvas the size of the tag's display. Double-tap a component (or
 * press Properties) to edit its exact position, size, rotation and content.
 *
 * Put {Field Name} in a text or barcode to make it fill-in data: every
 * distinct {Field Name} becomes an input on the update screen.
 */
class TemplateEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TEMPLATE_ID = "template_id"
        const val EXTRA_WIDTH = "display_width"
        const val EXTRA_HEIGHT = "display_height"
        const val RESULT_TEMPLATE_ID = "saved_template_id"

        private class DisplaySize(val label: String, val width: Int, val height: Int)

        private val SIZES = listOf(
            DisplaySize("200 x 96  (SmartTag 2.0)", 200, 96),
            DisplaySize("264 x 176  (SmartTag 2.7)", 264, 176),
            DisplaySize("296 x 128  (SmartTag 2.9)", 296, 128),
            DisplaySize("300 x 200  (SmartCard 2.9)", 300, 200),
            DisplaySize("480 x 280  (SmartTag 3.7)", 480, 280)
        )
    }

    private lateinit var binding: ActivityTemplateEditorBinding
    private lateinit var repo: TemplateRepository
    private lateinit var template: Template

    private var dirty = false
    private var sizeSpinnerReady = false

    // Image picking
    private var pendingImageComponent: TemplateComponent? = null
    private var pendingImageIsNew = false

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            onImagePicked(uri)
        }

    // ============================================================
    // Lifecycle
    // ============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTemplateEditorBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        repo = TemplateRepository(applicationContext)
        template = loadInitialTemplate()

        binding.canvasView.imageProvider = { id -> repo.loadImage(id) }
        binding.canvasView.template = template
        binding.canvasView.onSelectionChanged = { updateActionButtons() }
        binding.canvasView.onChanged = { dirty = true }
        binding.canvasView.onEdit = { showProperties(it) }

        binding.etName.setText(template.name)
        setupSizeSpinner()
        setupButtons()
        updateActionButtons()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmExit()
        })
    }

    private fun loadInitialTemplate(): Template {
        val id = intent.getStringExtra(EXTRA_TEMPLATE_ID)
        if (id != null) {
            val existing = repo.get(id)
            if (existing != null) {
                // Built-in templates are never changed: edit a copy.
                return if (existing.builtIn) existing.duplicate("${existing.name} (copy)") else existing
            }
        }

        val w = intent.getIntExtra(EXTRA_WIDTH, 0)
        val h = intent.getIntExtra(EXTRA_HEIGHT, 0)
        return Template(
            name = "New template",
            designWidth = if (w > 0) w else 264,
            designHeight = if (h > 0) h else 176
        )
    }

    // ============================================================
    // Size spinner
    // ============================================================

    private fun setupSizeSpinner() {
        val labels = SIZES.map { it.label }.toMutableList()
        var index = SIZES.indexOfFirst {
            it.width == template.designWidth && it.height == template.designHeight
        }
        if (index < 0) {
            labels += "${template.designWidth} x ${template.designHeight}  (custom)"
            index = labels.size - 1
        }

        binding.spSize.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spSize.setSelection(index, false)

        binding.spSize.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!sizeSpinnerReady) {
                    sizeSpinnerReady = true     // ignore the initial callback
                    return
                }
                val size = SIZES.getOrNull(pos) ?: return
                if (size.width == template.designWidth && size.height == template.designHeight) return
                template.designWidth = size.width
                template.designHeight = size.height
                dirty = true
                binding.canvasView.refresh()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    // ============================================================
    // Buttons
    // ============================================================

    private fun setupButtons() {
        binding.btnBack.setOnClickListener { confirmExit() }

        binding.btnAddText.setOnClickListener {
            addComponent(
                TemplateComponent(
                    ComponentType.TEXT, w = 0.5f, h = 0.2f,
                    text = "{Field}", textSize = 0.12f
                )
            )
        }
        binding.btnAddRect.setOnClickListener {
            addComponent(TemplateComponent(ComponentType.RECTANGLE, w = 0.3f, h = 0.25f))
        }
        binding.btnAddLine.setOnClickListener {
            addComponent(TemplateComponent(ComponentType.LINE, w = 0.5f, h = 0.03f))
        }
        binding.btnAddEllipse.setOnClickListener {
            addComponent(TemplateComponent(ComponentType.ELLIPSE, w = 0.25f, h = 0.3f))
        }
        binding.btnAddBarcode.setOnClickListener {
            addComponent(
                TemplateComponent(
                    ComponentType.BARCODE, w = 0.6f, h = 0.3f,
                    text = "{Code}", barcode = BarcodeKind.CODE_128
                )
            )
        }
        binding.btnAddImage.setOnClickListener {
            pendingImageComponent = TemplateComponent(ComponentType.IMAGE, w = 0.3f, h = 0.3f)
            pendingImageIsNew = true
            pickImage.launch("image/*")
        }

        binding.btnProps.setOnClickListener { binding.canvasView.selected?.let { showProperties(it) } }
        binding.btnDuplicate.setOnClickListener { duplicateSelected() }
        binding.btnForward.setOnClickListener { moveSelected(+1) }
        binding.btnBackward.setOnClickListener { moveSelected(-1) }
        binding.btnDelete.setOnClickListener { deleteSelected() }
        binding.btnMono.setOnClickListener {
            binding.canvasView.monoPreview = !binding.canvasView.monoPreview
            binding.btnMono.text = if (binding.canvasView.monoPreview) "Colour view" else "Tag view"
        }

        binding.btnSave.setOnClickListener { save() }
    }

    private fun updateActionButtons() {
        val has = binding.canvasView.selected != null
        listOf(
            binding.btnProps, binding.btnDuplicate, binding.btnForward,
            binding.btnBackward, binding.btnDelete
        ).forEach {
            it.isEnabled = has
            it.alpha = if (has) 1f else 0.4f
        }
    }

    // ============================================================
    // Component operations
    // ============================================================

    private fun addComponent(c: TemplateComponent) {
        if (template.components.size >= TemplateConstants.MAX_COMPONENTS) {
            toast("A template can have at most ${TemplateConstants.MAX_COMPONENTS} components.")
            return
        }
        template.components.add(c)
        dirty = true
        binding.canvasView.refresh()
        binding.canvasView.select(c)
    }

    private fun duplicateSelected() {
        val c = binding.canvasView.selected ?: return
        val copy = c.copy()
        copy.cx = (c.cx + 0.04f).coerceAtMost(1f)
        copy.cy = (c.cy + 0.06f).coerceAtMost(1f)
        addComponent(copy)
    }

    private fun moveSelected(delta: Int) {
        val c = binding.canvasView.selected ?: return
        val list = template.components
        val from = list.indexOf(c)
        val to = from + delta
        if (from < 0 || to !in list.indices) return
        list.removeAt(from)
        list.add(to, c)
        dirty = true
        binding.canvasView.refresh()
    }

    private fun deleteSelected() {
        val c = binding.canvasView.selected ?: return
        template.components.remove(c)
        dirty = true
        binding.canvasView.select(null)
        binding.canvasView.refresh()
    }

    // ============================================================
    // Image picking
    // ============================================================

    private fun onImagePicked(uri: Uri?) {
        val target = pendingImageComponent
        val isNew = pendingImageIsNew
        pendingImageComponent = null

        if (uri == null || target == null) return

        val bitmap = decodeUri(uri)
        if (bitmap == null) {
            toast("Could not open that image.")
            return
        }

        target.imageId = repo.saveImage(bitmap)

        if (isNew) {
            // Match the box to the picture's proportions.
            val boxWpx = target.w * template.designWidth
            val boxHpx = boxWpx * bitmap.height / bitmap.width
            target.h = (boxHpx / template.designHeight).coerceIn(0.05f, 0.9f)
            addComponent(target)
        } else {
            dirty = true
            binding.canvasView.refresh()
        }
    }

    private fun decodeUri(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (_: Exception) {
        null
    }

    // ============================================================
    // Properties dialog
    // ============================================================

    private fun showProperties(c: TemplateComponent) {
        val dw = template.designWidth.toFloat()
        val dh = template.designHeight.toFloat()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(col) }

        // ---- common geometry (shown in pixels of the design display) ----
        val eX = numberField(col, "X - centre (px)", fmt(c.cx * dw))
        val eY = numberField(col, "Y - centre (px)", fmt(c.cy * dh))
        val eW = numberField(col, "Width (px)", fmt(c.w * dw))
        val eH = numberField(col, "Height (px)", fmt(c.h * dh))
        val eRot = numberField(col, "Rotation (degrees)", fmt(c.rotation))

        // ---- type specific ----
        var eText: EditText? = null
        var eSize: EditText? = null
        var cBold: CheckBox? = null
        var sAlign: Spinner? = null
        var cInverted: CheckBox? = null
        var eStroke: EditText? = null
        var cFilled: CheckBox? = null
        var sBarcode: Spinner? = null
        var dialog: AlertDialog? = null

        when (c.type) {
            ComponentType.TEXT -> {
                eText = textField(
                    col, "Text  (use {Field Name} for data)", c.text,
                    TemplateConstants.MAX_TEXT_LENGTH
                )
                eSize = numberField(col, "Text size (px)", fmt(c.textSize * dh))
                sAlign = spinnerField(col, "Alignment", listOf("Left", "Centre", "Right"), c.align)
                cBold = checkField(col, "Bold", c.bold)
                cInverted = checkField(col, "White text on black box", c.inverted)
            }

            ComponentType.RECTANGLE, ComponentType.ELLIPSE -> {
                eStroke = numberField(col, "Border thickness (px)", fmt(c.strokeWidth * dh))
                cFilled = checkField(col, "Filled", c.filled)
                cInverted = checkField(col, "White (cut-out)", c.inverted)
            }

            ComponentType.LINE -> {
                eStroke = numberField(col, "Thickness (px)", fmt(c.strokeWidth * dh))
                cInverted = checkField(col, "White (cut-out)", c.inverted)
            }

            ComponentType.BARCODE -> {
                sBarcode = spinnerField(
                    col, "Barcode type",
                    BarcodeKind.entries.map { it.label }, c.barcode.ordinal
                )
                eText = textField(
                    col, "Content  (use {Field Name} for data)", c.text,
                    TemplateConstants.MAX_TEXT_LENGTH
                )
                col.addView(TextView(this).apply {
                    text = "The designer shows a sample value. The real barcode uses the data " +
                            "entered when updating the tag."
                    textSize = 12f
                    setPadding(0, dp(6), 0, 0)
                })
            }

            ComponentType.IMAGE -> {
                col.addView(Button(this).apply {
                    text = "Replace image"
                    setOnClickListener {
                        pendingImageComponent = c
                        pendingImageIsNew = false
                        dialog?.dismiss()
                        pickImage.launch("image/*")
                    }
                })
            }
        }

        fun num(e: EditText?, default: Float): Float =
            e?.text?.toString()?.trim()?.toFloatOrNull() ?: default

        val built = AlertDialog.Builder(this)
            .setTitle(typeTitle(c.type))
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                c.cx = (num(eX, c.cx * dw) / dw).coerceIn(0f, 1f)
                c.cy = (num(eY, c.cy * dh) / dh).coerceIn(0f, 1f)
                c.w = (num(eW, c.w * dw) / dw).coerceIn(0.01f, 1.5f)
                c.h = (num(eH, c.h * dh) / dh).coerceIn(0.01f, 1.5f)
                c.rotation = ((num(eRot, c.rotation) % 360f) + 360f) % 360f

                when (c.type) {
                    ComponentType.TEXT -> {
                        c.text = eText?.text?.toString().orEmpty()
                        c.textSize = (num(eSize, c.textSize * dh) / dh).coerceIn(0.03f, 1f)
                        c.align = sAlign?.selectedItemPosition ?: c.align
                        c.bold = cBold?.isChecked ?: c.bold
                        c.inverted = cInverted?.isChecked ?: c.inverted
                    }

                    ComponentType.RECTANGLE, ComponentType.ELLIPSE -> {
                        c.strokeWidth = (num(eStroke, c.strokeWidth * dh) / dh).coerceIn(0.004f, 0.5f)
                        c.filled = cFilled?.isChecked ?: c.filled
                        c.inverted = cInverted?.isChecked ?: c.inverted
                    }

                    ComponentType.LINE -> {
                        c.strokeWidth = (num(eStroke, c.strokeWidth * dh) / dh).coerceIn(0.004f, 0.5f)
                        c.inverted = cInverted?.isChecked ?: c.inverted
                    }

                    ComponentType.BARCODE -> {
                        c.text = eText?.text?.toString().orEmpty()
                        c.barcode = BarcodeKind.entries.getOrElse(
                            sBarcode?.selectedItemPosition ?: -1
                        ) { c.barcode }
                    }

                    ComponentType.IMAGE -> Unit
                }

                dirty = true
                binding.canvasView.refresh()
            }
            .create()

        dialog = built
        built.show()
    }

    private fun typeTitle(t: ComponentType) = when (t) {
        ComponentType.TEXT -> "Text"
        ComponentType.RECTANGLE -> "Rectangle"
        ComponentType.LINE -> "Line"
        ComponentType.ELLIPSE -> "Ellipse"
        ComponentType.IMAGE -> "Image"
        ComponentType.BARCODE -> "Barcode"
    }

    // ---- dialog field helpers ----

    private fun label(col: LinearLayout, text: String) {
        col.addView(TextView(this).apply {
            this.text = text
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
        })
    }

    private fun numberField(col: LinearLayout, label: String, value: String): EditText {
        label(col, label)
        return EditText(this).apply {
            setText(value)
            inputType = InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine()
            col.addView(this)
        }
    }

    private fun textField(col: LinearLayout, label: String, value: String, maxLen: Int): EditText {
        label(col, label)
        return EditText(this).apply {
            setText(value)
            filters = arrayOf(InputFilter.LengthFilter(maxLen))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            col.addView(this)
        }
    }

    private fun checkField(col: LinearLayout, label: String, checked: Boolean): CheckBox =
        CheckBox(this).apply {
            text = label
            isChecked = checked
            col.addView(this)
        }

    private fun spinnerField(col: LinearLayout, label: String, items: List<String>, selected: Int): Spinner {
        label(col, label)
        return Spinner(this).apply {
            adapter = ArrayAdapter(
                this@TemplateEditorActivity,
                android.R.layout.simple_spinner_item, items
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(selected.coerceIn(0, items.lastIndex))
            col.addView(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun fmt(v: Float): String =
        if (v == v.roundToInt().toFloat()) v.roundToInt().toString() else "%.1f".format(v)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    // ============================================================
    // Save / exit
    // ============================================================

    private fun save() {
        val name = binding.etName.text?.toString().orEmpty().trim()
        if (name.isEmpty()) {
            toast("Enter a template name.")
            return
        }
        template.name = name

        if (template.components.isEmpty()) {
            toast("Add at least one component first.")
            return
        }

        try {
            repo.save(template)
        } catch (e: Exception) {
            toast("Could not save: ${e.message}")
            return
        }
        dirty = false

        AlertDialog.Builder(this)
            .setTitle("Template saved")
            .setMessage(capacityMessage())
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                setResult(
                    RESULT_OK,
                    android.content.Intent().putExtra(RESULT_TEMPLATE_ID, template.id)
                )
                finish()
            }
            .show()
    }

    /** Tells the user whether the design fits into the tag's memory. */
    private fun capacityMessage(): String {
        return try {
            val body = TemplateTagCodec.storedBodySize(template)
            val fields = template.fieldNames().size
            val free = TemplateTagCodec.RECORD_CAPACITY - TemplateTagCodec.HEADER_SIZE - body - 1 - fields

            if (free >= 0) {
                "This template needs $body bytes of tag memory and leaves about $free bytes " +
                        "for the data. It will be stored on the tag, so any phone can read it."
            } else {
                "This template ($body bytes) is too big to be stored on the tag. The tag will only " +
                        "remember its ID and data, so the template must stay on this phone. " +
                        "Remove a few components to fit it on the tag."
            }
        } catch (e: Exception) {
            e.message ?: "Template saved."
        }
    }

    private fun confirmExit() {
        if (!dirty) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Discard changes?")
            .setMessage("Your changes to this template have not been saved.")
            .setNegativeButton("Keep editing", null)
            .setPositiveButton("Discard") { _, _ -> finish() }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}