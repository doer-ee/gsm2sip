package com.callagent.gateway.rtp

/** RFC 4733 events are retransmitted; emit each completed key press once. */
class TelephoneEventReceiver {
    data class Tone(val digit: Char, val durationMs: Int)
    private data class Event(val ssrc: Long, val timestamp: Long, val digit: Int)
    private val completed = LinkedHashSet<Event>()

    fun receive(packet: RtpPacket): Tone? {
        val payload = packet.payload
        if (payload.size < 4) return null
        val event = payload[0].toInt() and 0xff
        // Only digits, star and hash are supported by Android Telecom.
        val digit = "0123456789*#".getOrNull(event) ?: return null
        if (payload[1].toInt() and 0x80 == 0) return null // Not ended yet.
        val key = Event(packet.ssrc, packet.timestamp, event)
        if (!completed.add(key)) return null
        if (completed.size > 64) completed.remove(completed.first())
        val ticks = ((payload[2].toInt() and 0xff) shl 8) or
            (payload[3].toInt() and 0xff)
        return Tone(digit, (ticks / 8).coerceIn(100, 3000)) // 8 kHz event clock.
    }
}
