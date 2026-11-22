package com.sahl.gallery

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

/**
 * Smooth Matrix-backed zoomable ImageView with pinch-to-zoom,
 * double-tap zoom (1x <-> 2.5x), pan, and single-tap callback.
 */
class ZoomImageView(context: Context) : ImageView(context) {
    private val imgMatrix = Matrix()
    private val matrixValues = FloatArray(9)
    private var baseScale = 1f
    private var currentScale = 1f
    private val lastTouch = PointF()
    private var isDragging = false

    var onSingleTap: (() -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            val target = (currentScale * factor).coerceIn(1f, 5f)
            val actualFactor = target / currentScale
            currentScale = target
            imgMatrix.postScale(actualFactor, actualFactor, detector.focusX, detector.focusY)
            constrainMatrix()
            imageMatrix = imgMatrix
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (currentScale > 1.5f) 1f else 2.5f
            val factor = target / currentScale
            currentScale = target
            imgMatrix.postScale(factor, factor, e.x, e.y)
            constrainMatrix()
            imageMatrix = imgMatrix
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        post { fitToScreen() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToScreen()
    }

    fun fitToScreen() {
        val d = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (vw <= 0f || vh <= 0f || dw <= 0f || dh <= 0f) return

        baseScale = minOf(vw / dw, vh / dh)
        currentScale = 1f
        imgMatrix.reset()
        imgMatrix.postScale(baseScale, baseScale)
        val dx = (vw - dw * baseScale) / 2f
        val dy = (vh - dh * baseScale) / 2f
        imgMatrix.postTranslate(dx, dy)
        imageMatrix = imgMatrix
    }

    private fun constrainMatrix() {
        val d = drawable ?: return
        imgMatrix.getValues(matrixValues)
        val tx = matrixValues[Matrix.MTRANS_X]
        val ty = matrixValues[Matrix.MTRANS_Y]
        val totalScale = baseScale * currentScale
        val contentW = d.intrinsicWidth * totalScale
        val contentH = d.intrinsicHeight * totalScale
        val vw = width.toFloat()
        val vh = height.toFloat()

        val minX = if (contentW > vw) vw - contentW else (vw - contentW) / 2f
        val maxX = if (contentW > vw) 0f else (vw - contentW) / 2f
        val minY = if (contentH > vh) vh - contentH else (vh - contentH) / 2f
        val maxY = if (contentH > vh) 0f else (vh - contentH) / 2f

        val fixX = tx.coerceIn(minX, maxX) - tx
        val fixY = ty.coerceIn(minY, maxY) - ty
        imgMatrix.postTranslate(fixX, fixY)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouch.set(event.x, event.y)
                isDragging = true
                parent?.requestDisallowInterceptTouchEvent(currentScale > 1.05f)
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && !scaleDetector.isInProgress && currentScale > 1.05f && event.pointerCount == 1) {
                    val dx = event.x - lastTouch.x
                    val dy = event.y - lastTouch.y
                    imgMatrix.postTranslate(dx, dy)
                    constrainMatrix()
                    imageMatrix = imgMatrix
                    lastTouch.set(event.x, event.y)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
        return true
    }
}
