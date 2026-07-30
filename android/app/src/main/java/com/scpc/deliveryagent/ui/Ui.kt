package com.scpc.deliveryagent.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
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

    /** One turn of the conversation. The speaker is spelled out, not implied by colour. */
    fun chatLine(context: Context, speaker: String, text: String): TextView =
        TextView(context).apply {
            this.text = "$speaker  $text"
            textSize = 14f
            setPadding(0, dp(context, 4), 0, dp(context, 4))
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
