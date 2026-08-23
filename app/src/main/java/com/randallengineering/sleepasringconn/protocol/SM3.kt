package com.randallengineering.sleepasringconn.protocol

/**
 * SM3 Cryptographic Hash Algorithm (GB/T 32905-2016).
 * Pure Kotlin implementation for RingConn per-connection authentication.
 */
object SM3 {
    private val IV = intArrayOf(
        0x7380166f.toInt(), 0x4914b2b9.toInt(), 0x172442d7.toInt(), 0xda8a0600.toInt(),
        0xa96f30bc.toInt(), 0x163138aa.toInt(), 0xe38dee4d.toInt(), 0xb0fb0e4e.toInt()
    )

    private fun rotl(x: Int, n: Int): Int {
        val shift = n and 31
        return if (shift == 0) x else (x shl shift) or (x ushr (32 - shift))
    }

    private fun t(j: Int): Int = if (j < 16) 0x79cc4519.toInt() else 0x7a879d8a.toInt()

    private fun ff(x: Int, y: Int, z: Int, j: Int): Int =
        if (j < 16) x xor y xor z else (x and y) or (x and z) or (y and z)

    private fun gg(x: Int, y: Int, z: Int, j: Int): Int =
        if (j < 16) x xor y xor z else (x and y) or (x.inv() and z)

    private fun p0(x: Int): Int = x xor rotl(x, 9) xor rotl(x, 17)

    private fun p1(x: Int): Int = x xor rotl(x, 15) xor rotl(x, 23)

    fun hash(input: ByteArray): ByteArray {
        val bitLen = input.size.toLong() * 8
        val msg = ArrayList<Byte>(input.size + 64)
        for (b in input) msg.add(b)
        msg.add(0x80.toByte())
        while (msg.size % 64 != 56) {
            msg.add(0.toByte())
        }
        for (i in 56 downTo 0 step 8) {
            msg.add(((bitLen ushr i) and 0xff).toByte())
        }

        var v0 = IV[0]
        var v1 = IV[1]
        var v2 = IV[2]
        var v3 = IV[3]
        var v4 = IV[4]
        var v5 = IV[5]
        var v6 = IV[6]
        var v7 = IV[7]

        var blk = 0
        val w = IntArray(68)
        val w1 = IntArray(64)

        while (blk < msg.size) {
            for (j in 0 until 16) {
                val o = blk + j * 4
                w[j] = ((msg[o].toInt() and 0xff) shl 24) or
                        ((msg[o + 1].toInt() and 0xff) shl 16) or
                        ((msg[o + 2].toInt() and 0xff) shl 8) or
                        (msg[o + 3].toInt() and 0xff)
            }
            for (j in 16 until 68) {
                w[j] = p1(w[j - 16] xor w[j - 9] xor rotl(w[j - 3], 15)) xor rotl(w[j - 13], 7) xor w[j - 6]
            }
            for (j in 0 until 64) {
                w1[j] = w[j] xor w[j + 4]
            }

            var a = v0
            var b = v1
            var c = v2
            var d = v3
            var e = v4
            var f = v5
            var g = v6
            var h = v7

            for (j in 0 until 64) {
                val ss1 = rotl(rotl(a, 12) + e + rotl(t(j), j % 32), 7)
                val ss2 = ss1 xor rotl(a, 12)
                val tt1 = ff(a, b, c, j) + d + ss2 + w1[j]
                val tt2 = gg(e, f, g, j) + h + ss1 + w[j]
                d = c
                c = rotl(b, 9)
                b = a
                a = tt1
                h = g
                g = rotl(f, 19)
                f = e
                e = p0(tt2)
            }

            v0 = v0 xor a
            v1 = v1 xor b
            v2 = v2 xor c
            v3 = v3 xor d
            v4 = v4 xor e
            v5 = v5 xor f
            v6 = v6 xor g
            v7 = v7 xor h

            blk += 64
        }

        val out = ByteArray(32)
        val words = intArrayOf(v0, v1, v2, v3, v4, v5, v6, v7)
        for (i in 0 until 8) {
            val word = words[i]
            out[i * 4] = ((word ushr 24) and 0xff).toByte()
            out[i * 4 + 1] = ((word ushr 16) and 0xff).toByte()
            out[i * 4 + 2] = ((word ushr 8) and 0xff).toByte()
            out[i * 4 + 3] = (word and 0xff).toByte()
        }
        return out
    }
}
