package com.tonyisup.poseguidesnap.calibration

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.view.PreviewView
import com.tonyisup.poseguidesnap.R
import com.tonyisup.poseguidesnap.camera.NormalizedPoint
import com.tonyisup.poseguidesnap.domain.model.PoseImageSize
import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Instrumentation-only UI; a single transient outline is replaced on every frame. */
internal class CalibrationGuideScreen(context: Context) {
    private val guide = CalibrationAlignmentGuide()
    val preview = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }
    val status = TextView(context).apply {
        text = "Get ready. The flash starts your settling time."
        textSize = 20f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.DKGRAY)
        setPadding(24, 12, 24, 12)
        minLines = 4
        maxLines = 4
    }
    private val outline = AlignmentOutline(context, guide)
    val feedback: AlignmentFeedback get() = outline.feedback
    private var warmupFinished = false
    private val expireFeedback = Runnable {
        outline.feedback = AlignmentFeedback(AlignmentCue.WAITING)
        status.text = AlignmentCue.WAITING.text
    }
    val previewFrame: FrameLayout = object : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
            val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
            if (availableWidth == 0 || availableHeight == 0) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }
            val fitted = guide.fittedPreviewSize(availableWidth, availableHeight)
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(fitted.width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(fitted.height, MeasureSpec.EXACTLY),
            )
        }
    }.apply {
        addView(preview, FrameLayout.LayoutParams(-1, -1))
        addView(outline, FrameLayout.LayoutParams(-1, -1))
    }
    val root = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        keepScreenOn = true
        fitsSystemWindows = true
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(ImageView(context).apply {
                setImageResource(R.drawable.meditation_pose)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "Target meditation pose"
            }, LinearLayout.LayoutParams(-1, context.resources.displayMetrics.heightPixels / 5))
            addView(TextView(context).apply {
                text = "White dashed outline: target    •    Blue outline: you"
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(12, 8, 12, 8)
            }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(FrameLayout(context).apply {
                addView(previewFrame, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(status, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, FrameLayout.LayoutParams(-1, -1))
    }

    fun update(observation: PoseObservation, secondsRemaining: Int): Boolean {
        if (warmupFinished) return false
        root.removeCallbacks(expireFeedback)
        val feedback = if (preview.width > 0 && preview.height > 0 &&
            guide.compatibleAspect(observation.imageSize, PoseImageSize(preview.width, preview.height))) {
            guide.evaluate(observation)
        } else {
            AlignmentFeedback(AlignmentCue.GEOMETRY_MISMATCH)
        }
        outline.feedback = feedback
        status.text = "Settle in · ${secondsRemaining.coerceAtLeast(0)}s\n${feedback.cue.text}"
        root.postDelayed(expireFeedback, 750L)
        return feedback.cue != AlignmentCue.GEOMETRY_MISMATCH
    }

    fun finishWarmup() {
        warmupFinished = true
        root.removeCallbacks(expireFeedback)
        outline.feedback = AlignmentFeedback(AlignmentCue.WAITING)
        // The collector supplies the hold-still instruction; no adjustment cues during measurement.
    }

    fun close() {
        finishWarmup()
        root.keepScreenOn = false
    }
}

private class AlignmentOutline(context: Context, private val guide: CalibrationAlignmentGuide) : View(context) {
    var feedback = AlignmentFeedback(AlignmentCue.WAITING)
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val viewport = PoseImageSize(width, height)
        val imageSize = feedback.imageSize ?: guide.referenceSize
        fun point(x: Double, y: Double) = guide.previewPoint(NormalizedPoint(x, y), imageSize, viewport)
        fun box(bounds: AlignmentBounds, color: Int, dashed: Boolean) {
            paint.color = color
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f * density
            paint.pathEffect = if (dashed) DashPathEffect(floatArrayOf(8f * density, 5f * density), 0f) else null
            val topLeft = point(bounds.left, bounds.top)
            val bottomRight = point(bounds.right, bounds.bottom)
            canvas.drawRect(topLeft.x.toFloat(), topLeft.y.toFloat(), bottomRight.x.toFloat(), bottomRight.y.toFloat(), paint)
            paint.pathEffect = null
        }
        box(guide.targetBounds, Color.WHITE, true)
        val target = point(guide.targetBounds.center.x, guide.targetBounds.center.y)
        val crossSize = 8f * density
        canvas.drawLine(target.x.toFloat() - crossSize, target.y.toFloat(), target.x.toFloat() + crossSize, target.y.toFloat(), paint)
        canvas.drawLine(target.x.toFloat(), target.y.toFloat() - crossSize, target.x.toFloat(), target.y.toFloat() + crossSize, paint)
        val observed = feedback.observedBounds ?: return
        box(observed, Color.CYAN, false)
        val live = point(observed.center.x, observed.center.y)
        canvas.drawCircle(live.x.toFloat(), live.y.toFloat(), 5f * density, paint)
        if (feedback.cue != AlignmentCue.CENTER) return
        paint.color = Color.YELLOW
        canvas.drawLine(live.x.toFloat(), live.y.toFloat(), target.x.toFloat(), target.y.toFloat(), paint)
        val angle = atan2(target.y - live.y, target.x - live.x)
        for (offset in listOf(-0.5, 0.5)) {
            canvas.drawLine(
                target.x.toFloat(), target.y.toFloat(),
                (target.x - cos(angle + offset) * 14 * density).toFloat(),
                (target.y - sin(angle + offset) * 14 * density).toFloat(), paint,
            )
        }
    }
}
