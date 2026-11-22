package com.sahl.gallery

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

object PhotoEditorMath {
    fun buildColorMatrix(
        brightness: Float, // -100..100
        contrast: Float,   // 0.5..1.8 (1.0 neutral)
        saturation: Float, // 0.0..2.0 (1.0 neutral)
        warmth: Float      // -0.3..0.3 (0.0 neutral)
    ): ColorMatrix {
        val cm = ColorMatrix()

        // Saturation
        val sat = ColorMatrix().apply { setSaturation(saturation) }
        cm.postConcat(sat)

        // Contrast + Brightness + Warmth
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

class EditorActivity : Activity() {
    private var sourceUri: Uri? = null
    private var workingBitmap: Bitmap? = null
    private lateinit var previewImg: ImageView

    private var brightness = 0f
    private var contrast = 1f
    private var saturation = 1f
    private var warmth = 0f
    private var cropRatio: Float? = null

    private lateinit var seekBright: SeekBar
    private lateinit var seekContrast: SeekBar
    private lateinit var seekSat: SeekBar
    private lateinit var seekWarmth: SeekBar

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

        // Preview
        previewImg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        root.addView(previewImg, LinearLayout.LayoutParams(-1, 0, 1f))

        // Quick Transform Row: Auto | Rotate | Flip | Crop
        val toolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
        }
        fun toolBtn(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            setOnClickListener { onClick() }
        }
        toolsRow.addView(toolBtn("✨ ${getString(R.string.editor_auto)}") { applyAutoEnhance() })
        toolsRow.addView(toolBtn("↻ ${getString(R.string.editor_rotate)}") { transformBitmap(90f, false) })
        toolsRow.addView(toolBtn("⇄ ${getString(R.string.editor_flip)}") { transformBitmap(0f, true) })
        toolsRow.addView(toolBtn("✂ ${getString(R.string.editor_crop)}") { pickCropRatio() })
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
                setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 12.5f
                setTextColor(Color.LTGRAY)
                layoutParams = LinearLayout.LayoutParams((86 * dp).toInt(), -2)
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

    private fun loadBitmap() {
        val uri = sourceUri ?: return
        Thread {
            val bmp = MediaRepo.decodeSampledBitmap(this, uri, maxPixels = 12_000_000L)
            runOnUiThread {
                if (bmp == null) {
                    finish()
                } else {
                    workingBitmap = bmp
                    previewImg.setImageBitmap(bmp)
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
        previewImg.setImageBitmap(next)
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
                    previewImg.setImageBitmap(cropped)
                }
            }.show()
    }

    private fun updateFilterPreview() {
        val cm = PhotoEditorMath.buildColorMatrix(brightness, contrast, saturation, warmth)
        previewImg.colorFilter = ColorMatrixColorFilter(cm)
    }

    private fun saveEditedCopy() {
        val src = workingBitmap ?: return
        val cm = PhotoEditorMath.buildColorMatrix(brightness, contrast, saturation, warmth)
        Thread {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) }
            canvas.drawBitmap(src, 0f, 0f, paint)

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
