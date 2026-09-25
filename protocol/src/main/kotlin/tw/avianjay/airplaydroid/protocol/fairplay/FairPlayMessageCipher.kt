package tw.avianjay.airplaydroid.protocol.fairplay

/**
 * The cipher that protects the **m3 message body** in a FairPlay SAP handshake.
 *
 * ### What this is, and what it is not
 *
 * An m3 carries the sender's 128-byte *local SAP* encrypted in bytes 16..144,
 * in CBC over eight 16-byte blocks. This class implements exactly that.
 *
 * It is **not** the whole handshake. Turning an m2 challenge into the 20-byte
 * response is a separate, much harder problem (Phase 1's white-box AES); see
 * [FairPlayResponder]. This file is the part that is ordinary AES, and it is
 * self-contained: no tables beyond the round keys and the S-box.
 *
 * ### Why it is not `javax.crypto`
 *
 * The transform is AES-128 in *shape* only. The round keys are Apple's, baked
 * per FairPlay message mode, and there is no key to derive them from -- so no
 * AES implementation can represent it, and the round operations are spelled out
 * here. The S-box, ShiftRows, MixColumns and GF(2^8) arithmetic are standard.
 *
 * ### Modes
 *
 * An m2 selects a mode in byte 13, and the mode picks both the CBC IV and the
 * middle round keys. All four modes' constants are present here, so this class
 * can encrypt and decrypt any of them; only [FairPlayRecords.Mode.MODE_3] has
 * ever been observed on hardware.
 *
 * The constants are Apple-derived data, transcribed from `doubletake`'s
 * `internal/airplay/fairplay_message.go` (LGPL-3.0-or-later). They are extracted
 * values, not an independent derivation.
 */
object FairPlayMessageCipher {

    /** Standard AES forward S-box. */
    private val SBOX = hex(
        "637c777bf26b6fc53001672bfed7ab76ca82c97dfa5947f0add4a2af9ca472c0" +
            "b7fd9326363ff7cc34a5e5f171d8311504c723c31896059a071280e2eb27b275" +
            "09832c1a1b6e5aa0523bd6b329e32f8453d100ed20fcb15b6acbbe394a4c58cf" +
            "d0efaafb434d338545f9027f503c9fa851a3408f929d38f5bcb6da2110fff3d2" +
            "cd0c13ec5f974417c4a77e3d645d197360814fdc222a908846eeb814de5e0bdb" +
            "e0323a0a4906245cc2d3ac629195e479e7c8376d8dd54ea96c56f4ea657aae08" +
            "ba78252e1ca6b4c6e8dd741f4bbd8b8a703eb5664803f60e613557b986c11d9e" +
            "e1f8981169d98e949b1e87e9ce5528df8ca1890dbfe6426841992d0fb054bb16"
    )

    /** The inverse S-box, for the decryption direction. */
    private val INV_SBOX = hex(
        "52096ad53036a538bf40a39e81f3d7fb7ce339829b2fff87348e4344c4dee9cb" +
            "547b9432a6c2233dee4c950b42fac34e082ea16628d924b2765ba2496d8bd125" +
            "72f8f66486689816d4a45ccc5d65b6926c704850fdedb9da5e154657a78d9d84" +
            "90d8ab008cbcd30af7e45805b8b34506d02c1e8fca3f0f02c1afbd0301138a6b" +
            "3a9111414f67dcea97f2cfcef0b4e67396ac7422e7ad3585e2f937e81c75df6e" +
            "47f11a711d29c5896fb7620eaa18be1bfc563e4bc6d279209adbc0fe78cd5af4" +
            "1fdda8338807c731b11210592780ec5f60517fa919b54a0d2de57a9f93c99cef" +
            "a0e03b4dae2af5b0c8ebbb3c83539961172b047eba77d626e169146355210c7d"
    )

    private val MESSAGE_IV = arrayOf(
        hex("5752f1b7549d8f870c10485a6088cadb"),
        hex("df7b1563f005587752a90402b9a39295"),
        hex("68b54611fb04de676c968efb8c9db0c9"),
        hex("27078b2123361e7adc9d0b115354690d"),
    )

    private val ROUND_KEY_0 = hex("d30dfdb563def72b012ead72884dca25")
    private val ROUND_KEY_10 = hex("cd5ecf47e9af289b51188f68d085eb69")

    /** Nine middle round keys per mode, in order. */
    private val MIDDLE_KEYS: Array<Array<ByteArray>> = arrayOf(
        // Mode 0
        arrayOf(
            hex("6af008a6ae5118268acccb4357d1e711"), hex("c6b579e0dfce8114d41d91c868513be3"),
            hex("bafc24065ebc9cf6131652de75afd0de"), hex("81a8b516836ee6493cccc125db99e0ec"),
            hex("781b6ceb300cfe8f3c0c54c92dc7e2a3"), hex("594cc871ad4a3dd851a884b0ed048f5c"),
            hex("afea245eae1f2de557bb511b17ddcc3d"), hex("8e7a945569c09856533872ca6a3a00de"),
            hex("f3ca31810cb6201b24b5c7e4ee108bc0"),
        ),
        // Mode 1
        arrayOf(
            hex("1b9018bea49613e86ab413627c84a766"), hex("c94c1dc55c8bbb04a940f640f420eba5"),
            hex("d5664239df63981372b7a8b2360b160b"), hex("462cd7af87b8005a47157e32644557cf"),
            hex("f0c0ac26f01988a8573d39e298f97f6a"), hex("9407abf947788ceada25ee558333edb6"),
            hex("3f820f9ed823ce2f7722c4bab1d2e335"), hex("71fca0ff354f8991109961878bfa7cfb"),
            hex("566c98381eedcfe7954f7213f61fc87e"),
        ),
        // Mode 2
        arrayOf(
            hex("2ab2ebe11496a9655dd1b28a9910ee30"), hex("9cbc01d64fa41ca1f705a0ef7fb47934"),
            hex("08f02908920c5b348d32d3470b07705b"), hex("8735404470486fa4a42b04fbfde11e3c"),
            hex("d8eba4780f69653dfc7d2b4fbfb1ac68"), hex("a74a7a6696be42e1b884e1acff1311a4"),
            hex("cc7a007cd8a3f330d3f17ba3437d52cd"), hex("1edeff958a6254b20114cfddc02a48f8"),
            hex("3a36f4e069e1907b2c8c779be7336c09"),
        ),
        // Mode 3
        arrayOf(
            hex("f6a223b4f01d8a7927a7c965de554de0"), hex("0cd1515d43246c87fc2f494364a4cfbc"),
            hex("d66e3bdd6c7f1b811f90291984b5eaee"), hex("530d7ae85b3040569f13130f18fbb1e5"),
            hex("0784c47a9fe10f3d5ea5432ecdd9f585"), hex("b9b0368eb7d134d90ebd1b94b255a4fb"),
            hex("b2a4352534066fdec1ce38db3d8c3b85"), hex("0b317a942520622d89578e9fe5a13fc3"),
            hex("bdf569e6befc1d2fc08baf733f1d176a"),
        ),
    )

    const val BODY_BYTES = 128
    private const val BLOCK = 16

    /**
     * Encrypts a 128-byte SAP body for an m3 under [mode]. CBC, eight blocks,
     * chaining from the mode's fixed IV.
     */
    fun encryptBody(mode: FairPlayRecords.Mode, plaintext: ByteArray): ByteArray {
        require(plaintext.size == BODY_BYTES) {
            "a message body is $BODY_BYTES bytes, got ${plaintext.size}"
        }
        val out = ByteArray(BODY_BYTES)
        var chain = MESSAGE_IV[mode.value.toInt()]
        for (block in 0 until 8) {
            val start = block * BLOCK
            val state = ByteArray(BLOCK) { (plaintext[start + it].toInt() xor chain[it].toInt()).toByte() }
            encryptBlock(state, mode)
            state.copyInto(out, start)
            chain = state
        }
        return out
    }

    /**
     * Decrypts a 128-byte m3 body under [mode].
     *
     * Mode 3 historically walks the CBC chain **backwards**; that is not a
     * cosmetic difference, and getting it wrong yields plausible-looking garbage.
     */
    fun decryptBody(mode: FairPlayRecords.Mode, body: ByteArray): ByteArray {
        require(body.size == BODY_BYTES) {
            "a message body is $BODY_BYTES bytes, got ${body.size}"
        }
        val out = ByteArray(BODY_BYTES)
        for (step in 0 until 8) {
            val block = if (mode == FairPlayRecords.Mode.MODE_3) 7 - step else step
            val start = block * BLOCK
            val state = body.copyOfRange(start, start + BLOCK)
            decryptBlock(state, mode)

            val chain = if (block > 0) body.copyOfRange(start - BLOCK, start) else MESSAGE_IV[mode.value.toInt()]
            for (i in 0 until BLOCK) out[start + i] = (state[i].toInt() xor chain[i].toInt()).toByte()
        }
        return out
    }

    // ------------------------------------------------------------ the block

    private fun encryptBlock(state: ByteArray, mode: FairPlayRecords.Mode) {
        xorRoundKey(state, ROUND_KEY_0)
        subBytes(state)
        shiftRows(state)
        val keys = MIDDLE_KEYS[mode.value.toInt()]
        for (round in 0 until 9) {
            mixColumns(state)
            xorRoundKey(state, keys[round])
            subBytes(state)
            shiftRows(state)
        }
        xorRoundKey(state, ROUND_KEY_10)
    }

    private fun decryptBlock(state: ByteArray, mode: FairPlayRecords.Mode) {
        xorRoundKey(state, ROUND_KEY_10)
        val keys = MIDDLE_KEYS[mode.value.toInt()]
        for (round in 9 downTo 1) {
            inverseShiftRows(state)
            inverseSubBytes(state)
            xorRoundKey(state, keys[round - 1])
            inverseMixColumns(state)
        }
        inverseShiftRows(state)
        inverseSubBytes(state)
        xorRoundKey(state, ROUND_KEY_0)
    }

    private fun xorRoundKey(state: ByteArray, key: ByteArray) {
        for (i in 0 until BLOCK) state[i] = (state[i].toInt() xor key[i].toInt()).toByte()
    }

    private fun subBytes(state: ByteArray) {
        for (i in 0 until BLOCK) state[i] = SBOX[state[i].toInt() and 0xFF]
    }

    private fun inverseSubBytes(state: ByteArray) {
        for (i in 0 until BLOCK) state[i] = INV_SBOX[state[i].toInt() and 0xFF]
    }

    /** Row `r` rotates left by `r`; the state is column-major, so index `4*col+row`. */
    private fun shiftRows(state: ByteArray) {
        val previous = state.copyOf()
        for (row in 0 until 4) {
            for (column in 0 until 4) {
                state[4 * column + row] = previous[4 * ((column + row) and 3) + row]
            }
        }
    }

    private fun inverseShiftRows(state: ByteArray) {
        val previous = state.copyOf()
        for (row in 0 until 4) {
            for (column in 0 until 4) {
                state[4 * column + row] = previous[4 * ((column - row + 4) and 3) + row]
            }
        }
    }

    private fun mixColumns(state: ByteArray) {
        for (column in 0 until 4) {
            val o = column * 4
            val a = state[o].toInt() and 0xFF
            val b = state[o + 1].toInt() and 0xFF
            val c = state[o + 2].toInt() and 0xFF
            val d = state[o + 3].toInt() and 0xFF
            state[o] = (gfMul(a, 2) xor gfMul(b, 3) xor c xor d).toByte()
            state[o + 1] = (a xor gfMul(b, 2) xor gfMul(c, 3) xor d).toByte()
            state[o + 2] = (a xor b xor gfMul(c, 2) xor gfMul(d, 3)).toByte()
            state[o + 3] = (gfMul(a, 3) xor b xor c xor gfMul(d, 2)).toByte()
        }
    }

    private fun inverseMixColumns(state: ByteArray) {
        for (column in 0 until 4) {
            val o = column * 4
            val a = state[o].toInt() and 0xFF
            val b = state[o + 1].toInt() and 0xFF
            val c = state[o + 2].toInt() and 0xFF
            val d = state[o + 3].toInt() and 0xFF
            state[o] = (gfMul(a, 14) xor gfMul(b, 11) xor gfMul(c, 13) xor gfMul(d, 9)).toByte()
            state[o + 1] = (gfMul(a, 9) xor gfMul(b, 14) xor gfMul(c, 11) xor gfMul(d, 13)).toByte()
            state[o + 2] = (gfMul(a, 13) xor gfMul(b, 9) xor gfMul(c, 14) xor gfMul(d, 11)).toByte()
            state[o + 3] = (gfMul(a, 11) xor gfMul(b, 13) xor gfMul(c, 9) xor gfMul(d, 14)).toByte()
        }
    }

    /** Multiplication in GF(2^8) modulo the AES polynomial x^8 + x^4 + x^3 + x + 1. */
    private fun gfMul(aIn: Int, bIn: Int): Int {
        var a = aIn
        var b = bIn
        var product = 0
        while (b != 0) {
            if (b and 1 != 0) product = product xor a
            val high = a and 0x80
            a = a shl 1
            if (high != 0) a = a xor 0x1b
            b = b shr 1
        }
        return product and 0xFF
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
