package com.randallengineering.sleepasringconn.protocol

/**
 * RingConn per-connection authentication handshake generator.
 *
 * Sequence:
 * Host sends: 01 00 00
 * Ring sends: 81 00 <challenge> <xor>
 * Host computes:
 *   V = mac[3] ^ mac[4] ^ mac[5] (XOR of last 3 MAC bytes)
 *   r = SM3([V, challenge]).takeLast(3)
 * Host replies:
 *   01 01 <r0> <r1> <r2> 00
 */
object RingAuth {

    fun macTailXor(mac: ByteArray): Byte {
        if (mac.size < 6) return 0
        return (mac[3].toInt() xor mac[4].toInt() xor mac[5].toInt()).toByte()
    }

    fun parseMacString(macAddress: String): ByteArray {
        val parts = macAddress.split(":", "-")
        if (parts.size != 6) return ByteArray(6)
        val result = ByteArray(6)
        for (i in 0 until 6) {
            result[i] = parts[i].toInt(16).toByte()
        }
        return result
    }

    fun computeAuthResponse(challenge: Byte, mac: ByteArray): ByteArray {
        val v = macTailXor(mac)
        val input = byteArrayOf(v, challenge)
        val digest = SM3.hash(input)
        return byteArrayOf(digest[29], digest[30], digest[31])
    }

    fun createAuthCommand(challenge: Byte, mac: ByteArray): ByteArray {
        val resp = computeAuthResponse(challenge, mac)
        return byteArrayOf(0x01, 0x01, resp[0], resp[1], resp[2], 0x00)
    }

    fun macFromSystemId(sysId: ByteArray): ByteArray? {
        if (sysId.size == 8 && (sysId[3].toInt() and 0xFF) == 0xFF && (sysId[4].toInt() and 0xFF) == 0xFE) {
            return byteArrayOf(sysId[0], sysId[1], sysId[2], sysId[5], sysId[6], sysId[7])
        }
        if (sysId.size == 8 && (sysId[4].toInt() and 0xFF) == 0xFE && (sysId[3].toInt() and 0xFF) == 0xFF) {
            return byteArrayOf(sysId[0], sysId[1], sysId[2], sysId[5], sysId[6], sysId[7])
        }
        if (sysId.size == 8) {
            val rev = sysId.reversedArray()
            if ((rev[3].toInt() and 0xFF) == 0xFF && (rev[4].toInt() and 0xFF) == 0xFE) {
                return byteArrayOf(rev[0], rev[1], rev[2], rev[5], rev[6], rev[7])
            }
        }
        if (sysId.size == 6) return sysId
        if (sysId.size > 6) return sysId.takeLast(6).toByteArray()
        return null
    }
}
