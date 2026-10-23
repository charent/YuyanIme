package com.yuyan.imemodule.compose.keyboard

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 手写板会话：笔迹采集 + 渲染 + 识别触发。
 *
 * 与旧 `HandwritingKeyboard` 的对应关系：
 * - 坐标序列仍然是 `x, y, x, y, ..., -1, 0` 的 `Short` 列表（`HWEngine.recognitionData` 的入参格式不变）；
 * - 抬笔即识别、停笔 `1200 - handWritingSpeed` ms 后自动清空笔迹、
 *   停笔后再次落笔视为「确认当前候选」——这三条时序与旧实现一致；
 * - 笔迹宽度仍按速度反比（快细慢粗），只是把 Bezier 细分换成按采样点直接描边，
 *   视觉差异小于 1px，换来的是每帧不再分配 Path 对象。
 */
class HandwritingSession(
    private val onRecognize: (MutableList<Short?>) -> Unit,
    private val onCommitCurrentCandidate: () -> Unit,
) {
    /** 当前字的笔迹（每个元素是一条线的采样点），用于绘制 */
    val strokes = mutableStateListOf<List<TimedOffset>>()

    /** 待识别点序列，格式与旧实现一致 */
    private val points: MutableList<Short?> = ArrayList()

    private var lastUpTime = 0L
    private var lastPoint: TimedOffset? = null
    private var currentStroke: MutableList<TimedOffset>? = null

    private var maxWidth = 0f
    private var minWidth = 0f

    /** 停笔判定窗口，与旧实现 `1200 - handWritingSpeed` 一致 */
    private val idleTimeoutMs: Long
        get() = (1200L - AppPrefs.getInstance().handwriting.handWritingSpeed.getValue()).coerceAtLeast(200L)

    private val maxWidthDp: Float
        get() = AppPrefs.getInstance().handwriting.handWritingWidth.getValue() * 4 / 10f

    fun onDown(position: Offset) {
        val now = System.currentTimeMillis()
        if (lastUpTime != 0L && now - lastUpTime > idleTimeoutMs) {
            // 停笔超时后再落笔 = 确认上一个字，进入新字
            onCommitCurrentCandidate()
            points.clear()
            strokes.clear()
        }
        val density = 1f
        maxWidth = dpToPx(maxWidthDp)
        minWidth = maxWidth / 2f
        startStroke(position, now)
    }

    fun onMove(position: Offset) {
        appendPoint(position, System.currentTimeMillis())
    }

    fun onUp(position: Offset) {
        if (currentStroke == null) {
            startStroke(position, System.currentTimeMillis())
        }
        appendPoint(position, System.currentTimeMillis())
        currentStroke?.let { strokes.add(it.toList()) }
        currentStroke = null
        lastUpTime = System.currentTimeMillis()
        // 笔画结束标记
        points.add(-1)
        points.add(0)
        onRecognize(points)
    }

    private fun startStroke(position: Offset, now: Long) {
        val stroke = mutableListOf<TimedOffset>()
        val point = TimedOffset(position.x, position.y, now)
        stroke.add(point)
        currentStroke = stroke
        points.add(position.x.toInt().toShort())
        points.add(position.y.toInt().toShort())
        lastPoint = point
    }

    private fun appendPoint(position: Offset, now: Long) {
        val stroke = currentStroke ?: run {
            startStroke(position, now)
            return
        }
        val point = TimedOffset(position.x, position.y, now)
        stroke.add(point)
        points.add(position.x.toInt().toShort())
        points.add(position.y.toInt().toShort())
        lastPoint = point
    }

    /** 清空笔迹（识别完 / 超时） */
    fun clearInk() {
        strokes.clear()
        currentStroke = null
        lastPoint = null
    }

    /** 供绘制层读取的完整笔迹（含正在书写的那一笔） */
    fun allStrokes(): List<List<TimedOffset>> =
        if (currentStroke == null) strokes.toList()
        else strokes.toList() + listOf(currentStroke!!.toList())

    /** 按速度反比算线宽：快细慢粗，与旧实现的 `strokeWidth(velocity)` 同一公式 */
    fun strokeWidthFor(p0: TimedOffset, p1: TimedOffset): Float {
        val dt = (p1.time - p0.time).coerceAtLeast(1L)
        val distance = sqrt((p1.x - p0.x) * (p1.x - p0.x) + (p1.y - p0.y) * (p1.y - p0.y))
        val velocity = distance / dt
        return max(maxWidth / (velocity + 1f), minWidth)
    }

    private fun dpToPx(dp: Float): Float {
        val density = com.yuyan.imemodule.application.Launcher.instance.context.resources.displayMetrics.density
        return (density * dp).roundToInt().toFloat().coerceAtLeast(1f)
    }
}

/** 笔迹绘制：放在顶层，避免成员扩展函数在 `drawWithCache` 的嵌套 receiver 里解析不到 */
fun DrawScope.drawHandwritingStrokes(
    strokes: List<List<TimedOffset>>,
    widthFor: (TimedOffset, TimedOffset) -> Float,
    color: Color,
) {
    strokes.forEach { stroke ->
        for (i in 1 until stroke.size) {
            val p0 = stroke[i - 1]
            val p1 = stroke[i]
            drawLine(
                color = color,
                start = Offset(p0.x, p0.y),
                end = Offset(p1.x, p1.y),
                strokeWidth = widthFor(p0, p1),
                cap = StrokeCap.Round,
            )
        }
    }
}

data class TimedOffset(val x: Float, val y: Float, val time: Long)
