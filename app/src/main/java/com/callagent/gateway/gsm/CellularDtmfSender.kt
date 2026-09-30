package com.callagent.gateway.gsm

import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.util.Log
import java.util.ArrayDeque

/** Serialize SIP keypad presses onto the active cellular call, on Telecom's thread. */
class CellularDtmfSender {
    private val handler = Handler(Looper.getMainLooper())
    private data class Tone(val call: Call, val digit: Char, val durationMs: Int)
    private val pending = ArrayDeque<Tone>()
    private var playing: Call? = null

    fun send(call: Call, digit: Char, durationMs: Int) {
        handler.post {
            if (call.state != Call.STATE_ACTIVE || digit !in "0123456789*#") return@post
            if (pending.size >= 32) {
                Log.w(TAG, "DTMF queue full; discarding press")
                return@post
            }
            pending.add(Tone(call, digit, durationMs.coerceIn(100, 3000)))
            if (playing == null) playNext()
        }
    }

    fun clear() {
        handler.post {
            handler.removeCallbacksAndMessages(null)
            pending.clear()
            playing?.let { call ->
                try { call.stopDtmfTone() } catch (e: Exception) {
                    Log.w(TAG, "Could not stop cellular DTMF: ${e.message}")
                }
            }
            playing = null
        }
    }

    private fun playNext() {
        val tone = pending.poll() ?: return
        if (tone.call.state != Call.STATE_ACTIVE) {
            playNext()
            return
        }
        playing = tone.call
        try {
            tone.call.playDtmfTone(tone.digit)
            Log.i(TAG, "Forwarded SIP DTMF to cellular call (${tone.durationMs}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "Cellular DTMF failed: ${e.message}")
            playing = null
            playNext()
            return
        }
        handler.postDelayed({
            try { tone.call.stopDtmfTone() } catch (e: Exception) {
                Log.w(TAG, "Could not stop cellular DTMF: ${e.message}")
            }
            // Keep playing non-null through the gap so new presses stay queued.
            handler.postDelayed({ playing = null; playNext() }, 80)
        }, tone.durationMs.toLong())
    }

    companion object { private const val TAG = "CellularDtmfSender" }
}
