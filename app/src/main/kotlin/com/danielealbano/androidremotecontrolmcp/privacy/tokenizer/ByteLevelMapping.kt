package com.danielealbano.androidremotecontrolmcp.privacy.tokenizer

/**
 * GPT-2 byte-level mapping: each of the 256 byte values maps to a printable Unicode char so that
 * arbitrary bytes survive BPE as text. Printable ASCII (`!`..`~`), `0xA1`..`0xAC` and `0xAE`..`0xFF`
 * map to themselves; the remaining 68 bytes map to `U+0100 + n` in order.
 */
object ByteLevelMapping {
    val byteToChar: CharArray = CharArray(BYTE_COUNT)
    private val charToByteMap: Map<Char, Int>

    init {
        val printable = HashSet<Int>()
        for (b in '!'.code..'~'.code) printable += b
        for (b in 0xA1..0xAC) printable += b
        for (b in 0xAE..0xFF) printable += b
        var extra = 0
        val reverse = HashMap<Char, Int>()
        for (b in 0 until BYTE_COUNT) {
            val ch = if (b in printable) b.toChar() else (BYTE_COUNT + extra++).toChar()
            byteToChar[b] = ch
            reverse[ch] = b
        }
        charToByteMap = reverse
    }

    fun charToByte(ch: Char): Int? = charToByteMap[ch]

    private const val BYTE_COUNT = 256
}
