package com.will.noteharbor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * Grade 3x3 de "desenho" (padrão) para desbloqueio, desenhada em código (sem XML/Compose).
 *
 * Os pontos são numerados de 1 a 9, da esquerda para a direita e de cima para baixo. O usuário
 * desliza o dedo ligando pontos; ao soltar, a sequência é entregue via [onPatternCompleted]
 * (ex.: "1478"). Pontos intermediários cruzados por um salto em linha reta são ligados
 * automaticamente (comportamento clássico do Android).
 */
class PatternLockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        const val MIN_DOTS = 4
        const val MAX_DOTS = 9
    }

    /** Chamado ao soltar o dedo com um padrão de pelo menos [MIN_DOTS] pontos. */
    var onPatternCompleted: ((String) -> Unit)? = null

    /** Cor dos pontos não selecionados. */
    var dotColor: Int = 0x80FFFFFF.toInt()

    /** Cor da linha e dos pontos selecionados. */
    var accentColor: Int = 0xFFFFFFFF.toInt()

    /** Cor exibida temporariamente quando o padrão é inválido/incorreto. */
    var errorColor: Int = 0xFFE5484D.toInt()

    private val selected = ArrayList<Int>(MAX_DOTS)
    private val dotsX = FloatArray(MAX_DOTS)
    private val dotsY = FloatArray(MAX_DOTS)
    private var spacing = 0f
    private var dotRadius = 0f
    private var inError = false
    private var drawing = false
    private var lastX = 0f
    private var lastY = 0f

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val side = min(w, h)
        spacing = side / 3f // 3 pontos igualmente espaçados, como no padrão do Android
        val startX = (w - 2 * spacing) / 2f
        val startY = (h - 2 * spacing) / 2f
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val i = row * 3 + col
                dotsX[i] = startX + col * spacing
                dotsY[i] = startY + row * spacing
            }
        }
        dotRadius = spacing * 0.12f
        linePaint.strokeWidth = dotRadius * 0.35f
        dotRingPaint.strokeWidth = dotRadius * 0.35f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        linePaint.color = if (inError) errorColor else accentColor

        for (i in 0 until selected.size - 1) {
            val a = selected[i]
            val b = selected[i + 1]
            canvas.drawLine(dotsX[a], dotsY[a], dotsX[b], dotsY[b], linePaint)
        }
        if (drawing && selected.isNotEmpty()) {
            val last = selected.last()
            canvas.drawLine(dotsX[last], dotsY[last], lastX, lastY, linePaint)
        }

        for (i in 0 until MAX_DOTS) {
            if (selected.contains(i)) {
                dotFillPaint.color = if (inError) errorColor else accentColor
                canvas.drawCircle(dotsX[i], dotsY[i], dotRadius, dotFillPaint)
            } else {
                // Ponto vazio: anel fino, como no padrão nativo do Android.
                dotRingPaint.color = if (inError) errorColor else dotColor
                canvas.drawCircle(dotsX[i], dotsY[i], dotRadius, dotRingPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                drawing = true
                hitAndAdd(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!drawing) return true
                lastX = event.x
                lastY = event.y
                hitAndAdd(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!drawing) return true
                drawing = false
                invalidate()
                finish()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitAndAdd(x: Float, y: Float) {
        val target = hitDot(x, y) ?: return
        if (selected.contains(target)) return
        val last = selected.lastOrNull()
        if (last != null) {
            intermediate(last, target).forEach { if (!selected.contains(it)) selected.add(it) }
        }
        selected.add(target)
        invalidate()
    }

    private fun hitDot(x: Float, y: Float): Int? {
        val maxDist = spacing * 0.5f
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in 0 until MAX_DOTS) {
            val dx = x - dotsX[i]
            val dy = y - dotsY[i]
            val dist = dx * dx + dy * dy
            if (dist <= maxDist * maxDist && dist < bestDist) {
                best = i
                bestDist = dist
            }
        }
        return if (best >= 0) best else null
    }

    /** Índices entre `a` e `b` quando há um ponto no meio do salto em linha reta. */
    private fun intermediate(a: Int, b: Int): List<Int> {
        val ax = a % 3
        val ay = a / 3
        val bx = b % 3
        val by = b / 3
        val step = maxOf(abs(bx - ax), abs(by - ay))
        if (step <= 1) return emptyList()
        val dx = (bx - ax).coerceIn(-1, 1)
        val dy = (by - ay).coerceIn(-1, 1)
        val result = ArrayList<Int>(1)
        var cx = ax + dx
        var cy = ay + dy
        while (cx != bx || cy != by) {
            result.add(cy * 3 + cx)
            cx += dx
            cy += dy
        }
        return result
    }

    private fun finish() {
        val pattern = selected.joinToString("") { (it + 1).toString() }
        if (pattern.length >= MIN_DOTS) {
            onPatternCompleted?.invoke(pattern)
        } else {
            flashError()
        }
    }

    /** Pisca em vermelho e reinicia (usado quando o padrão digitado está errado). */
    fun showError() {
        flashError()
    }

    fun reset() {
        selected.clear()
        inError = false
        drawing = false
        invalidate()
    }

    private fun flashError() {
        inError = true
        invalidate()
        postDelayed({ reset() }, 450)
    }
}
