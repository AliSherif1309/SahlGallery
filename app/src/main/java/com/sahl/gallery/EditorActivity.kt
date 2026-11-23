package com.sahl.gallery

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

object PhotoEditorMath {
    fun buildColorMatrix(
        brightness: Float,
        contrast: Float,
        saturation: Float,
        warmth: Float
    ): ColorMatrix {
        val cm = ColorMatrix()
        val sat = ColorMatrix().apply { setSaturation(saturation) }
        cm.postConcat(sat)

        val translate = (-0.5f * contrast + 0.5f) * 255f + brightness
        val rScale = contrast * (1f + warmth * 0.35f)
        val gScale = contrast
        val bScale = contrast * (1f - warmth * 0.35f)

        val scaleMat = ColorMatrix(
            floatArrayOf(
                rScale, 0f, 0f, 0f, translate,
                0f, gScale, 0f, 0f, translate,
                0f, 0f, bScale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(scaleMat)
        return cm
    }

    data class CropRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    fun computeCenteredCropRect(width: Int, height: Int, targetRatio: Float?): CropRect {
        if (targetRatio == null || targetRatio <= 0f || width <= 0 || height <= 0) {
            return CropRect(0, 0, width, height)
        }
        val currentRatio = width.toFloat() / height.toFloat()
        return if (currentRatio > targetRatio) {
            val newW = (height * targetRatio).toInt().coerceIn(1, width)
            val left = (width - newW) / 2
            CropRect(left, 0, left + newW, height)
        } else {
            val newH = (width / targetRatio).toInt().coerceIn(1, height)
            val top = (height - newH) / 2
            CropRect(0, top, width, top + newH)
        }
    }
}

enum class BrushMode { NONE, MOSAIC, PEN }

data class EditorStroke(
    val isMosaic: Boolean,
    val color: Int,
    val radiusNorm: Float,
    val points: MutableList<PointF> = mutableListOf()
)

/**
 * Interactive editor canvas supporting ColorMatrix preview,
 * Mosaic/Blur privacy brush, and Pen markup strokes in normalized image coordinates.
 */
class EditorCanvasView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }
    var colorFilter: ColorMatrixColorFilter? = null
        set(value) {
            field = value
            invalidate()
        }
    var brushMode: BrushMode = BrushMode.NONE
    var penColor: Int = Color.parseColor("#E53935")
    val strokes = mutableListOf<EditorStroke>()

    private val imgRect = RectF()
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val penPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val mosaicPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private var activeStroke: EditorStroke? = null

    fun undo(): Boolean {
        if (strokes.isEmpty()) return false
        strokes.removeAt(strokes.lastIndex)
        invalidate()
        return true
    }

    fun clearStrokes() {
        strokes.clear()
        invalidate()
    }

    private fun updateImgRect() {
        val bmp = bitmap ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val scale = minOf(vw / bmp.width, vh / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val dx = (vw - dw) / 2f
        val dy = (vh - dh) / 2f
        imgRect.set(dx, dy, dx + dw, dy + dh)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        updateImgRect()
        bmpPaint.colorFilter = colorFilter
        canvas.drawBitmap(bmp, null, imgRect, bmpPaint)

        for (s in strokes) {
            if (s.isMosaic) {
                drawMosaicPreview(canvas, bmp, s, imgRect)
            } else {
                drawPenStroke(canvas, s, imgRect)
            }
        }
    }

    private fun drawPenStroke(canvas: Canvas, s: EditorStroke, target: RectF) {
        if (s.points.isEmpty()) return
        penPaint.color = s.color
        penPaint.strokeWidth = (s.radiusNorm * target.width()).coerceAtLeast(4f)
        if (s.points.size == 1) {
            val p = s.points[0]
            canvas.drawPoint(target.left + p.x * target.width(), target.top + p.y * target.height(), penPaint)
            return
        }
        val path = Path()
        path.moveTo(target.left + s.points[0].x * target.width(), target.top + s.points[0].y * target.height())
        for (i in 1 until s.points.size) {
            val p = s.points[i]
            path.lineTo(target.left + p.x * target.width(), target.top + p.y * target.height())
        }
        canvas.drawPath(path, penPaint)
    }

    private fun drawMosaicPreview(canvas: Canvas, bmp: Bitmap, s: EditorStroke, target: RectF) {
        val radiusPx = (s.radiusNorm * target.width()).coerceAtLeast(10f)
        val block = (radiusPx * 0.45f).coerceAtLeast(8f)
        for (p in s.points) {
            val cx = target.left + p.x * target.width()
            val cy = target.top + p.y * target.height()
            val bx = (p.x * (bmp.width - 1)).toInt().coerceIn(0, bmp.width - 1)
            val by = (p.y * (bmp.height - 1)).toInt().coerceIn(0, bmp.height - 1)
            mosaicPaint.color = bmp.getPixel(bx, by)
            val snapX = ((cx / block).toInt()) * block
            val snapY = ((cy / block).toInt()) * block
            canvas.drawRect(snapX - block, snapY - block, snapX + block, snapY + block, mosaicPaint)
        }
    }

    fun renderStrokesToBitmap(outBmp: Bitmap) {
        val canvas = Canvas(outBmp)
        val target = RectF(0f, 0f, outBmp.width.toFloat(), outBmp.height.toFloat())
        for (s in strokes) {
            if (s.isMosaic) {
                drawMosaicPreview(canvas, outBmp, s, target)
            } else {
                drawPenStroke(canvas, s, target)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (brushMode == BrushMode.NONE || bitmap == null || imgRect.width() <= 0f) return false
        val nx = ((event.x - imgRect.left) / imgRect.width()).coerceIn(0f, 1f)
        val ny = ((event.y - imgRect.top) / imgRect.height()).coerceIn(0f, 1f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val stroke = EditorStroke(
                    isMosaic = brushMode == BrushMode.MOSAIC,
                    color = penColor,
                    radiusNorm = if (brushMode == BrushMode.MOSAIC) 0.045f else 0.012f
                )
                stroke.points.add(PointF(nx, ny))
                strokes.add(stroke)
                activeStroke = stroke
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                activeStroke?.points?.add(PointF(nx, ny))
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeStroke = null
            }
        }
        return true
    }
}

class EditorActivity : Activity() {
    private var sourceUri: Uri? = null
    private var workingBitmap: Bitmap? = null
    private lateinit var canvasView: EditorCanvasView

    private var brightness = 0f
    private var contrast = 1f
    private var saturation = 1f
    private var warmth = 0f
    private var cropRatio: Float? = null

    private lateinit var btnBlur: TextView
    private lateinit var btnDraw: TextView
    private lateinit var seekBright: SeekBar
    private lateinit var seekContrast: SeekBar
    private lateinit var seekSat: SeekBar
    private lateinit var seekWarmth: SeekBar

    private val penColors = listOf(
        Color.parseColor("#E53935"),
        Color.parseColor("#0E6B4E"),
        Color.parseColor("#FDD835"),
        Color.WHITE
    )
    private var penColorIdx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SahlCore.catchCrashes(this)
        sourceUri = intent.data
        if (sourceUri == null) {
            finish()
            return
        }

        val dp = resources.displayMetrics.density
        val accent = Color.parseColor("#0E6B4E")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = true
        }

        // Top Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
        }
        topBar.addView(TextView(this).apply {
            text = "✕"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt())
            setOnClickListener { finish() }
        })
        topBar.addView(TextView(this).apply {
            text = getString(R.string.editor_title)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        topBar.addView(Button(this).apply {
            text = getString(R.string.editor_save)
            setBackgroundColor(accent)
            setTextColor(Color.WHITE)
            setOnClickListener { saveEditedCopy() }
        })
        root.addView(topBar)

        // Interactive Canvas
        canvasView = EditorCanvasView(this)
        root.addView(canvasView, LinearLayout.LayoutParams(-1, 0, 1f))

        // Quick Tools Row: Auto | Rotate | Flip | Crop | Blur | Draw | Undo
        val toolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding((4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
        }
        fun toolBtn(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            setOnClickListener { onClick() }
        }
        toolsRow.addView(toolBtn("✨ ${getString(R.string.editor_auto)}") { applyAutoEnhance() })
        toolsRow.addView(toolBtn("↻") { transformBitmap(90f, false) })
        toolsRow.addView(toolBtn("⇄") { transformBitmap(0f, true) })
        toolsRow.addView(toolBtn("✂") { pickCropRatio() })
        btnBlur = toolBtn("▦ ${getString(R.string.editor_blur)}") {
            canvasView.brushMode = if (canvasView.brushMode == BrushMode.MOSAIC) BrushMode.NONE else BrushMode.MOSAIC
            refreshBrushButtons()
        }
        btnDraw = toolBtn("${getString(R.string.editor_draw)}") {
            if (canvasView.brushMode == BrushMode.PEN) {
                penColorIdx = (penColorIdx + 1) % penColors.size
                if (penColorIdx == 0) {
                    canvasView.brushMode = BrushMode.NONE
                } else {
                    canvasView.penColor = penColors[penColorIdx]
                }
            } else {
                penColorIdx = 0
                canvasView.penColor = penColors[0]
                canvasView.brushMode = BrushMode.PEN
            }
            refreshBrushButtons()
        }
        toolsRow.addView(btnBlur)
        toolsRow.addView(btnDraw)
        toolsRow.addView(toolBtn("↶") { canvasView.undo() })
        root.addView(toolsRow)

        // Sliders
        val sliders = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#14171A"))
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        }

        fun sliderRow(label: String, max: Int, initial: Int, onChange: (Int) -> Unit): SeekBar {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(Color.LTGRAY)
                layoutParams = LinearLayout.LayoutParams((82 * dp).toInt(), -2)
            })
            val sb = SeekBar(this).apply {
                this.max = max
                progress = initial
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                        onChange(p)
                        updateFilterPreview()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            row.addView(sb)
            sliders.addView(row)
            return sb
        }

        seekBright = sliderRow(getString(R.string.editor_brightness), 100, 50) { p -> brightness = (p - 50) * 1.4f }
        seekContrast = sliderRow(getString(R.string.editor_contrast), 100, 50) { p -> contrast = 0.6f + (p / 100f) * 0.8f }
        seekSat = sliderRow(getString(R.string.editor_saturation), 100, 50) { p -> saturation = p / 50f }
        seekWarmth = sliderRow(getString(R.string.editor_warmth), 100, 50) { p -> warmth = (p - 50) / 160f }
        root.addView(sliders)

        setContentView(root)
        loadBitmap()
    }

    private fun refreshBrushButtons() {
        val accent = Color.parseColor("#0E6B4E")
        btnBlur.setTextColor(if (canvasView.brushMode == BrushMode.MOSAIC) accent else Color.WHITE)
        btnDraw.setTextColor(if (canvasView.brushMode == BrushMode.PEN) canvasView.penColor else Color.WHITE)
    }

    private fun loadBitmap() {
        val uri = sourceUri ?: return
        Thread {
            val bmp = MediaRepo.decodeSampledBitmap(this, uri, maxPixels = 12_000_000L)
            runOnUiThread {
                if (bmp == null) {
                    finish()
                } else {
                    workingBitmap = bmp
                    canvasView.bitmap = bmp
                }
            }
        }.start()
    }

    private fun applyAutoEnhance() {
        brightness = 12f
        contrast = 1.12f
        saturation = 1.12f
        warmth = 0.06f
        seekBright.progress = 58
        seekContrast.progress = 65
        seekSat.progress = 56
        seekWarmth.progress = 60
        updateFilterPreview()
    }

    private fun transformBitmap(rotateDeg: Float, flipH: Boolean) {
        val bmp = workingBitmap ?: return
        val m = Matrix()
        if (rotateDeg != 0f) m.postRotate(rotateDeg)
        if (flipH) m.postScale(-1f, 1f)
        val next = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (next !== bmp) bmp.recycle()
        workingBitmap = next
        canvasView.clearStrokes()
        canvasView.bitmap = next
    }

    private fun pickCropRatio() {
        val labels = arrayOf("Original", "1:1", "4:3", "16:9", "9:16")
        val ratios = arrayOf(null, 1f, 4f / 3f, 16f / 9f, 9f / 16f)
        AlertDialog.Builder(this)
            .setTitle(R.string.editor_crop)
            .setItems(labels) { _, which ->
                cropRatio = ratios[which]
                val bmp = workingBitmap ?: return@setItems
                if (cropRatio != null) {
                    val r = PhotoEditorMath.computeCenteredCropRect(bmp.width, bmp.height, cropRatio)
                    val cropped = Bitmap.createBitmap(bmp, r.left, r.top, r.width, r.height)
                    if (cropped !== bmp) bmp.recycle()
                    workingBitmap = cropped
                    canvasView.clearStrokes()
                    canvasView.bitmap = cropped
                }
            }.show()
    }

    private fun updateFilterPreview() {
        val cm = PhotoEditorMath.buildColorMatrix(brightness, contrast, saturation, warmth)
        canvasView.colorFilter = ColorMatrixColorFilter(cm)
    }

    private fun saveEditedCopy() {
        val src = workingBitmap ?: return
        val cm = PhotoEditorMath.buildColorMatrix(brightness, contrast, saturation, warmth)
        Thread {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) }
            canvas.drawBitmap(src, 0f, 0f, paint)
            canvasView.renderStrokesToBitmap(out)

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_${System.currentTimeMillis()}_edited")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SahlGallery")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val cr = contentResolver
            val destUri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (destUri != null) {
                cr.openOutputStream(destUri)?.use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cr.update(destUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                }
            }
            out.recycle()
            runOnUiThread {
                if (destUri != null) {
                    Toast.makeText(this, R.string.editor_saved, Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK, Intent().setData(destUri))
                    finish()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        workingBitmap?.recycle()
        workingBitmap = null
        super.onDestroy()
    }
}
