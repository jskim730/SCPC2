package com.scpc.deliveryagent.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Small view helpers.
 *
 * The screens are built in code rather than in layout XML so that every control
 * carries an explicit content description and the whole surface stays readable at
 * 360dp with one primary action per screen.
 */
object Ui {

    // One palette, so a card, a bubble and a sheet cannot drift apart.
    const val BRAND = "#173F35"
    const val BRAND_TINT = "#E7EFEB"
    const val CHAT_BG = "#DCE5E1"
    const val INK = "#141A18"
    const val INK_2 = "#5B6763"
    const val LINE = "#D4DBD8"
    const val ACCENT = "#1F6B55"
    const val NEEDS = "#B4541F"

    private fun color(value: String) = Color.parseColor(value)

    fun density(context: Context): Float = context.resources.displayMetrics.density

    fun dp(context: Context, value: Int): Int = (value * density(context)).toInt()

    fun sp(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.scaledDensity

    /** Width a chip row may use inside a card in the thread. */
    fun cardContentWidth(context: Context): Int =
        context.resources.displayMetrics.widthPixels - dp(context, 56)

    private fun rounded(context: Context, fill: String, radius: Int, stroke: String? = null) =
        GradientDrawable().apply {
            setColor(color(fill))
            cornerRadius = dp(context, radius).toFloat()
            if (stroke != null) setStroke(dp(context, 1), color(stroke))
        }

    /** The screen's own header: where the order stands, and the way to everything else. */
    fun appBar(
        context: Context,
        title: String,
        subtitle: String,
        menuLabel: String,
        onMenu: () -> Unit,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(color(BRAND))
        setPadding(dp(context, 16), dp(context, 8), dp(context, 6), dp(context, 8))
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    TextView(context).apply {
                        text = title
                        textSize = 16f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Color.WHITE)
                    },
                )
                addView(
                    TextView(context).apply {
                        text = subtitle
                        textSize = 11f
                        setTextColor(color("#B9CCC4"))
                    },
                )
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            Button(context).apply {
                text = "⋮"
                contentDescription = menuLabel
                textSize = 18f
                setTextColor(Color.WHITE)
                minWidth = dp(context, 48)
                minHeight = dp(context, 48)
                setPadding(0, 0, 0, 0)
                background = rounded(context, BRAND, 24)
                setOnClickListener { onMenu() }
            },
        )
    }

    /** One tappable answer. [sub] carries an amount when the option costs extra. */
    data class Chip(
        val label: String,
        val sub: String = "",
        val selected: Boolean = false,
        val onTap: () -> Unit,
    )

    /**
     * Chips packed into as few rows as fit.
     *
     * Android has no wrapping row, and a fixed column count breaks the moment a
     * label is long, so the rows are packed from measured text width.
     */
    fun chipFlow(context: Context, availableWidth: Int, chips: List<Chip>): LinearLayout {
        val gap = dp(context, 7)
        val paint = Paint().apply { textSize = sp(context, 13.5f) }
        val subPaint = Paint().apply { textSize = sp(context, 12f) }
        val fixed = dp(context, 30)
        fun widthOf(chip: Chip): Int {
            val text = paint.measureText(chip.label).toInt()
            val extra = if (chip.sub.isEmpty()) 0 else subPaint.measureText(chip.sub).toInt() + dp(context, 5)
            return minOf(availableWidth, text + extra + fixed)
        }

        val rows = mutableListOf<MutableList<Chip>>()
        var used = 0
        chips.forEach { chip ->
            val width = widthOf(chip)
            if (rows.isEmpty() || used + gap + width > availableWidth) {
                rows += mutableListOf(chip)
                used = width
            } else {
                rows.last() += chip
                used += gap + width
            }
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            rows.forEach { row ->
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 0, 0, gap)
                        row.forEachIndexed { index, chip ->
                            addView(
                                chipView(context, chip),
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                ).apply { if (index < row.size - 1) marginEnd = gap },
                            )
                        }
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        }
    }

    private fun chipView(context: Context, chip: Chip): Button = Button(context).apply {
        text = if (chip.sub.isEmpty()) chip.label else "${chip.label}  ${chip.sub}"
        // Selection is spelled out as well as drawn, so it never rests on colour.
        contentDescription = if (chip.selected) "${chip.label} 선택됨" else chip.label
        textSize = 13.5f
        isAllCaps = false
        minHeight = dp(context, 44)
        minWidth = 0
        setPadding(dp(context, 14), 0, dp(context, 14), 0)
        setTextColor(color(INK))
        if (chip.selected) {
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(context, BRAND_TINT, 22, BRAND)
        } else {
            background = rounded(context, "#FFFFFF", 22, LINE)
        }
        setOnClickListener { chip.onTap() }
    }

    /**
     * A question the agent asks, with its answers attached.
     *
     * [footnote] is where a consequence goes — what applying this value will and
     * will not do later — because that belongs next to the choice, not in a
     * separate line the user reads afterwards.
     */
    fun agentCard(
        context: Context,
        question: String,
        hint: String,
        chips: List<Chip>,
        footnote: String = "",
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 13), dp(context, 13), dp(context, 13), dp(context, 6))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            val r = dp(context, 15).toFloat()
            val tail = dp(context, 3).toFloat()
            cornerRadii = floatArrayOf(tail, tail, r, r, r, r, r, r)
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(context, 9) }
        addView(
            TextView(context).apply {
                text = question
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(INK))
            },
        )
        if (hint.isNotEmpty()) {
            addView(
                TextView(context).apply {
                    text = hint
                    textSize = 12f
                    setTextColor(color(INK_2))
                    setPadding(0, dp(context, 3), 0, dp(context, 10))
                },
            )
        }
        if (chips.isNotEmpty()) addView(chipFlow(context, cardContentWidth(context), chips))
        if (footnote.isNotEmpty()) {
            addView(
                TextView(context).apply {
                    text = footnote
                    textSize = 11.5f
                    setTextColor(color(ACCENT))
                    setPadding(0, dp(context, 3), 0, dp(context, 4))
                },
            )
        }
    }

    /** The always-present summary of the order, above the message field. */
    fun orderBar(
        context: Context,
        count: Int,
        summary: String,
        onOpen: () -> Unit,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.WHITE)
        setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
        contentDescription = "주문서 열기 $summary"
        addView(
            TextView(context).apply {
                text = count.toString()
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = rounded(context, BRAND, 11)
                setPadding(dp(context, 9), dp(context, 3), dp(context, 9), dp(context, 3))
            },
        )
        addView(
            TextView(context).apply {
                text = summary
                textSize = 14f
                setTextColor(color(INK))
                setPadding(dp(context, 10), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            TextView(context).apply {
                text = "▲"
                textSize = 12f
                setTextColor(color(INK_2))
            },
        )
        setOnClickListener { onOpen() }
    }

    /** One row of the draft: what it is, what it says, where it came from. */
    fun draftRow(
        context: Context,
        label: String,
        value: String,
        source: String,
        needsUser: Boolean,
        onChange: (() -> Unit)?,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(context, 6), 0, dp(context, 6))
        addView(
            TextView(context).apply {
                text = label
                textSize = 13f
                setTextColor(color(INK_2))
            },
            LinearLayout.LayoutParams(dp(context, 70), ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    TextView(context).apply {
                        text = value
                        textSize = 13.5f
                        setTextColor(color(INK))
                    },
                )
                if (source.isNotEmpty()) {
                    addView(
                        TextView(context).apply {
                            text = source
                            textSize = 11f
                            setTextColor(color(if (needsUser) NEEDS else ACCENT))
                        },
                    )
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (onChange != null) {
            addView(
                Button(context).apply {
                    text = "변경"
                    contentDescription = "$label 변경"
                    textSize = 12f
                    isAllCaps = false
                    minWidth = dp(context, 56)
                    minHeight = dp(context, 44)
                    setPadding(dp(context, 10), 0, dp(context, 10), 0)
                    setTextColor(color(INK))
                    background = rounded(context, "#FFFFFF", 15, LINE)
                    setOnClickListener { onChange() }
                },
            )
        }
    }

    /** The heading of one order line inside the draft. */
    fun lineHeader(
        context: Context,
        name: String,
        price: String,
        onRemove: (() -> Unit)?,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(context, 14), 0, dp(context, 2))
        addView(
            TextView(context).apply {
                text = name
                textSize = 14.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(INK))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (price.isNotEmpty()) {
            addView(
                TextView(context).apply {
                    text = price
                    textSize = 13.5f
                    setTextColor(color(INK))
                },
            )
        }
        if (onRemove != null) {
            addView(
                Button(context).apply {
                    text = "빼기"
                    contentDescription = "$name 빼기"
                    textSize = 12f
                    isAllCaps = false
                    minWidth = dp(context, 48)
                    minHeight = dp(context, 44)
                    setPadding(dp(context, 8), 0, dp(context, 8), 0)
                    setTextColor(color(INK_2))
                    background = rounded(context, "#FFFFFF", 15, LINE)
                    setOnClickListener { onRemove() }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(context, 8) },
            )
        }
    }

    fun totalRow(context: Context, label: String, amount: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 14), 0, dp(context, 2))
            addView(
                TextView(context).apply {
                    text = label
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(color(INK))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                TextView(context).apply {
                    text = amount
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(color(INK))
                },
            )
        }

    /** One entry of the overflow menu. */
    fun menuItem(
        context: Context,
        title: String,
        detail: String,
        onTap: () -> Unit,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 18), dp(context, 13), dp(context, 18), dp(context, 13))
        contentDescription = "$title. $detail"
        addView(
            TextView(context).apply {
                text = title
                textSize = 14.5f
                setTextColor(color(INK))
            },
        )
        if (detail.isNotEmpty()) {
            addView(
                TextView(context).apply {
                    text = detail
                    textSize = 11.5f
                    setTextColor(color(INK_2))
                    setPadding(0, dp(context, 2), 0, 0)
                },
            )
        }
        setOnClickListener { onTap() }
    }

    fun menuGroup(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 11.5f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color(INK_2))
        setPadding(dp(context, 18), dp(context, 14), dp(context, 18), dp(context, 4))
    }

    /**
     * A panel that covers the thread until it is answered or dismissed.
     *
     * The scrim takes the tap so nothing behind it can be pressed by accident
     * while the panel is up.
     */
    fun sheet(
        context: Context,
        title: String,
        body: View,
        footer: View?,
        onDismiss: () -> Unit,
    ): FrameLayout = FrameLayout(context).apply {
        addView(
            View(context).apply {
                setBackgroundColor(Color.parseColor("#52000000"))
                contentDescription = "$title 닫기"
                setOnClickListener { onDismiss() }
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    val r = dp(context, 16).toFloat()
                    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                }
                addView(
                    View(context).apply {
                        background = rounded(context, "#D8DEDC", 2)
                    },
                    LinearLayout.LayoutParams(dp(context, 36), dp(context, 4)).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        topMargin = dp(context, 8)
                        bottomMargin = dp(context, 8)
                    },
                )
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(context, 16), 0, dp(context, 8), dp(context, 8))
                        addView(
                            TextView(context).apply {
                                this.text = title
                                textSize = 16f
                                setTypeface(typeface, Typeface.BOLD)
                                setTextColor(color(INK))
                            },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                        )
                        addView(
                            Button(context).apply {
                                this.text = "닫기"
                                contentDescription = "$title 닫기"
                                textSize = 12f
                                isAllCaps = false
                                minHeight = dp(context, 44)
                                minWidth = dp(context, 56)
                                setTextColor(color(INK_2))
                                background = rounded(context, "#FFFFFF", 15, LINE)
                                setOnClickListener { onDismiss() }
                            },
                        )
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                addView(
                    ScrollView(context).apply {
                        isFillViewport = false
                        addView(
                            body,
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ),
                        )
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    ),
                )
                if (footer != null) {
                    addView(
                        footer,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                }
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.BOTTOM },
        )
    }

    /** One proposed menu inside the agent's recommendation card. */
    fun candidateRow(
        context: Context,
        name: String,
        meta: String,
        reasons: String,
        actionLabel: String,
        onTap: (() -> Unit)?,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(context, 10), 0, dp(context, 10))
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    TextView(context).apply {
                        text = name
                        textSize = 14.5f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(color(INK))
                    },
                )
                addView(
                    TextView(context).apply {
                        text = meta
                        textSize = 12f
                        setTextColor(color(INK_2))
                        setPadding(0, dp(context, 2), 0, 0)
                    },
                )
                if (reasons.isNotEmpty()) {
                    addView(
                        TextView(context).apply {
                            text = reasons
                            textSize = 11f
                            setTextColor(color(ACCENT))
                            setPadding(0, dp(context, 3), 0, 0)
                        },
                    )
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (onTap != null) {
            addView(
                Button(context).apply {
                    text = actionLabel
                    contentDescription = "$name $actionLabel"
                    textSize = 12.5f
                    isAllCaps = false
                    setTypeface(typeface, Typeface.BOLD)
                    minWidth = dp(context, 56)
                    minHeight = dp(context, 44)
                    setPadding(dp(context, 12), 0, dp(context, 12), 0)
                    setTextColor(Color.WHITE)
                    background = rounded(context, BRAND, 17)
                    setOnClickListener { onTap() }
                },
            )
        }
    }

    /** The one action a sheet exists to make possible. */
    fun primaryAction(
        context: Context,
        text: String,
        enabled: Boolean,
        onTap: () -> Unit,
    ): Button = Button(context).apply {
        this.text = text
        contentDescription = text
        textSize = 15f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        minHeight = dp(context, 52)
        isEnabled = enabled
        setTextColor(if (enabled) Color.WHITE else color("#98A19E"))
        background = rounded(context, if (enabled) BRAND else "#E7EAE9", 12)
        setOnClickListener { if (enabled) onTap() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = dp(context, 16)
            rightMargin = dp(context, 16)
            topMargin = dp(context, 10)
            bottomMargin = dp(context, 14)
        }
    }

    fun column(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 32))
    }

    fun scroller(context: Context, content: View): ScrollView = ScrollView(context).apply {
        isFillViewport = true
        addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    fun title(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(context, 8), 0, dp(context, 8))
    }

    fun section(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(context, 16), 0, dp(context, 4))
    }

    fun body(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        setPadding(0, dp(context, 2), 0, dp(context, 2))
    }

    fun mono(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        typeface = Typeface.MONOSPACE
        setPadding(0, dp(context, 2), 0, dp(context, 2))
    }

    /**
     * A row that never relies on colour alone: the status word is always spelled
     * out next to the value.
     */
    fun row(context: Context, label: String, value: String, status: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 6), 0, dp(context, 6))
            addView(
                TextView(context).apply {
                    text = label
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f),
            )
            addView(
                TextView(context).apply {
                    text = value
                    textSize = 14f
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f),
            )
            addView(
                TextView(context).apply {
                    text = status
                    textSize = 13f
                    gravity = Gravity.END
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 4f),
            )
        }

    fun button(
        context: Context,
        text: String,
        description: String = text,
        primary: Boolean = false,
        onClick: () -> Unit,
    ): Button = Button(context).apply {
        this.text = text
        contentDescription = description
        isFocusable = true
        isFocusableInTouchMode = true
        minHeight = dp(context, 48)
        if (primary) {
            setTypeface(typeface, Typeface.BOLD)
            textSize = 16f
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(context, 6) }
    }


    /** Single-line text entry with an explicit send control next to it. */
    fun inputRow(
        context: Context,
        hint: String,
        sendLabel: String,
        onSend: (String) -> Unit,
    ): LinearLayout {
        val field = EditText(context).apply {
            this.hint = hint
            contentDescription = hint
            textSize = 15f
            minHeight = dp(context, 48)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT
        }
        fun send() {
            val text = field.text.toString().trim()
            if (text.isEmpty()) return
            field.setText("")
            onSend(text)
        }
        field.setOnEditorActionListener { _, _, _ ->
            send()
            true
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                field,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                Button(context).apply {
                    text = sendLabel
                    contentDescription = sendLabel
                    minHeight = dp(context, 48)
                    setOnClickListener { send() }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    /**
     * One turn of the conversation, drawn as a chat bubble.
     *
     * The speaker is carried three ways that do not depend on colour: the side
     * the bubble sits on, the name written above the agent's bubbles, and the
     * spoken description of the whole bubble, which always begins with the
     * speaker so a screen reader announces who said it.
     */
    fun chatLine(
        context: Context,
        speaker: String,
        text: String,
        showName: Boolean = true,
    ): LinearLayout {
        val mine = speaker.startsWith("나")
        val radius = dp(context, 16).toFloat()
        val tail = dp(context, 4).toFloat()
        val bubble = TextView(context).apply {
            this.text = text
            textSize = 15f
            setTextColor(if (mine) Color.WHITE else Color.parseColor("#111111"))
            setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.78f).toInt()
            background = GradientDrawable().apply {
                setColor(
                    if (mine) Color.parseColor("#173F35") else Color.parseColor("#ECECEC"),
                )
                cornerRadii = if (mine) {
                    // The corner nearest the speaker is the blunt one.
                    floatArrayOf(radius, radius, tail, tail, radius, radius, radius, radius)
                } else {
                    floatArrayOf(tail, tail, radius, radius, radius, radius, radius, radius)
                }
            }
            contentDescription = "$speaker $text"
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(0, dp(context, 3), 0, dp(context, 3))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            // A run of turns from the same speaker is named once, the way a
            // messenger does it. The spoken description above still carries the
            // speaker on every bubble.
            if (!mine && showName) {
                addView(
                    TextView(context).apply {
                        this.text = speaker.trimEnd(':', ' ')
                        textSize = 12f
                        setPadding(dp(context, 4), 0, 0, dp(context, 2))
                    },
                )
            }
            addView(bubble)
        }
    }

    /** Small wrapping row of tap targets for answering one question. */
    fun chipRow(context: Context, choices: List<Pair<String, () -> Unit>>): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 2), 0, dp(context, 6))
            choices.forEach { (label, action) ->
                addView(
                    Button(context).apply {
                        text = label
                        contentDescription = label
                        textSize = 13f
                        minHeight = dp(context, 44)
                        setOnClickListener { action() }
                    },
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    ).apply { marginEnd = dp(context, 4) },
                )
            }
        }

    /** A recommendation candidate: what it is, what it costs and why it is here. */
    fun candidateCard(
        context: Context,
        title: String,
        detail: String,
        reasons: String,
        action: Pair<String, () -> Unit>?,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
        // A proposal is a card in the thread, drawn like the draft card so the
        // two read as the same kind of thing the agent hands over.
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(context, 14).toFloat()
            setStroke(dp(context, 1), Color.parseColor("#D0D0D0"))
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(context, 6) }
        addView(
            TextView(context).apply {
                text = title
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
            },
        )
        addView(body(context, detail))
        addView(
            TextView(context).apply {
                text = reasons
                textSize = 12f
            },
        )
        if (action != null) {
            addView(button(context, action.first, onClick = action.second))
        }
    }

    /** A card the agent puts into the thread, such as the order draft. */
    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(context, 14).toFloat()
            setStroke(dp(context, 1), Color.parseColor("#D0D0D0"))
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(context, 6)
            bottomMargin = dp(context, 6)
        }
    }

    /**
     * One line of the draft the user can act on: what it is, what it says now
     * and a control to change it. The status word is always spelled out next to
     * the value, so nothing here rests on colour.
     */
    fun editableRow(
        context: Context,
        label: String,
        value: String,
        status: String,
        editLabel: String,
        onEdit: (() -> Unit)?,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(context, 5), 0, dp(context, 5))
        // Name, value and the control share one line; where the value came from
        // goes underneath at a smaller size. Provenance is often a sentence, and
        // squeezing it into a third of the width wrapped every row.
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    TextView(context).apply {
                        text = label
                        textSize = 14f
                        setTypeface(typeface, Typeface.BOLD)
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f),
                )
                addView(
                    TextView(context).apply {
                        text = value
                        textSize = 14f
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 5f),
                )
                if (onEdit != null) {
                    addView(
                        Button(context).apply {
                            text = editLabel
                            contentDescription = "$label $editLabel"
                            textSize = 12f
                            minWidth = dp(context, 56)
                            minHeight = dp(context, 44)
                            setPadding(dp(context, 6), 0, dp(context, 6), 0)
                            setOnClickListener { onEdit() }
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        if (status.isNotEmpty()) {
            addView(
                TextView(context).apply {
                    text = status
                    textSize = 12f
                    setPadding(0, dp(context, 1), 0, 0)
                },
            )
        }
    }

    /** Where the order stands and the one thing to do next, set apart from the thread. */
    fun banner(context: Context, path: String, next: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E8F0ED"))
                cornerRadius = dp(context, 10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(context, 6)
                bottomMargin = dp(context, 6)
            }
            addView(
                TextView(context).apply {
                    text = path
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#173F35"))
                },
            )
            addView(
                TextView(context).apply {
                    text = next
                    textSize = 14f
                    setPadding(0, dp(context, 4), 0, 0)
                },
            )
        }

    /**
     * The seam between two order conversations.
     *
     * It is centred and unattributed rather than a bubble, because nobody said
     * it: it marks where one order ended and the next began, so the thread reads
     * as a series of conversations instead of one that never stops growing.
     */
    fun chatDivider(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#4A5B54"))
        setPadding(dp(context, 12), dp(context, 16), dp(context, 12), dp(context, 8))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        contentDescription = text
    }

    fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(Color.LTGRAY)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(context, 1),
        ).apply {
            topMargin = dp(context, 12)
            bottomMargin = dp(context, 4)
        }
    }
}
