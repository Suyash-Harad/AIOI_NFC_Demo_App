package com.bartech.demonfc_app.template

import java.util.UUID

/**
 * Template data model.
 *
 * All geometry is stored as FRACTIONS of the display (0..1), so one template
 * renders on every Smart Tag size (200x96, 264x176, 296x128, 300x200, 480x280).
 *
 *  - cx, cy      : centre of the component, fraction of display width / height
 *  - w, h        : size, fraction of display width / height
 *  - textSize    : text height, fraction of display height
 *  - strokeWidth : border / line thickness, fraction of display height
 *  - rotation    : degrees, clockwise, around the component centre
 *
 * designWidth/designHeight only say which display the designer canvas showed
 * while the template was being drawn.
 *
 * Text and barcode content may contain {Field Name} placeholders. Every
 * distinct placeholder becomes one input on the update screen.
 */

enum class ComponentType(val code: Int) {
    TEXT(0),
    RECTANGLE(1),
    LINE(2),
    ELLIPSE(3),
    IMAGE(4),
    BARCODE(5);

    companion object {
        fun fromCode(code: Int): ComponentType? = entries.firstOrNull { it.code == code }
    }
}

enum class BarcodeKind(val code: Int, val label: String, val is2D: Boolean) {
    CODE_128(0, "Code 128", false),
    CODE_39(1, "Code 39", false),
    EAN_13(2, "EAN-13", false),
    EAN_8(3, "EAN-8", false),
    UPC_A(4, "UPC-A", false),
    ITF(5, "ITF (Interleaved 2 of 5)", false),
    QR_CODE(6, "QR Code", true),
    DATA_MATRIX(7, "Data Matrix", true),
    PDF_417(8, "PDF417", true),
    AZTEC(9, "Aztec", true);

    companion object {
        fun fromCode(code: Int): BarcodeKind =
            entries.firstOrNull { it.code == code } ?: CODE_128
    }
}

object TemplateConstants {
    /** {Field Name}, 1..24 characters. */
    val FIELD_REGEX = Regex("\\{([^{}]{1,24})\\}")

    /** Reserved image id for the bundled Bartech logo (see TemplateRepository). */
    const val BUILTIN_LOGO_ID = "0000000000000001"

    const val MAX_COMPONENTS = 60
    const val MAX_TEXT_LENGTH = 80

    /** Replaces every {Field} in [text] with its value ("" when missing). */
    fun resolve(text: String, values: Map<String, String>): String =
        FIELD_REGEX.replace(text) { values[it.groupValues[1].trim()].orEmpty() }
}

class TemplateComponent(
    var type: ComponentType,
    var cx: Float = 0.5f,
    var cy: Float = 0.5f,
    var w: Float = 0.3f,
    var h: Float = 0.2f,
    var rotation: Float = 0f,
    /** TEXT: the text. BARCODE: the content. Both may contain {Field} placeholders. */
    var text: String = "",
    var textSize: Float = 0.12f,
    var bold: Boolean = false,
    /** 0 = left, 1 = centre, 2 = right */
    var align: Int = 0,
    var strokeWidth: Float = 0.012f,
    /** RECTANGLE / ELLIPSE: filled instead of outlined. */
    var filled: Boolean = false,
    /** TEXT: white text on a black box. Shapes / line: drawn in white (cut-out). */
    var inverted: Boolean = false,
    var barcode: BarcodeKind = BarcodeKind.CODE_128,
    /** IMAGE: 16 hex characters, resolved by TemplateRepository.loadImage(). */
    var imageId: String = ""
) {
    fun copy() = TemplateComponent(
        type, cx, cy, w, h, rotation, text, textSize, bold, align,
        strokeWidth, filled, inverted, barcode, imageId
    )
}

class Template(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var designWidth: Int,
    var designHeight: Int,
    val components: MutableList<TemplateComponent> = mutableListOf(),
    val builtIn: Boolean = false
) {

    /** Distinct {placeholders}, in the order they appear. */
    fun fieldNames(): List<String> {
        val names = LinkedHashSet<String>()
        for (c in components) {
            if (c.type == ComponentType.TEXT || c.type == ComponentType.BARCODE) {
                TemplateConstants.FIELD_REGEX.findAll(c.text).forEach {
                    val n = it.groupValues[1].trim()
                    if (n.isNotEmpty()) names.add(n)
                }
            }
        }
        return names.toList()
    }

    /** Independent copy with a new id (used to edit built-in templates). */
    fun duplicate(newName: String) = Template(
        id = UUID.randomUUID().toString(),
        name = newName,
        designWidth = designWidth,
        designHeight = designHeight,
        components = components.map { it.copy() }.toMutableList(),
        builtIn = false
    )
}
