package com.scpc.deliveryagent.core

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Deterministic JSON encoding for state digests.
 *
 * Every state digest reported to the Probe result must be reproducible from the
 * persisted bytes alone, so object keys are sorted and numbers are encoded
 * without platform-dependent formatting. This is the candidate-owned digest of
 * candidate-owned production state; the starter AAR computes the separate
 * contract-level result digest.
 */
object Canonical {

    private val BACKSPACE = Char(0x08)
    private val FORM_FEED = Char(0x0C)
    private val LINE_SEPARATOR = Char(0x2028)
    private val PARAGRAPH_SEPARATOR = Char(0x2029)

    fun encode(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> encodeObject(value)
        is JSONArray -> encodeArray(value)
        is String -> quote(value)
        is Boolean -> value.toString()
        is Int, is Long -> value.toString()
        is Double -> encodeDouble(value)
        is Float -> encodeDouble(value.toDouble())
        is Number -> value.toString()
        else -> quote(value.toString())
    }

    private fun encodeObject(value: JSONObject): String =
        value.keys().asSequence().sorted().joinToString(
            separator = ",",
            prefix = "{",
            postfix = "}",
        ) { key -> quote(key) + ":" + encode(value.opt(key)) }

    private fun encodeArray(value: JSONArray): String =
        (0 until value.length()).joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]",
        ) { index -> encode(value.opt(index)) }

    private fun encodeDouble(value: Double): String {
        require(!value.isNaN() && !value.isInfinite()) { "non-finite number in production state" }
        return if (value == Math.floor(value) && Math.abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            value.toString()
        }
    }

    private fun quote(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        value.forEach { ch ->
            when {
                ch == '"' -> out.append("\\\"")
                ch == '\\' -> out.append("\\\\")
                ch == '\n' -> out.append("\\n")
                ch == '\r' -> out.append("\\r")
                ch == '\t' -> out.append("\\t")
                ch == BACKSPACE -> out.append("\\b")
                ch == FORM_FEED -> out.append("\\f")
                // Remaining control characters and the two JavaScript line
                // terminators are escaped so the encoding is parser stable.
                ch < ' ' || ch == LINE_SEPARATOR || ch == PARAGRAPH_SEPARATOR ->
                    out.append(String.format("\\u%04x", ch.code))
                else -> out.append(ch)
            }
        }
        out.append('"')
        return out.toString()
    }
}

object Digest {

    private val HEX = "0123456789abcdef".toCharArray()

    fun utf8(value: String): String = bytes(value.toByteArray(Charsets.UTF_8))

    fun bytes(value: ByteArray): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value)
        val out = StringBuilder(bytes.size * 2)
        bytes.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            out.append(HEX[unsigned ushr 4])
            out.append(HEX[unsigned and 0x0f])
        }
        return out.toString()
    }

    fun canonical(value: Any?): String = utf8(Canonical.encode(value))
}
