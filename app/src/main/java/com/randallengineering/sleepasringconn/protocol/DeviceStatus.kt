package com.randallengineering.sleepasringconn.protocol

/**
 * Parses the 0x10 / 0x87 fixed 19-byte descriptor frame.
 *
 * Layout:
 * [0] = 0x10 or 0x87
 * [1] = Ring battery % (1..100)
 * [2] = State / work mode: 0x04 = on charger, 0x02/0x03 = worn streaming, 0x06 = sport
 * [4:6] = 16-bit BE step count for current quarter-hour bucket
 * [6:8] = Skin temperature channel A (0.1 °C BE)
 * [8:10] = Skin temperature channel B (0.1 °C BE)
 * [14:16] = Ring battery voltage mV (16-bit BE)
 * [17] = Case battery byte: low 7 bits = %, bit 0x80 = charging, 0xFF = not in case
 * [18] = XOR trailer
 */
data class SkinTemperature(
    val channelA: Double,
    val channelB: Double
) {
    val celsius: Double = (channelA + channelB) / 2.0
    val fahrenheit: Double = celsius * 9.0 / 5.0 + 32.0
}

data class CaseBattery(
    val percent: Int,
    val isCharging: Boolean
)

data class DeviceStatus(
    val batteryPercent: Int,
    val stateByte: Int,
    val isOnCharger: Boolean,
    val isSportMode: Boolean,
    val quarterHourSteps: Int,
    val skinTemperature: SkinTemperature?,
    val batteryVoltageMv: Int?,
    val caseBattery: CaseBattery?,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun parse(frame: ByteArray): DeviceStatus? {
            if (frame.size < 19) return null
            val opcode = frame[0].toInt() and 0xFF
            if (opcode != 0x10 && opcode != 0x87) return null
            if (!RingProtocol.isFrameValid(frame)) return null

            val battery = frame[1].toInt() and 0xFF
            val state = frame[2].toInt() and 0xFF
            val onCharger = state == 0x04
            val sport = state == 0x06

            val steps = ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)

            val tempA = ((frame[6].toInt() and 0xFF) shl 8) or (frame[7].toInt() and 0xFF)
            val tempB = ((frame[8].toInt() and 0xFF) shl 8) or (frame[9].toInt() and 0xFF)
            val skinTemp = if (tempA in 150..500 && tempB in 150..500) {
                SkinTemperature(tempA / 10.0, tempB / 10.0)
            } else null

            val volt = ((frame[14].toInt() and 0xFF) shl 8) or (frame[15].toInt() and 0xFF)
            val voltageMv = if (volt in 2500..4600) volt else null

            val caseByte = frame[17].toInt() and 0xFF
            val case = if (caseByte != 0xFF) {
                val casePct = caseByte and 0x7F
                val caseCharging = (caseByte and 0x80) != 0
                if (casePct <= 100) CaseBattery(casePct, caseCharging) else null
            } else null

            return DeviceStatus(
                batteryPercent = battery.coerceIn(0, 100),
                stateByte = state,
                isOnCharger = onCharger,
                isSportMode = sport,
                quarterHourSteps = steps,
                skinTemperature = skinTemp,
                batteryVoltageMv = voltageMv,
                caseBattery = case
            )
        }
    }
}
