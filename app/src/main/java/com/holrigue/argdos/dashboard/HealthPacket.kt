package com.holrigue.argdos.dashboard

/**
 * Builds the compact health packet the watch's health-input characteristic
 * expects. Mirrors the firmware wire format (see the ARGUS-Design-OS
 * docs/health/README.md and src/health_state.h):
 *
 *   byte 0 : version (= 1)
 *   byte 1 : field mask (bit0 sleep, bit1 steps, bit2 goal, bit3 stress,
 *            bit4 hr average, bit5 hr sample)
 *   then each present field, in bit order, little-endian:
 *     sleep  u8   (0..100)
 *     steps  u32
 *     goal   u32  (the watch ignores it - the goal is Settings-owned)
 *     stress u8   (0..100)
 *     hr     u16  (bpm)
 *
 * A null field is simply omitted (its mask bit stays 0).
 */
object HealthPacket {
    const val VERSION = 0x01
    const val BIT_SLEEP = 0x01
    const val BIT_STEPS = 0x02
    const val BIT_GOAL = 0x04
    const val BIT_STRESS = 0x08
    const val BIT_HR_AVG = 0x10

    fun build(
        sleepScore: Int? = null,
        steps: Int? = null,
        stress: Int? = null,
        hrBpm: Int? = null,
    ): ByteArray {
        val body = ArrayList<Byte>(16)
        var mask = 0

        sleepScore?.let {
            mask = mask or BIT_SLEEP
            body.add(clamp0(it, 100).toByte())
        }
        steps?.let {
            mask = mask or BIT_STEPS
            putU32(body, it.coerceAtLeast(0))
        }
        stress?.let {
            mask = mask or BIT_STRESS
            body.add(clamp0(it, 100).toByte())
        }
        hrBpm?.let {
            mask = mask or BIT_HR_AVG
            putU16(body, it.coerceIn(0, 0xFFFF))
        }

        val out = ByteArray(2 + body.size)
        out[0] = VERSION.toByte()
        out[1] = mask.toByte()
        for (i in body.indices) out[2 + i] = body[i]
        return out
    }

    private fun clamp0(v: Int, max: Int) = v.coerceIn(0, max)

    private fun putU16(list: MutableList<Byte>, v: Int) {
        list.add((v and 0xFF).toByte())
        list.add(((v shr 8) and 0xFF).toByte())
    }

    private fun putU32(list: MutableList<Byte>, v: Int) {
        list.add((v and 0xFF).toByte())
        list.add(((v shr 8) and 0xFF).toByte())
        list.add(((v shr 16) and 0xFF).toByte())
        list.add(((v shr 24) and 0xFF).toByte())
    }
}
