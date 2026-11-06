package com.alwayside.android

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View

/** Touch feedback shared by the overlay and its practice controller. */
internal object BubbleHaptics {
    fun selection(view: View) {
        try {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        } catch (_: RuntimeException) {
            // Optional selection feedback must never interrupt release or cancellation.
        }
    }

    fun longPress(view: View) = perform(
        standardFeedback = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) },
        fallbackAllowed = {
            view.isHapticFeedbackEnabled && view.isAttachedToWindow && view.isShown &&
                touchFeedbackEnabled(view.context)
        },
        fallbackFeedback = { vibrateTouch(view.context) },
    )

    // Injectable callbacks keep failure handling testable without Android views or services.
    internal fun perform(standardFeedback: () -> Boolean, fallbackAllowed: () -> Boolean,
        fallbackFeedback: () -> Unit) {
        try {
            // A false result can also mean settings suppressed feedback, so it alone is
            // never permission to vibrate. A true result must not get a second effect.
            if (!standardFeedback() && fallbackAllowed()) fallbackFeedback()
        } catch (_: RuntimeException) {
            // Missing permission, inaccessible settings and a rejected service request
            // must not interrupt the gesture or prompt another vibration attempt.
        }
    }

    internal fun reachedHold(state: BubbleGestureStateMachine,
        previous: BubbleGestureStateMachine.Phase, now: Long, alreadySent: Boolean): Boolean =
        !alreadySent && previous == BubbleGestureStateMachine.Phase.PRESSING &&
            now >= state.armedAt && (state.phase == BubbleGestureStateMachine.Phase.ARMED ||
            state.phase == BubbleGestureStateMachine.Phase.FINISHED)

    @Suppress("DEPRECATION")
    private fun touchFeedbackEnabled(context: Context): Boolean {
        // Since API 33, USAGE_TOUCH lets the service apply touch intensity and user
        // preferences. Earlier platform haptics treat a missing setting as disabled.
        return Build.VERSION.SDK_INT >= 33 || Settings.System.getInt(
            context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 0) != 0
    }

    @Suppress("DEPRECATION")
    private fun vibrateTouch(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else context.getSystemService(Vibrator::class.java)
        if (vibrator == null || !vibrator.hasVibrator()) return
        // A short platform click has device-specific support and a platform fallback;
        // avoid an arbitrary one-shot pulse with an unscaled fixed duration/amplitude.
        val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_TOUCH).build())
        } else {
            vibrator.vibrate(effect, AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
        }
        // No bypass flags or attentional usage: system settings, interruption policy
        // and background restrictions may still suppress this request.
    }
}
