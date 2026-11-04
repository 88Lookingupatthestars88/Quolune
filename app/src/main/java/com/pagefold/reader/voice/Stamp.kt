/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * 令牌算法移植自 rany2/edge-tts 的 src/edge_tts/drm.py，
 * revision b2ffbdd9f43f28f03cc939b5de0f3964aa62d60e（2026-10-13）。
 * Copyright (c) rany and edge-tts contributors.
 * 许可全文与署名随包放在 assets/licenses/。
 */
package com.pagefold.reader.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Edge 那一路握手要的令牌。
 *
 * 源串是「三百秒一格的时间片 + 一个固定串」。同一串算两遍：一遍是改过的 SHA-256，
 * 一遍拿这个摘要的前 8 字节当密钥做折叠，两截用连字符接起来发出去。
 *
 * 全程按 32 位回绕，不用 Long 兜底 —— 换成 Long 再截断，中间结果就不一样了。
 */
internal object Stamp {

    const val CLIENT = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    private const val EPOCH_1601 = 11_644_473_600L
    private const val SLICE_SEC = 300L

    fun generate(nowMs: Long): String {
        val sec = Math.floorDiv(nowMs, 1000L) + EPOCH_1601
        val ticks = (sec - Math.floorMod(sec, SLICE_SEC)) * 10_000_000L
        val src = (ticks.toString() + CLIENT).toByteArray(Charsets.US_ASCII)
        val hash = bare256(src)
        return upper(mask64(src, hash)) + "-" + upper(hash)
    }

    /* ---------------------------------------------------------------- 前一半 */

    /**
     * 改过的 SHA-256。
     *
     * 跟标准一样的地方不再重复：初始值、轮常量、补位规则。不一样的有四处，
     * 两处在调度表的关键字上加了偏移，一处把第 30 个字定死，一处让主循环从第 14 轮起跳。
     * 源串只有四十来字节，补位之后仍然一块装得下。
     */
    private fun bare256(src: ByteArray): ByteArray {
        val w = IntArray(64)
        w[14] = be32(src, 0, 3)
        w[15] = be32(src, 3, 4)

        for (i in 16 until 29) {
            var v = SEED[i - 16] + (sig1(w[i - 2]) xor (if (i == 16) 0x000B92C0 else 0))
            if (i >= 21) v += w[i - 7]
            w[i] = v
        }
        w[29] = SEED[13] + sig1(w[27]) + w[22] + (sig0(w[14]) xor 0x059C0B80)
        w[30] = sig1(w[28]) + w[23] + sig0(w[15]) + w[14] + 0xC9DC06A7.toInt() - K[14]
        fill(w, 31)

        val h = mix(MID, w, 14)
        for (i in h.indices) h[i] += H0[i]

        // 协议规定的做法：把源串当成「前面已有 57 字节」接在后面再压一遍。
        val total = 57 + src.size
        val pad = Math.floorMod(55 - total, 64)
        val buf = ByteArray(src.size - 7 + 1 + pad + 8)
        src.copyInto(buf, 0, 7)
        buf[src.size - 7] = 0x80.toByte()
        ByteBuffer.wrap(buf).putLong(buf.size - 8, total.toLong() * 8L)

        var off = 0
        while (off < buf.size) {
            for (i in 0 until 16) w[i] = be32(buf, off + i * 4, 4)
            fill(w, 16)
            val blk = mix(h, w, 0)
            for (i in 0 until 8) h[i] += blk[i]
            off += 64
        }

        val out = ByteBuffer.allocate(32)
        for (v in h) out.putInt(v)
        return out.array()
    }

    /** 补调度表。`at` 是第一个还要算的位置。 */
    private fun fill(w: IntArray, at: Int) {
        for (i in at until 64) {
            w[i] = sig1(w[i - 2]) + w[i - 7] + sig0(w[i - 15]) + w[i - 16]
        }
    }

    /**
     * 主循环。`from` 是起步轮次。
     *
     * 走完整的 64 轮时 `from` 是 0；头一遍从第 14 轮起跳，那第 14 轮的常量
     * 要换成一个非标准值，所以这里要判一下。
     */
    private fun mix(h: IntArray, w: IntArray, from: Int): IntArray {
        var p0 = h[0]
        var p1 = h[1]
        var p2 = h[2]
        var p3 = h[3]
        var p4 = h[4]
        var p5 = h[5]
        var p6 = h[6]
        var p7 = h[7]

        for (i in from until 64) {
            val k = if (from == 14 && i == 14) 0xC9DC06A7.toInt() else K[i]
            val t1 = p7 + cap1(p4) + (p6 xor (p4 and (p5 xor p6))) + k + w[i]
            val t2 = cap0(p0) + ((p0 and p1) or (p2 and (p0 or p1)))
            p7 = p6
            p6 = p5
            p5 = p4
            p4 = p3 + t1
            p3 = p2
            p2 = p1
            p1 = p0
            p0 = t1 + t2
        }
        return intArrayOf(p0, p1, p2, p3, p4, p5, p6, p7)
    }

    /** 左旋 16 位再乘一个常数。折叠里这两步反复出现。 */
    private fun turn(v: Int, k: Int): Int = Integer.rotateLeft(v, 16) * k

    private fun be32(d: ByteArray, off: Int, n: Int): Int {
        var v = 0
        for (i in 0 until n) v = (v shl 8) or (d[off + i].toInt() and 0xFF)
        return v
    }

    private fun sig0(v: Int): Int {
        val a = Integer.rotateRight(v, 7) xor Integer.rotateRight(v, 18)
        return a xor (v ushr 3)
    }

    private fun sig1(v: Int): Int {
        val a = Integer.rotateRight(v, 17) xor Integer.rotateRight(v, 19)
        return a xor (v ushr 10)
    }

    private fun cap0(v: Int): Int {
        val a = Integer.rotateRight(v, 2) xor Integer.rotateRight(v, 13)
        return a xor Integer.rotateRight(v, 22)
    }

    private fun cap1(v: Int): Int {
        val a = Integer.rotateRight(v, 6) xor Integer.rotateRight(v, 11)
        return a xor Integer.rotateRight(v, 25)
    }

    /* ---------------------------------------------------------------- 后一半 */

    /**
     * 折叠。摘要前 8 字节拆成两个密钥（各按位或上 1，避免零传播）。
     *
     * 源串的每个 32 位字按奇偶轮流乘上去，中间还夹一层旋转移位，最后压成 8 字节。
     * 常量表那 14 个数先走一遍，再逐字走一遍源串 —— 两组都按「两两一对」进同一段累加。
     */
    private fun mask64(src: ByteArray, hash: ByteArray): ByteArray {
        val head = ByteBuffer.wrap(hash).order(ByteOrder.LITTLE_ENDIAN)
        val ka = head.int or 1
        val kb = head.int or 1
        val kaBig = ka * 0x82893933.toInt()
        val kbBig = kb * 0x82893933.toInt()

        var sumA = 0
        var sumB = 0
        var keep = 0
        var carry = 0

        /** 两个 32 位字一组。x/y 是乘过的值，ox/oy 是还没乘的偏移量。 */
        fun pair(x: Int, y: Int, ox: Int, oy: Int) {
            var q = Integer.rotateLeft(ox + x, 16) * 0x76F50F34
            sumA += Integer.rotateLeft(q, 16) * 0xC3EFEA97.toInt() + q
            q = Integer.rotateLeft(oy + y, 16) * 0x1881CE3C
            carry = Integer.rotateLeft(q, 16) * 0x35BD1EC9 + q
            sumA += carry

            keep = keep * ka + x
            keep = Integer.rotateLeft(keep, 16) * 0xCF98B111.toInt()
            keep = Integer.rotateLeft(keep, 16) * 0x87085B9F.toInt()
            keep = Integer.rotateLeft(keep, 16) * 0x12CEB96D
            keep = Integer.rotateLeft(keep, 16) * 0x257E1D83
            sumB += keep

            var t = keep * kb + y
            t = turn(t, 0xA27416F5.toInt())
            t = turn(t, 0xD38396FF.toInt())
            keep = Integer.rotateLeft(Integer.rotateLeft(t, 16) * 0x7C932B89, 16) * 0xBFA49F69.toInt()
            sumB += keep
        }

        // 常量表：十四个数，四个一组往前推
        for (i in 0 until 28 step 4) {
            pair(
                WHEEL[i] * kaBig,
                WHEEL[i + 2] * kbBig,
                WHEEL[i + 1],
                WHEEL[i + 3],
            )
        }

        // 源串：前面空一个字节，按小端读成 32 位字
        val buf = ByteArray(((src.size + 1 + 7) / 8) * 8)
        src.copyInto(buf, 1)
        val le = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        for (j in 0 until buf.size / 4 step 2) {
            val lo = le.int
            val hi = le.int
            var tx = lo * ka
            var ox = Integer.rotateLeft(lo, 16) * 0xEF0569FB.toInt()
            if (j == 0) {
                tx += 0xF2F90B1A.toInt() * kaBig
                ox += 0x0B1A0000
            }
            pair(tx, hi * kb, ox, Integer.rotateLeft(hi, 16) * 0xC31713DB.toInt())
        }

        val out = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(carry xor keep)
        out.putInt(sumA xor sumB)
        return out.array()
    }

    /* ---------------------------------------------------------------- 工具 */

    private fun upper(b: ByteArray): String {
        val hex = "0123456789ABCDEF"
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xFF
            sb.append(hex[v ushr 4]).append(hex[v and 0x0F])
        }
        return sb.toString()
    }

    private val H0 = intArrayOf(
        0x6A09E667, 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt(),
        0x510E527F, 0x9B05688C.toInt(), 0x1F83D9AB, 0x5BE0CD19
    )

    private val MID = intArrayOf(
        0xFD9FBEA2.toInt(), 0x91243CE7.toInt(), 0xBE499094.toInt(), 0xCD2E1E0A.toInt(),
        0x6C006F49, 0x04BD35AB, 0xFEB0A7E4.toInt(), 0x03EFFBD2
    )

    private val SEED = intArrayOf(
        0xEB24BF9E.toInt(), 0x1558691F, 0xA0C09EE2.toInt(), 0x0CFA32BA,
        0xA622EE8C.toInt(), 0xD1DD9491.toInt(), 0xC0DD25A0.toInt(), 0xF9E70AB6.toInt(),
        0xF0C31573.toInt(), 0x7058CE4E, 0x7188E606, 0x00E5AB33,
        0xD258B060.toInt(), 0x72766564
    )

    private val K = intArrayOf(
        0x428A2F98, 0x71374491, 0xB5C0FBCF.toInt(),
        0xE9B5DBA5.toInt(), 0x3956C25B, 0x59F111F1,
        0x923F82A4.toInt(), 0xAB1C5ED5.toInt(), 0xD807AA98.toInt(),
        0x12835B01, 0x243185BE, 0x550C7DC3,
        0x72BE5D74, 0x80DEB1FE.toInt(), 0x9BDC06A7.toInt(),
        0xC19BF174.toInt(), 0xE49B69C1.toInt(), 0xEFBE4786.toInt(),
        0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F,
        0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        0x983E5152.toInt(), 0xA831C66D.toInt(), 0xB00327C8.toInt(),
        0xBF597FC7.toInt(), 0xC6E00BF3.toInt(), 0xD5A79147.toInt(),
        0x06CA6351, 0x14292967, 0x27B70A85,
        0x2E1B2138, 0x4D2C6DFC, 0x53380D13,
        0x650A7354, 0x766A0ABB, 0x81C2C92E.toInt(),
        0x92722C85.toInt(), 0xA2BFE8A1.toInt(), 0xA81A664B.toInt(),
        0xC24B8B70.toInt(), 0xC76C51A3.toInt(), 0xD192E819.toInt(),
        0xD6990624.toInt(), 0xF40E3585.toInt(), 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C,
        0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A,
        0x5B9CCA4F, 0x682E6FF3, 0x748F82EE,
        0x78A5636F, 0x84C87814.toInt(), 0x8CC70208.toInt(),
        0x90BEFFFA.toInt(), 0xA4506CEB.toInt(), 0xBEF9A3F7.toInt(),
        0xC67178F2.toInt()
    )

    private val WHEEL = intArrayOf(
        0xE82E91B1.toInt(), 0xB32700D0.toInt(), 0x44DE24C6, 0x88DFF51D.toInt(),
        0x3BEB65BC, 0x29514038, 0xC89C5933.toInt(), 0xB328CBDF.toInt(),
        0x8D1EC211.toInt(), 0xD405B4D5.toInt(), 0x314EF5D5, 0x26E55F3C,
        0xE92491B1.toInt(), 0x607801C6, 0xF0A5B9D5.toInt(), 0x82B2C1FB.toInt(),
        0x06064CF3, 0xFA51A3DA.toInt(), 0xDA79FA60.toInt(), 0x207CC464,
        0xBED20560.toInt(), 0x3C3C74F3, 0x89F9C9F8.toInt(), 0xD2D14B61.toInt(),
        0x35FA38C6, 0xBB04A2C1.toInt(), 0xCB49E3C6.toInt(), 0xC5626167.toInt()
    )
}
