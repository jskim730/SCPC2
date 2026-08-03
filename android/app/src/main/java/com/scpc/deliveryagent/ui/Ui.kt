package com.scpc.deliveryagent.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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

    fun density(context: Context): Float = context.resources.displayMetrics.density

    fun dp(context: Context, value: Int): Int = (value * density(context)).toInt()

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
        setBackgroundColor(Color.parseColor("#11000000"))
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
