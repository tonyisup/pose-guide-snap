package com.tonyisup.poseguidesnap.calibration

import android.view.View
import androidx.camera.core.ViewPort
import androidx.camera.view.PreviewView

/** Main-thread, one-shot binding gate. A posted runnable alone does not guarantee layout. */
internal class CalibrationPreviewReadiness(
    private val preview: PreviewView,
    private val onReady: (ViewPort, Int) -> Unit,
) : AutoCloseable {
    private var closed = false
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> checkReady() }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = checkReady()
        override fun onViewDetachedFromWindow(view: View) = Unit
    }

    init {
        preview.addOnLayoutChangeListener(layoutListener)
        preview.addOnAttachStateChangeListener(attachListener)
        checkReady()
    }

    private fun checkReady() {
        if (closed || !preview.isAttachedToWindow ||
            preview.width <= 0 || preview.height <= 0) return
        val display = preview.display ?: return
        val viewPort = preview.viewPort ?: return
        close()
        onReady(viewPort, display.rotation)
    }

    override fun close() {
        if (closed) return
        closed = true
        preview.removeOnLayoutChangeListener(layoutListener)
        preview.removeOnAttachStateChangeListener(attachListener)
    }
}
