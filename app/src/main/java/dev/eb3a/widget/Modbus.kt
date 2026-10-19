package dev.eb3a.widget

/**
 * Classic Bluetti BLE-Modbus used by EB3A (same map as the open bluetti_mqtt project).
 * Read holding registers 10..49. Power fields are uint16 watts:
 * 36 DC in, 37 AC in, 38 AC out, 39 DC out, 43 battery percent.
 */
object Modbus {
    const val START = 10
    const val COUNT = 40
    const val RESPONSE_SIZE = 2 * COUNT + 5

    fun readHolding(start: Int = START, count: Int = COUNT): ByteArray {
        val body = byteArrayOf(
            0x01,
            0x03,
            (start shr 8).toByte(),
            (start and 0xFF).toByte(),
            (count shr 8).toByte(),
            (count and 0xFF).toByte(),
        )
        val crc = crc16(body)
        return byteArrayOf(
            body[0], body[1], body[2], body[3], body[4], body[5],
            (crc and 0xFF).toByte(),
            (crc shr 8).toByte(),
        )
    }

    fun crc16(data: ByteArray, length: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }

    fun isValid(response: ByteArray): Boolean {
        if (response.size < 5) return false
        val crc = crc16(response, response.size - 2)
        val lo = response[response.size - 2].toInt() and 0xFF
        val hi = response[response.size - 1].toInt() and 0xFF
        return crc == (lo or (hi shl 8))
    }

    data class Sample(
        val dcIn: Int,
        val acIn: Int,
        val acOut: Int,
        val dcOut: Int,
        val battery: Int,
    )

    fun parseCore(response: ByteArray, start: Int = START): Sample? {
        if (response.size < RESPONSE_SIZE) return null
        val frame = if (response.size == RESPONSE_SIZE) response else response.copyOf(RESPONSE_SIZE)
        if (frame[0] != 0x01.toByte() || frame[1] != 0x03.toByte()) return null
        if ((frame[2].toInt() and 0xFF) != COUNT * 2) return null
        if (!isValid(frame)) return null
        val dcIn = reg(frame, start, 36)
        val acIn = reg(frame, start, 37)
        val acOut = reg(frame, start, 38)
        val dcOut = reg(frame, start, 39)
        val batteryRaw = reg(frame, start, 43)
        if (dcIn !in 0..4000 || acIn !in 0..4000 || acOut !in 0..4000 || dcOut !in 0..4000) return null
        val battery = if (batteryRaw in 0..100) batteryRaw else -1
        return Sample(dcIn, acIn, acOut, dcOut, battery)
    }

    private fun reg(response: ByteArray, start: Int, address: Int): Int {
        val off = 3 + (address - start) * 2
        val hi = response[off].toInt() and 0xFF
        val lo = response[off + 1].toInt() and 0xFF
        return (hi shl 8) or lo
    }
}
