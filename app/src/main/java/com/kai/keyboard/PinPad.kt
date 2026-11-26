package com.kai.keyboard

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Задаётся в начале каждого экрана: Palette.apply(activity).
 */
object Palette {
    @Volatile var light = false
    private fun c(l: Int, d: Int) = if (light) l else d
    val bg get() = c(Color.rgb(0xF2, 0xF2, 0xF7), Color.BLACK)
    val surface get() = c(Color.WHITE, Color.rgb(0x1C, 0x1C, 0x1E))
    val surface2 get() = c(Color.rgb(0xE5, 0xE5, 0xEA), Color.rgb(0x2C, 0x2C, 0x2E))
    val text get() = c(Color.rgb(0x1C, 0x1C, 0x1E), Color.WHITE)
    val secondary get() = c(Color.rgb(0x6C, 0x6C, 0x70), Color.rgb(0x8E, 0x8E, 0x93))
    val muted get() = c(Color.rgb(0xA6, 0xA6, 0xAE), Color.rgb(0x8E, 0x8E, 0x96))
    @Volatile var accent: Int = Color.rgb(0x0A, 0x84, 0xFF)
    val onAccent get() = Accent.onAccent(accent)
    val danger get() = c(Color.rgb(0xFF, 0x3B, 0x30), Color.rgb(0xFF, 0x45, 0x3A))
    val success get() = c(Color.rgb(0x34, 0xC7, 0x59), Color.rgb(0x30, 0xD1, 0x58))
    val separator get() = c(Color.rgb(0xC6, 0xC6, 0xC8), Color.rgb(0x38, 0x38, 0x3A))
    val ripple get() = c(Color.argb(0x22, 0, 0, 0), Color.argb(0x40, 0xFF, 0xFF, 0xFF))
    val dialog get() = if (light) android.R.style.Theme_DeviceDefault_Light_Dialog_Alert else android.R.style.Theme_DeviceDefault_Dialog_Alert

    fun tint(sw: android.widget.Switch) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, if (light) Color.WHITE else Color.rgb(0xC7, 0xC7, 0xCC)))
        sw.trackTintList = ColorStateList(states, intArrayOf(accent, separator))
    }

    fun tint(bar: android.widget.SeekBar) {
        bar.progressTintList = ColorStateList.valueOf(accent)
        bar.thumbTintList = ColorStateList.valueOf(accent)
        bar.progressBackgroundTintList = ColorStateList.valueOf(separator)
    }

    /** В тон теме клавиатуры: цвета, фон окна, полосы системы и цвет значков на них. */
    fun apply(a: android.app.Activity) {
        val p = Prefs(a)
        SystemTheme.refresh(a)
        val th = Theme.of(p.theme)
        light = th.light
        accent = th.accentFor(p.accent)
        val w = a.window
        w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
        w.statusBarColor = bg
        w.navigationBarColor = bg
        @Suppress("DEPRECATION")
        var f = w.decorView.systemUiVisibility
        val flags = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        f = if (light) f or flags else f and flags.inv()
        @Suppress("DEPRECATION")
        w.decorView.systemUiVisibility = f
    }
}

fun pressable(color: Int, r: Float): RippleDrawable {
    val shape = GradientDrawable().apply {
        setColor(color)
        if (r < 0) this.shape = GradientDrawable.OVAL else cornerRadius = r
    }
    return RippleDrawable(ColorStateList.valueOf(Palette.ripple), shape, null)
}

/** Значки, общие для приложения и клавиатуры. */
object Icons {
    private val rect = RectF()
    private val path = android.graphics.Path()

    /**
     * Отпечаток пальца: вложенные петли-гребни, открытые книзу, с ножками разной длины (как у настоящего),
     * и короткий гребень в середине. r — половина высоты значка.
     */
    fun fingerprint(c: Canvas, cx: Float, cy: Float, r: Float, p: Paint) {
        val top = cy - r * 0.1f // центр дуг
        val legs = arrayOf(0.55f to 0.75f, 0.85f to 0.5f, 0.7f to 0.85f, 0.4f to 0.6f)
        for (i in 0 until 4) {
            val rx = r * (0.24f + 0.22f * i)
            val ry = rx * 1.1f
            rect.set(cx - rx, top - ry, cx + rx, top + ry)
            path.reset()
            val (l, rt) = legs[i]
            // внешний гребень — короче и с разрывом сверху справа, как у отпечатка
            path.moveTo(cx - rx, top + r * l * (1f - i * 0.12f))
            path.lineTo(cx - rx, top)
            path.arcTo(rect, 180f, if (i == 3) 150f else 180f)
            if (i != 3) path.lineTo(cx + rx, top + r * rt * (1f - i * 0.12f))
            c.drawPath(path, p)
        }
        c.drawLine(cx, top - r * 0.08f, cx, top + r * 0.55f, p)
    }
}

/**
 * Значок раздела настроек: белые линии на цветном скруглённом квадрате (как в настройках телефона) —
 * видно в любой теме, без эмодзи.
 */
class SectionIcon(ctx: Context, private val kind: Int, private val color: Int) : View(ctx) {
    companion object {
        const val KEYBOARD = 0; const val BACKSPACE = 1; const val PALETTE = 2; const val FEEDBACK = 3
        const val SHIELD = 4; const val INFO = 5; const val SAFE = 6; const val AI = 7
    }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Color.WHITE
    }
    private val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val r = RectF()
    private val path = android.graphics.Path()

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f
        bg.color = color
        r.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
        c.drawRoundRect(r, s * 0.24f, s * 0.24f, bg)
        p.strokeWidth = s * 0.065f
        val u = s / 24f // сетка 24×24
        fun x(v: Float) = cx + (v - 12) * u
        fun y(v: Float) = cy + (v - 12) * u
        when (kind) {
            KEYBOARD -> {
                r.set(x(4f), y(7f), x(20f), y(17f)); c.drawRoundRect(r, 2 * u, 2 * u, p)
                for (row in 0..1) for (col in 0..3) c.drawCircle(x(7f + col * 3.3f), y(10f + row * 2.6f), 0.75f * u, f)
                c.drawLine(x(9f), y(15.2f), x(15f), y(15.2f), p)
            }
            BACKSPACE -> {
                path.reset()
                path.moveTo(x(9f), y(7f)); path.lineTo(x(20f), y(7f)); path.lineTo(x(20f), y(17f))
                path.lineTo(x(9f), y(17f)); path.lineTo(x(4f), y(12f)); path.close()
                c.drawPath(path, p)
                c.drawLine(x(12f), y(10f), x(16f), y(14f), p); c.drawLine(x(12f), y(14f), x(16f), y(10f), p)
            }
            PALETTE -> {
                c.drawCircle(cx, cy, 7.5f * u, p)
                c.drawCircle(x(9f), y(9.5f), 1.2f * u, f); c.drawCircle(x(13.5f), y(8f), 1.2f * u, f)
                c.drawCircle(x(16f), y(11.5f), 1.2f * u, f)
                c.drawCircle(x(10.5f), y(15f), 1.6f * u, p)
            }
            FEEDBACK -> {
                r.set(x(9f), y(5f), x(15f), y(19f)); c.drawRoundRect(r, 1.6f * u, 1.6f * u, p)
                c.drawLine(x(6f), y(9f), x(6f), y(15f), p); c.drawLine(x(18f), y(9f), x(18f), y(15f), p)
                c.drawLine(x(3.5f), y(10.5f), x(3.5f), y(13.5f), p); c.drawLine(x(20.5f), y(10.5f), x(20.5f), y(13.5f), p)
            }
            SHIELD -> {
                path.reset()
                path.moveTo(x(12f), y(4f)); path.lineTo(x(19f), y(7f)); path.lineTo(x(19f), y(12f))
                path.quadTo(x(18.5f), y(17.5f), x(12f), y(20f)); path.quadTo(x(5.5f), y(17.5f), x(5f), y(12f))
                path.lineTo(x(5f), y(7f)); path.close()
                c.drawPath(path, p)
                c.drawLine(x(9f), y(12f), x(11.2f), y(14.2f), p); c.drawLine(x(11.2f), y(14.2f), x(15.2f), y(10f), p)
            }
            INFO -> {
                c.drawCircle(cx, cy, 8f * u, p)
                c.drawLine(x(12f), y(11f), x(12f), y(16.5f), p)
                c.drawCircle(x(12f), y(8f), 1.1f * u, f)
            }
            SAFE -> {
                r.set(x(6f), y(11f), x(18f), y(20f)); c.drawRoundRect(r, 1.8f * u, 1.8f * u, p)
                r.set(x(8.5f), y(5f), x(15.5f), y(13f)); c.drawArc(r, 180f, 180f, false, p)
                c.drawLine(x(8.5f), y(9f), x(8.5f), y(11f), p); c.drawLine(x(15.5f), y(9f), x(15.5f), y(11f), p)
                c.drawCircle(x(12f), y(15.5f), 1.2f * u, f)
            }
            AI -> {
                path.reset()
                path.moveTo(x(11f), y(5f)); path.quadTo(x(11.6f), y(10.4f), x(17f), y(11f))
                path.quadTo(x(11.6f), y(11.6f), x(11f), y(17f)); path.quadTo(x(10.4f), y(11.6f), x(5f), y(11f))
                path.quadTo(x(10.4f), y(10.4f), x(11f), y(5f)); path.close()
                c.drawPath(path, f)
                path.reset()
                path.moveTo(x(17.5f), y(14f)); path.quadTo(x(17.8f), y(16.7f), x(20.5f), y(17f))
                path.quadTo(x(17.8f), y(17.3f), x(17.5f), y(20f)); path.quadTo(x(17.2f), y(17.3f), x(14.5f), y(17f))
                path.quadTo(x(17.2f), y(16.7f), x(17.5f), y(14f)); path.close()
                c.drawPath(path, f)
            }
        }
    }
}

class Glyph(ctx: Context, private val kind: Int, private val color: Int) : View(ctx) {
    companion object { const val LOCK = 0; const val FINGERPRINT = 1; const val BACKSPACE = 2; const val CHECK = 3; const val SEARCH = 4 }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f
        p.color = color; p.strokeWidth = s * 0.075f
        when (kind) {
            LOCK -> {
                val w = s * 0.56f; val h = s * 0.42f
                c.drawRoundRect(RectF(cx - w / 2, cy - h * 0.15f, cx + w / 2, cy + h * 0.85f), s * 0.08f, s * 0.08f, p)
                val r = w * 0.32f
                c.drawArc(RectF(cx - r, cy - h * 0.15f - r * 1.9f, cx + r, cy - h * 0.15f + r * 0.1f), 180f, 180f, false, p)
                c.drawLine(cx - r, cy - h * 0.15f - r * 0.9f, cx - r, cy - h * 0.15f, p)
                c.drawLine(cx + r, cy - h * 0.15f - r * 0.9f, cx + r, cy - h * 0.15f, p)
                c.drawLine(cx, cy + h * 0.25f, cx, cy + h * 0.45f, p)
            }
            FINGERPRINT -> {
                p.strokeWidth = s * 0.055f
                Icons.fingerprint(c, cx, cy, s * 0.42f, p)
            }
            BACKSPACE -> {
                val w = s * 0.62f; val h = s * 0.42f
                val l = cx - w / 2; val r = cx + w / 2; val t = cy - h / 2; val b = cy + h / 2
                val path = android.graphics.Path().apply {
                    moveTo(l + h * 0.45f, t); lineTo(r, t); lineTo(r, b); lineTo(l + h * 0.45f, b); lineTo(l, cy); close()
                }
                c.drawPath(path, p)
                val x = cx + w * 0.1f; val d = h * 0.2f
                c.drawLine(x - d, cy - d, x + d, cy + d, p); c.drawLine(x - d, cy + d, x + d, cy - d, p)
            }
            CHECK -> {
                c.drawLine(cx - s * 0.2f, cy, cx - s * 0.05f, cy + s * 0.15f, p)
                c.drawLine(cx - s * 0.05f, cy + s * 0.15f, cx + s * 0.22f, cy - s * 0.15f, p)
            }
            SEARCH -> {
                // Лупа: кружок со сдвигом к верхнему левому углу и ручка вправо-вниз.
                val r = s * 0.28f
                val ox = cx - s * 0.06f; val oy = cy - s * 0.06f
                c.drawCircle(ox, oy, r, p)
                val a = r * 0.72f
                c.drawLine(ox + a, oy + a, cx + s * 0.32f, cy + s * 0.32f, p)
            }
        }
    }
}

private class Dots(ctx: Context) : View(ctx) {
    var count = 0; set(v) { field = v; invalidate() }
    var error = false; set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val r = height * 0.22f
        val gap = r * 2.2f
        val total = if (count == 0) 0f else (count - 1) * (2 * r + gap)
        var x = width / 2f - total / 2
        p.color = if (error) Palette.danger else Palette.text
        repeat(count) { c.drawCircle(x, height / 2f, r, p); x += 2 * r + gap }
    }
}

/**
 * Ввод ПИНа своими кнопками (без клавиатуры: ни одна клавиатура его не увидит): заголовок, точки,
 * строка состояния, цифры, ⌫ (долгое — стереть всё) и «готово». ПИН — в изменяемом буфере,
 * затирается после отправки.
 */
class PinPad(
    ctx: Context,
    title: String,
    subtitle: String,
    private val minLen: Int,
    private val onSubmit: (String) -> Unit,
) : LinearLayout(ctx) {
    private val pin = StringBuilder()
    private val dots: Dots
    private val titleView: TextView
    private val subtitleView: TextView
    private val status: TextView
    private val ok: View
    private val keys = ArrayList<View>()
    private var enabledPad = true
    private fun dp(v: Float) = v * resources.displayMetrics.density
    private val maxLen = 12

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(Glyph(ctx, Glyph.LOCK, Palette.text), LayoutParams(dp(44f).toInt(), dp(44f).toInt()))
        titleView = TextView(ctx).apply {
            text = title; textSize = 22f; setTextColor(Palette.text); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setPadding(0, dp(12f).toInt(), 0, 0)
        }
        addView(titleView, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        subtitleView = TextView(ctx).apply {
            text = subtitle; textSize = 14f; setTextColor(Palette.secondary); gravity = Gravity.CENTER
            setPadding(dp(24f).toInt(), dp(6f).toInt(), dp(24f).toInt(), 0)
        }
        addView(subtitleView, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        dots = Dots(ctx).apply { contentDescription = ctx.getString(R.string.sf_pin_none) }
        addView(dots, LayoutParams(MATCH_PARENT, dp(36f).toInt()).apply { topMargin = dp(20f).toInt() })
        status = TextView(ctx).apply {
            textSize = 14f; gravity = Gravity.CENTER; setTextColor(Palette.secondary); minLines = 2
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(dp(24f).toInt(), dp(4f).toInt(), dp(24f).toInt(), dp(8f).toInt())
        }
        addView(status, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val size = dp(76f).toInt()
        val gap = dp(18f).toInt()
        val letters = arrayOf("", "", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ")
        fun digit(d: Int) = LinearLayout(ctx).apply {
            orientation = VERTICAL; gravity = Gravity.CENTER
            background = pressable(Palette.surface, -1f)
            addView(TextView(ctx).apply {
                text = d.toString(); setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 30f)
                setTextColor(Palette.text); gravity = Gravity.CENTER
                includeFontPadding = false
            })
            if (letters[d].isNotEmpty()) addView(TextView(ctx).apply {
                text = letters[d]; setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 10f)
                setTextColor(Palette.secondary); gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                letterSpacing = 0.15f
            })
            contentDescription = d.toString()
            setOnClickListener {
                tap(it)
                if (errorShown) { errorShown = false; status.text = "" } // начали заново — ошибку убираем
                if (pin.length < maxLen) { pin.append(('0' + d)); changed() }
            }
            keys.add(this)
        }
        fun row(vararg v: View) = LinearLayout(ctx).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER
            v.forEachIndexed { i, k ->
                addView(k, LayoutParams(size, size).apply { if (i > 0) leftMargin = gap })
            }
        }
        val back = FrameLayout(ctx).apply {
            background = pressable(Color.TRANSPARENT, -1f)
            addView(Glyph(ctx, Glyph.BACKSPACE, Palette.text), FrameLayout.LayoutParams(dp(32f).toInt(), dp(32f).toInt(), Gravity.CENTER))
            contentDescription = ctx.getString(R.string.sf_erase_digit)
            setOnClickListener { tap(it); if (pin.isNotEmpty()) { pin.setCharAt(pin.length - 1, '0'); pin.setLength(pin.length - 1); changed() } }
            setOnLongClickListener { tap(it); wipe(); changed(); true }
            keys.add(this)
        }
        ok = FrameLayout(ctx).apply {
            background = pressable(Palette.accent, -1f)
            addView(Glyph(ctx, Glyph.CHECK, Palette.onAccent), FrameLayout.LayoutParams(dp(34f).toInt(), dp(34f).toInt(), Gravity.CENTER))
            contentDescription = ctx.getString(R.string.cf_done)
            setOnClickListener { tap(it); submit() }
            keys.add(this)
        }
        val pad = LinearLayout(ctx).apply {
            orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            for (r in 0 until 3) addView(row(digit(r * 3 + 1), digit(r * 3 + 2), digit(r * 3 + 3)),
                LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (r > 0) topMargin = gap / 2 })
            addView(row(back, digit(0), ok), LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = gap / 2 })
        }
        addView(pad, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8f).toInt() })
        changed()
    }

    private fun tap(v: View) { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }

    private var errorShown = false
    private var lastLen = 0

    private fun changed() {
        dots.error = false
        dots.count = pin.length
        dots.contentDescription = if (pin.isEmpty()) context.getString(R.string.sf_pin_none)
            else context.getString(R.string.sf_pin_digits, pin.length)
        val ready = pin.length >= minLen && enabledPad
        ok.alpha = if (ready) 1f else 0.35f
        ok.isEnabled = ready
    }

    private fun submit() {
        if (!enabledPad || pin.length < minLen) return
        val p = pin.toString()
        lastLen = pin.length
        wipe()
        dots.count = 0
        onSubmit(p)
    }

    private fun wipe() {
        for (i in 0 until pin.length) pin.setCharAt(i, '0')
        pin.setLength(0)
    }

    fun setTitle(t: String, sub: String? = null) {
        titleView.text = t
        if (sub != null) subtitleView.text = sub
    }

    fun setStatus(s: String, error: Boolean = false) {
        status.text = s
        status.setTextColor(if (error) Palette.danger else Palette.secondary)
        errorShown = error
        if (error) {
            if (pin.isEmpty() && lastLen > 0) {
                dots.count = lastLen; dots.error = true
                postDelayed({ if (pin.isEmpty()) { dots.error = false; dots.count = 0 } }, 700)
            }
            ObjectAnimator.ofFloat(dots, "translationX", 0f, 24f, -24f, 16f, -16f, 8f, -8f, 0f).setDuration(420).start()
            performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT
                else HapticFeedbackConstants.LONG_PRESS)
        }
    }

    fun setPadEnabled(on: Boolean) {
        enabledPad = on
        for (k in keys) { k.isEnabled = on; if (k !== ok) k.alpha = if (on) 1f else 0.35f }
        changed()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        wipe()
    }
}
