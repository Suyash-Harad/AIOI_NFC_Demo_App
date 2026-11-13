package com.bartech.demonfc_app.template

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.LruCache
import com.bartech.demonfc_app.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom

/**
 * Stores templates as JSON files in the app's private storage:
 *   files/templates/<id>.json
 *   files/template_images/<imageId>.png
 */
class TemplateRepository(context: Context) {

    private val appContext = context.applicationContext
    private val templateDir = File(appContext.filesDir, "templates").apply { mkdirs() }
    private val imageDir = File(appContext.filesDir, "template_images").apply { mkdirs() }

    // ------------------------------------------------------------
    // Templates
    // ------------------------------------------------------------

    /** Built-in template first, then saved templates, oldest first. */
    fun list(): List<Template> {
        val saved = (templateDir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .sortedBy { it.lastModified() }
            .mapNotNull { f ->
                try {
                    TemplateJson.fromJson(f.readText())
                } catch (_: Exception) {
                    null
                }
            }
        return listOf(BuiltInTemplates.employeeIdCard()) + saved
    }

    fun get(id: String): Template? {
        if (id == BuiltInTemplates.EMPLOYEE_ID) return BuiltInTemplates.employeeIdCard()
        val file = File(templateDir, "$id.json")
        if (!file.exists()) return null
        return try {
            TemplateJson.fromJson(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    fun save(template: Template) {
        require(!template.builtIn) { "Built-in templates cannot be overwritten." }
        File(templateDir, "${template.id}.json").writeText(TemplateJson.toJson(template))
    }

    fun delete(id: String) {
        File(templateDir, "$id.json").delete()
    }

    // ------------------------------------------------------------
    // Images
    // ------------------------------------------------------------

    private val imageCache = LruCache<String, Bitmap>(8)

    /** Saves [source] (flattened on white, max 1024 px) and returns its image id. */
    fun saveImage(source: Bitmap): String {
        val id = newImageId()

        var bmp = source
        val maxSide = maxOf(bmp.width, bmp.height)
        if (maxSide > 1024) {
            val s = 1024f / maxSide
            bmp = Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * s).toInt().coerceAtLeast(1),
                (bmp.height * s).toInt().coerceAtLeast(1),
                true
            )
        }

        val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(bmp, 0f, 0f, null)
        }

        FileOutputStream(File(imageDir, "$id.png")).use {
            flat.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        imageCache.put(id, flat)
        return id
    }

    /** Null when the image is not on this phone (e.g. template imported from a tag). */
    fun loadImage(id: String): Bitmap? {
        if (id.isEmpty()) return null
        imageCache.get(id)?.let { return it }

        val bitmap = try {
            if (id == TemplateConstants.BUILTIN_LOGO_ID) loadLogo()
            else {
                val file = File(imageDir, "$id.png")
                if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
            }
        } catch (_: Exception) {
            null
        }

        if (bitmap != null) imageCache.put(id, bitmap)
        return bitmap
    }

    private fun loadLogo(): Bitmap? {
        val res = appContext.resources
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(res, R.drawable.bartech_logo, bounds)

        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inScaled = false
        }
        return BitmapFactory.decodeResource(res, R.drawable.bartech_logo, options)
    }

    private fun newImageId(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        bytes[7] = (bytes[7].toInt() or 0x80).toByte()   // never collides with the reserved logo id
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}

/** The original ID card design, now expressed as a template. */
object BuiltInTemplates {

    const val EMPLOYEE_ID = "00000000-0000-0000-0000-00000000b001"

    fun employeeIdCard() = Template(
        id = EMPLOYEE_ID,
        name = "Employee ID Card",
        designWidth = 264,
        designHeight = 176,
        builtIn = true,
        components = mutableListOf(
            // Border
            TemplateComponent(
                ComponentType.RECTANGLE, cx = 0.5f, cy = 0.5f, w = 1f, h = 1f,
                strokeWidth = 0.012f
            ),
            // Logo
            TemplateComponent(
                ComponentType.IMAGE, cx = 0.25f, cy = 0.15f, w = 0.40f, h = 0.24f,
                imageId = TemplateConstants.BUILTIN_LOGO_ID
            ),
            // ID
            TemplateComponent(
                ComponentType.TEXT, cx = 0.72f, cy = 0.15f, w = 0.50f, h = 0.20f,
                text = "ID: {Employee ID}", textSize = 0.11f, bold = true, align = 2
            ),
            // Divider
            TemplateComponent(
                ComponentType.RECTANGLE, cx = 0.5f, cy = 0.30f, w = 1f, h = 0.016f,
                filled = true
            ),
            // Name
            TemplateComponent(
                ComponentType.TEXT, cx = 0.5f, cy = 0.46f, w = 0.92f, h = 0.20f,
                text = "{Name}", textSize = 0.16f, bold = true
            ),
            // Email
            TemplateComponent(
                ComponentType.TEXT, cx = 0.5f, cy = 0.68f, w = 0.92f, h = 0.14f,
                text = "Email: {Email}", textSize = 0.10f
            ),
            // Phone
            TemplateComponent(
                ComponentType.TEXT, cx = 0.5f, cy = 0.84f, w = 0.92f, h = 0.14f,
                text = "Phone: {Phone}", textSize = 0.10f
            )
        )
    )
}

object TemplateJson {

    fun toJson(t: Template): String {
        val root = JSONObject()
        root.put("id", t.id)
        root.put("name", t.name)
        root.put("designWidth", t.designWidth)
        root.put("designHeight", t.designHeight)

        val arr = JSONArray()
        for (c in t.components) {
            arr.put(
                JSONObject()
                    .put("type", c.type.name)
                    .put("cx", c.cx.toDouble())
                    .put("cy", c.cy.toDouble())
                    .put("w", c.w.toDouble())
                    .put("h", c.h.toDouble())
                    .put("rotation", c.rotation.toDouble())
                    .put("text", c.text)
                    .put("textSize", c.textSize.toDouble())
                    .put("bold", c.bold)
                    .put("align", c.align)
                    .put("strokeWidth", c.strokeWidth.toDouble())
                    .put("filled", c.filled)
                    .put("inverted", c.inverted)
                    .put("barcode", c.barcode.name)
                    .put("imageId", c.imageId)
            )
        }
        root.put("components", arr)
        return root.toString()
    }

    fun fromJson(json: String): Template {
        val root = JSONObject(json)
        val arr = root.optJSONArray("components") ?: JSONArray()
        val list = ArrayList<TemplateComponent>(arr.length())

        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val type = runCatching { ComponentType.valueOf(o.getString("type")) }.getOrNull() ?: continue
            list += TemplateComponent(
                type = type,
                cx = o.optDouble("cx", 0.5).toFloat(),
                cy = o.optDouble("cy", 0.5).toFloat(),
                w = o.optDouble("w", 0.3).toFloat(),
                h = o.optDouble("h", 0.2).toFloat(),
                rotation = o.optDouble("rotation", 0.0).toFloat(),
                text = o.optString("text", ""),
                textSize = o.optDouble("textSize", 0.12).toFloat(),
                bold = o.optBoolean("bold", false),
                align = o.optInt("align", 0),
                strokeWidth = o.optDouble("strokeWidth", 0.012).toFloat(),
                filled = o.optBoolean("filled", false),
                inverted = o.optBoolean("inverted", false),
                barcode = runCatching { BarcodeKind.valueOf(o.optString("barcode")) }
                    .getOrDefault(BarcodeKind.CODE_128),
                imageId = o.optString("imageId", "")
            )
        }

        return Template(
            id = root.getString("id"),
            name = root.optString("name", "Template"),
            designWidth = root.optInt("designWidth", 264),
            designHeight = root.optInt("designHeight", 176),
            components = list
        )
    }
}
