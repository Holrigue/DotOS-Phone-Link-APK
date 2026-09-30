package com.holrigue.argdos.dashboard

/**
 * Builds the "New Alert" (0x2A46) payload the watch's Alert Notification Service
 * expects. Mirrors the firmware parser in ARGUS-Design-OS src/ans.cpp:
 *
 *   byte 0 : ANS CategoryID
 *   byte 1 : count (number of new alerts of this category; we send 1)
 *   then 0x00-separated UTF-8 strings: title, then body
 *
 * The firmware reads the first two 0x00-separated fields as title and body and
 * ignores the app field (it always labels them "Phone"). The whole thing must
 * fit in one ATT write (MTU - 3 bytes), so the caller passes the negotiated
 * budget and we truncate title/body on UTF-8 boundaries to fit.
 */
object NotifPacket {

    // ANS CategoryID values (Bluetooth SIG). The firmware maps these back to its
    // own Category vocabulary (see ans.cpp map_category).
    const val CAT_SIMPLE = 0
    const val CAT_EMAIL = 1
    const val CAT_NEWS = 2
    const val CAT_CALL = 3
    const val CAT_MISSED_CALL = 4
    const val CAT_SMS = 5
    const val CAT_SCHEDULE = 7
    const val CAT_IM = 9

    /**
     * @param category one of the CAT_* values
     * @param title    notification title (may be empty)
     * @param body     notification text (may be empty)
     * @param maxBytes total packet budget (typically negotiated MTU - 3)
     */
    fun build(category: Int, title: String, body: String, maxBytes: Int): ByteArray {
        // Header is 2 bytes; one 0x00 separator sits between title and body.
        val budget = (maxBytes - 3).coerceAtLeast(0)

        // Give the title up to a third of the budget (min 16 bytes) so the body,
        // usually the more informative part, keeps most of the room.
        val titleCap = if (budget <= 0) 0 else maxOf(16, budget / 3)
        val titleBytes = clipUtf8(title, minOf(titleCap, budget))
        val bodyBytes = clipUtf8(body, (budget - titleBytes.size).coerceAtLeast(0))

        val out = ArrayList<Byte>(titleBytes.size + bodyBytes.size + 3)
        out.add((category and 0xFF).toByte())
        out.add(1)                       // count = 1 new alert
        out.addAll(titleBytes.toList())
        out.add(0x00)                    // title/body separator
        out.addAll(bodyBytes.toList())
        return out.toByteArray()
    }

    // UTF-8 encode `s` and cut it to at most `max` bytes without splitting a
    // multi-byte sequence (so the watch never renders a broken glyph).
    private fun clipUtf8(s: String, max: Int): ByteArray {
        if (max <= 0 || s.isEmpty()) return ByteArray(0)
        val full = s.toByteArray(Charsets.UTF_8)
        if (full.size <= max) return full
        var end = max
        // Back up while the byte at `end` is a UTF-8 continuation byte (10xxxxxx).
        while (end > 0 && (full[end].toInt() and 0xC0) == 0x80) end--
        return full.copyOfRange(0, end)
    }
}
