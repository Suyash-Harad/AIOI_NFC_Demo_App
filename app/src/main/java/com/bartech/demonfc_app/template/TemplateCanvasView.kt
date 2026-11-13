package com.bartech.demonfc_app.template

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.bartech.demonfc_app.smarttag.display.SmartTagDisplayPainter
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The designer canvas: a page the size of the tag's display (scaled to fit the
 * screen) on which components can be selected, dragged, resized and rotated.
 *
 *  - touch a component  -> select it and drag to move
 *  - square handle      -> resize (bottom-right corner)
 *  - round handle above -> rotate (snaps to 15 degrees)
 *  - double tap         -> [onEdit]
 *
 * The page is drawn with the same TemplateRenderer used for the upload.
 */
class TemplateCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var template: Template? = null
        set(value) {
            field = value
            selected = null
            renderDirty = true
            invalidate()
        }

    var imageProvider: (String) -> Bitmap? = { null }

    /** Show the page as the tag will show it (pure black / white). */
    var monoPreview: Boolean = false
        set(value) {
            field = value
            renderDirty = true
            invalidate()
        }

    var onSelectionChanged: ((TemplateComponent?) -> Unit)? = null
    var onChanged: (() -> Unit)? = null
    var onEdit: ((TemplateComponent) -> Unit)? = null

    var selected: TemplateComponent? = null
        private set

    // ------------------------------------------------------------
    // Rendering state
    // ------------------------------------------------------------

    private var renderBitmap: Bitmap? = null
    private var renderDirty = true

    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val bitmapPaint = Paint().apply { isFilterBitmap = false }
    private val pageBorderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.DKGRAY
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.rgb(0x1E, 0x88, 0xE5)
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.rgb(0x1E, 0x88, 0xE5)
    }

    private val rotateOffset get() = dp(30f)
    private val handleRadius get() = dp(9f)
    private val handleTouch get() = dp(24f)

    // ------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------

    /** Call after the template (or an image) was changed from outside. */
    fun refresh() {
        renderDirty = true
        val s = selected
        if (s != null && template?.components?.contains(s) != true) {
            select(null)
        }
        invalidate()
    }

    fun select(component: TemplateComponent?) {
        if (selected === component) {
            invalidate()
            return
        }
        selected = component
        onSelectionChanged?.invoke(component)
        invalidate()
    }

    // ------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------

    private fun computeMetrics(t: Template) {
        val pad = dp(12f)
        val availW = width - 2 * pad
        val availH = height - 2 * pad
        scale = max(0.1f, min(availW / t.designWidth, availH / t.designHeight))
        offX = (width - t.designWidth * scale) / 2f
        offY = (height - t.designHeight * scale) / 2f
    }

    private fun ensureRender(t: Template): Bitmap {
        var bmp = renderBitmap
        if (renderDirty || bmp == null || bmp.width != t.designWidth || bmp.height != t.designHeight) {
            var rendered = TemplateRenderer.render(
                t, t.designWidth, t.designHeight,
                emptyMap(), true, imageProvider
            )
            if (monoPreview) {
                val painter = SmartTagDisplayPainter(t.designWidth, t.designHeight)
                painter.putImage(rendered, 0, 0, false)
                rendered = painter.getPreview()
            }
            renderBitmap = rendered
            renderDirty = false
            bmp = rendered
        }
        return bmp
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(0xCF, 0xD3, 0xD8))

        val t = template ?: return
        computeMetrics(t)

        val dst = RectF(
            offX, offY,
            offX + t.designWidth * scale,
            offY + t.designHeight * scale
        )
        canvas.drawBitmap(ensureRender(t), null, dst, bitmapPaint)
        canvas.drawRect(dst, pageBorderPaint)

        val s = selected
        if (s != null && t.components.contains(s)) drawSelection(canvas, t, s)
    }

    private fun drawSelection(canvas: Canvas, t: Template, c: TemplateComponent) {
        val (cx, cy) = centerInView(t, c)
        val hw = halfW(t, c)
        val hh = halfH(t, c)

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(c.rotation)

        canvas.drawRect(-hw, -hh, hw, hh, selectionPaint)

        // rotate handle
        canvas.drawLine(0f, -hh, 0f, -hh - rotateOffset, selectionPaint)
        canvas.drawCircle(0f, -hh - rotateOffset, handleRadius, handleFill)
        canvas.drawCircle(0f, -hh - rotateOffset, handleRadius, handleStroke)

        // resize handle
        val r = handleRadius * 0.85f
        canvas.drawRect(hw - r, hh - r, hw + r, hh + r, handleFill)
        canvas.drawRect(hw - r, hh - r, hw + r, hh + r, handleStroke)

        canvas.restore()
    }

    // ------------------------------------------------------------
    // Geometry helpers (view coordinates)
    // ------------------------------------------------------------

    private fun centerInView(t: Template, c: TemplateComponent): Pair<Float, Float> =
        Pair(offX + c.cx * t.designWidth * scale, offY + c.cy * t.designHeight * scale)

    private fun halfW(t: Template, c: TemplateComponent) =
        max(dp(4f), c.w * t.designWidth * scale / 2f)

    private fun halfH(t: Template, c: TemplateComponent) =
        max(dp(6f), c.h * t.designHeight * scale / 2f)

    /** Converts a touch point into the component's own (unrotated) coordinates. */
    private fun toLocal(t: Template, c: TemplateComponent, vx: Float, vy: Float): Pair<Float, Float> {
        val (cx, cy) = centerInView(t, c)
        val dx = vx - cx
        val dy = vy - cy
        val a = Math.toRadians(-c.rotation.toDouble())
        val lx = (dx * cos(a) - dy * sin(a)).toFloat()
        val ly = (dx * sin(a) + dy * cos(a)).toFloat()
        return Pair(lx, ly)
    }

    private fun hitComponent(t: Template, vx: Float, vy: Float): TemplateComponent? {
        for (i in t.components.indices.reversed()) {
            val c = t.components[i]
            val (lx, ly) = toLocal(t, c, vx, vy)
            val slop = dp(4f)
            if (abs(lx) <= halfW(t, c) + slop && abs(ly) <= halfH(t, c) + slop) return c
        }
        return null
    }

    private fun hitResizeHandle(t: Template, c: TemplateComponent, vx: Float, vy: Float): Boolean {
        val (lx, ly) = toLocal(t, c, vx, vy)
        return hypot(lx - halfW(t, c), ly - halfH(t, c)) <= handleTouch
    }

    private fun hitRotateHandle(t: Template, c: TemplateComponent, vx: Float, vy: Float): Boolean {
        val (lx, ly) = toLocal(t, c, vx, vy)
        return hypot(lx, ly - (-halfH(t, c) - rotateOffset)) <= handleTouch
    }

    // ------------------------------------------------------------
    // Touch
    // ------------------------------------------------------------

    private enum class Mode { NONE, MOVE, RESIZE, ROTATE }

    private var mode = Mode.NONE
    private var lastX = 0f
    private var lastY = 0f
    private var changedDuringGesture = false

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val t = template ?: return false
                val hit = hitComponent(t, e.x, e.y) ?: return false
                select(hit)
                onEdit?.invoke(hit)
                return true
            }
        })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val t = template ?: return false
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                computeMetrics(t)

                lastX = event.x
                lastY = event.y
                changedDuringGesture = false
                mode = Mode.NONE

                val sel = selected
                if (sel != null && t.components.contains(sel)) {
                    if (hitRotateHandle(t, sel, event.x, event.y)) mode = Mode.ROTATE
                    else if (hitResizeHandle(t, sel, event.x, event.y)) mode = Mode.RESIZE
                }

                if (mode == Mode.NONE) {
                    val hit = hitComponent(t, event.x, event.y)
                    select(hit)
                    mode = if (hit != null) Mode.MOVE else Mode.NONE
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val c = selected
                if (c != null && mode != Mode.NONE) {
                    when (mode) {
                        Mode.MOVE -> moveBy(t, c, event.x - lastX, event.y - lastY)
                        Mode.RESIZE -> resizeTo(t, c, event.x, event.y)
                        Mode.ROTATE -> rotateTo(t, c, event.x, event.y)
                        Mode.NONE -> Unit
                    }
                    lastX = event.x
                    lastY = event.y
                    changedDuringGesture = true
                    renderDirty = true
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (changedDuringGesture) onChanged?.invoke()
                mode = Mode.NONE
                changedDuringGesture = false
            }
        }
        return true
    }

    private fun moveBy(t: Template, c: TemplateComponent, dxView: Float, dyView: Float) {
        c.cx = (c.cx + dxView / (scale * t.designWidth)).coerceIn(0f, 1f)
        c.cy = (c.cy + dyView / (scale * t.designHeight)).coerceIn(0f, 1f)
    }

    private fun resizeTo(t: Template, c: TemplateComponent, vx: Float, vy: Float) {
        val (lx, ly) = toLocal(t, c, vx, vy)
        val minPx = dp(6f)

        val newHalfW = max(abs(lx), minPx)
        c.w = (2f * newHalfW / (scale * t.designWidth)).coerceIn(0.01f, 1.5f)

        if (c.type != ComponentType.LINE) {
            val newHalfH = max(abs(ly), minPx)
            c.h = (2f * newHalfH / (scale * t.designHeight)).coerceIn(0.01f, 1.5f)
        }
    }

    private fun rotateTo(t: Template, c: TemplateComponent, vx: Float, vy: Float) {
        val (cx, cy) = centerInView(t, c)
        var deg = Math.toDegrees(atan2((vy - cy).toDouble(), (vx - cx).toDouble())).toFloat() + 90f
        deg = ((deg % 360f) + 360f) % 360f

        val snapped = (deg / 15f).roundToInt() * 15f
        if (abs(deg - snapped) < 4f) deg = snapped % 360f

        c.rotation = deg
    }
}
