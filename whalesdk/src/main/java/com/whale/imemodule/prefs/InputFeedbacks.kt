package com.whale.imemodule.prefs

import android.media.AudioManager
import android.os.VibrationEffect
import android.view.HapticFeedbackConstants
import android.view.View
import com.whale.imemodule.application.Launcher
import com.whale.imemodule.utils.audioManager
import com.whale.imemodule.utils.vibrator

object InputFeedbacks {

    private val soundOnKeyPress by AppPrefs.getInstance().internal.soundOnKeyPress
    private val vibrationAmplitude by AppPrefs.getInstance().internal.vibrationAmplitude

    private val vibrator = Launcher.instance.context.vibrator
    private val hasAmplitudeControl = vibrator.hasAmplitudeControl()
    private val audioManager = Launcher.instance.context.audioManager

    fun hapticFeedback(view: View) {
        val duration = when (vibrationAmplitude) {
            0 -> {
                @Suppress("DEPRECATION")
                val flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, flags)
                0L
            }
            1 -> 0L
            2 -> 5L
            3 -> 30L
            else -> 50L
        }
        if (duration != 0L) {
            if (hasAmplitudeControl) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        }
    }

    /**
     * 无 View 的震动入口（Compose 侧使用）。
     *
     * 语义对齐 [hapticFeedback]：0 = 交给系统默认（这里退化成 10ms 的极短震动，
     * 因为 Compose 的手势回调里没有 View 可以 `performHapticFeedback`），1 = 关闭。
     */
    fun hapticFeedback() {
        val duration = when (vibrationAmplitude) {
            0 -> 10L
            1 -> return
            2 -> 5L
            3 -> 30L
            else -> 50L
        }
        if (hasAmplitudeControl) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }

    enum class SoundEffect {
        Standard, SpaceBar, Delete, Return
    }

    fun soundEffect(effect: SoundEffect) {
        val fx = when (effect) {
            SoundEffect.Standard -> AudioManager.FX_KEYPRESS_STANDARD
            SoundEffect.SpaceBar -> AudioManager.FX_KEYPRESS_SPACEBAR
            SoundEffect.Delete -> AudioManager.FX_KEYPRESS_DELETE
            SoundEffect.Return -> AudioManager.FX_KEYPRESS_RETURN
        }
        if (soundOnKeyPress < 5) {
            audioManager.playSoundEffect(fx, -1f)
        } else if (soundOnKeyPress < 10) {
            return
        } else {
            audioManager.playSoundEffect(fx, (soundOnKeyPress - 10) / 30f)
        }
    }

}