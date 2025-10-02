package com.example.maxscraper.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Visual cue: briefly flash a view's background green.
 * Safe on any thread — always switches to main before running the animator.
 */
object FlashPulse {

    fun pulse(view: View) {
        // Always run on main thread to satisfy ValueAnimator
        if (Looper.myLooper() != Looper.getMainLooper()) {
            view.post { doPulse(view) }
        } else {
            doPulse(view)
        }
    }

    private fun doPulse(view: View) {
        val ctx = view.context ?: return
        val green = ContextCompat.getColor(ctx, android.R.color.holo_green_light)
        val original = (view.background as? ColorDrawable)?.color ?: Color.TRANSPARENT
        val evaluator = ArgbEvaluator()

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 400L
            repeatCount = 3
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { va ->
                val f = (va.animatedValue as? Float) ?: 0f
                val color = evaluator.evaluate(f, original, green) as Int
                view.setBackgroundColor(color)
            }
        }

        // If view is not attached yet, wait until it is
        if (!view.isAttachedToWindow) {
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                    anim.start()
                }
                override fun onViewDetachedFromWindow(v: View) {
                    // no-op
                }
            })
        } else {
            anim.start()
        }
    }
}
