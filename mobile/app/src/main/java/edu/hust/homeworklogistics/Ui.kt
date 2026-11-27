package edu.hust.homeworklogistics

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object Palette {
    val accent = Color.parseColor("#4F46E5")
    val accentDark = Color.parseColor("#3730A3")
    val accentSoft = Color.parseColor("#EEF2FF")
    val bg = Color.parseColor("#F4F5FB")
    val card = Color.WHITE
    val ink = Color.parseColor("#111827")
    val body = Color.parseColor("#374151")
    val muted = Color.parseColor("#6B7280")
    val faint = Color.parseColor("#9CA3AF")
    val line = Color.parseColor("#E5E7EB")
    val field = Color.parseColor("#F9FAFB")
    val error = Color.parseColor("#B91C1C")
    /** 暖色：用于「未加入」这类需要提醒但不危险的状态 */
    val warn = Color.parseColor("#B45309")
    val warnSoft = Color.parseColor("#FEF3C7")
    val errorSoft = Color.parseColor("#FEF2F2")
    val ok = Color.parseColor("#047857")
}

/** Small view factory so every screen shares the same look. */
class Ui(private val activity: Activity) {
    fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 1) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
    }

    fun circle(color: Int, strokeColor: Int? = null, strokeDp: Int = 2) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
    }

    fun ripple(content: Drawable, radiusDp: Int, rippleColor: Int = Color.parseColor("#22000000")) =
        RippleDrawable(ColorStateList.valueOf(rippleColor), content, rounded(Color.BLACK, radiusDp))

    fun lp(
        width: Int = MATCH_PARENT,
        height: Int = WRAP_CONTENT,
        top: Int = 0,
        bottom: Int = 0,
        start: Int = 0,
        end: Int = 0,
        weight: Float = 0f,
    ) = LinearLayout.LayoutParams(width, height, weight).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
        marginStart = dp(start)
        marginEnd = dp(end)
    }

    fun vertical(vararg children: View) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        children.forEach { addView(it) }
    }

    fun horizontal(vararg children: View) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        children.forEach { addView(it) }
    }

    fun text(value: String, size: Float = 15f, color: Int = Palette.body, bold: Boolean = false) =
        TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setLineSpacing(0f, 1.15f)
        }

    fun section(title: String) = text(title, 14f, Palette.muted, bold = true).apply {
        letterSpacing = 0.04f
        setPadding(dp(4), dp(20), dp(4), dp(8))
    }

    fun card(paddingDp: Int = 16) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Palette.card, 16)
        elevation = dp(2).toFloat()
        setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
    }

    fun clickable(view: View, radiusDp: Int, base: Drawable, onClick: () -> Unit) {
        view.background = ripple(base, radiusDp)
        view.isClickable = true
        view.isFocusable = true
        view.setOnClickListener { onClick() }
    }

    fun field(label: String, value: String = "", password: Boolean = false, multiline: Boolean = false, uri: Boolean = false) =
        EditText(activity).apply {
            hint = label
            setText(value)
            textSize = 15f
            setTextColor(Palette.ink)
            setHintTextColor(Palette.faint)
            background = rounded(Palette.field, 12, Palette.line)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                uri -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (multiline) {
                minLines = 3
                gravity = Gravity.TOP or Gravity.START
            } else {
                isSingleLine = true
            }
            setOnFocusChangeListener { _, focused ->
                background = if (focused) rounded(Color.WHITE, 12, Palette.accent, 2) else rounded(Palette.field, 12, Palette.line)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
        }

    fun label(value: String) = text(value, 13f, Palette.muted, bold = true).apply {
        setPadding(dp(2), 0, 0, dp(6))
    }

    fun primaryButton(label: String, onClick: () -> Unit) = text(label, 16f, Color.WHITE, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(13), dp(16), dp(13))
        clickable(this, 12, rounded(Palette.accent, 12), onClick)
        elevation = dp(1).toFloat()
    }

    fun secondaryButton(label: String, color: Int = Palette.accent, onClick: () -> Unit) =
        text(label, 15f, color, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(12))
            clickable(this, 12, rounded(Color.WHITE, 12, color), onClick)
        }

    fun linkButton(label: String, color: Int = Palette.accent, onClick: () -> Unit) =
        text(label, 14f, color, bold = true).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            clickable(this, 8, rounded(Color.TRANSPARENT, 8), onClick)
        }

    fun chip(label: String, fg: Int, bg: Int) = text(label, 12f, fg, bold = true).apply {
        background = rounded(bg, 999)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    fun statusChip(status: String, mode: String = "full"): TextView {
        val (fg, bg) = Domain.statusColors(status)
        return chip(Domain.label(status, mode), fg, bg)
    }

    fun spacer() = View(activity).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }

    /** Round icon button (the "+" and "↑" of a composer). Stroke keeps the circle visible on white. */
    fun roundAction(
        glyph: String,
        glyphColor: Int,
        bg: Int,
        sizeDp: Int = 44,
        stroke: Int? = null,
        onClick: () -> Unit,
    ) =
        text(glyph, sizeDp / 1.9f, glyphColor, bold = true).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            clickable(this, sizeDp / 2, circle(bg, stroke), onClick)
        }

    /**
     * 一个圆角消息框，把 "+" 和 "↑" 嵌在框内左右两端（WorkBuddy / 微信那种），
     * 输入框本身透明，多行时两个圆贴底。
     */
    fun composer(hint: String, onPickImage: () -> Unit, onSend: (String) -> Unit): LinearLayout {
        val field = EditText(activity).apply {
            this.hint = hint
            textSize = 15f
            setTextColor(Palette.ink)
            setHintTextColor(Palette.faint)
            background = null
            setPadding(dp(8), dp(8), dp(8), dp(8))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            minLines = 1
        }
        val plus = roundAction("+", Palette.body, Palette.card, 32, Palette.line, onPickImage)
        val send = roundAction("↑", Color.WHITE, Palette.accent, 32) {
            val value = field.text.toString().trim()
            if (value.isNotEmpty()) {
                field.setText("")
                onSend(value)
            }
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = rounded(Palette.field, 24, Palette.line)
        }
        box.addView(plus, LinearLayout.LayoutParams(dp(32), dp(32)))
        box.addView(field, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            marginStart = dp(6)
            marginEnd = dp(6)
        })
        box.addView(send, LinearLayout.LayoutParams(dp(32), dp(32)))

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.WHITE)
            addView(box, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            tag = field
        }
    }

    /** Plain title row. Pages that need a way back use this; home and profile do not. */
    fun topBar(
        title: String,
        subtitle: String? = null,
        onBack: (() -> Unit)? = null,
        actions: List<Pair<String, () -> Unit>> = emptyList(),
    ): LinearLayout {
        val bar = horizontal().apply {
            setBackgroundColor(Color.WHITE)
            setPadding(dp(if (onBack != null) 4 else 16), dp(10), dp(12), dp(10))
        }
        if (onBack != null) {
            bar.addView(text("‹", 28f, Palette.ink).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(8), dp(2))
                clickable(this, 20, rounded(Color.TRANSPARENT, 20), onBack)
            })
        }
        val titles = vertical(text(title, 17f, Palette.ink, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (!subtitle.isNullOrBlank()) {
            titles.addView(text(subtitle, 12f, Palette.muted).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        bar.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        actions.forEach { (label, action) ->
            bar.addView(text(label, 14f, Palette.accent, bold = true).apply {
                setPadding(dp(12), dp(6), dp(12), dp(6))
                clickable(this, 999, rounded(Color.TRANSPARENT, 999), action)
            }, lp(WRAP_CONTENT, WRAP_CONTENT, start = 8))
        }
        val wrap = vertical(bar, View(activity).apply { setBackgroundColor(Palette.line) })
        wrap.getChildAt(1).layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1))
        wrap.setBackgroundColor(Color.WHITE)
        return wrap
    }
}
