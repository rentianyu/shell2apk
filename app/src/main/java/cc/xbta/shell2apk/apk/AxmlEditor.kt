package cc.xbta.shell2apk.apk

import java.io.ByteArrayOutputStream

/**
 * 改写二进制 AndroidManifest.xml 里的字符串。
 *
 * 模板 APK 的 manifest 里预埋了两个标记：包名 `cc.xbta.shell2apk.tpl` 和标题占位符，
 * 生成时把它们换成用户输入的值。做法是只重建「字符串池」chunk，节点流原样保留
 * （节点里存的是字符串索引，索引不变，所以不需要动）。
 */
object AxmlEditor {

    private const val TYPE_STRING_POOL = 0x0001
    private const val UTF8_FLAG = 0x100

    fun replaceStrings(input: ByteArray, map: Map<String, String>): ByteArray {
        var pos = u16(input, 2)
        var poolStart = -1
        var poolSize = 0
        while (pos + 8 <= input.size) {
            val type = u16(input, pos)
            val size = i32(input, pos + 4)
            if (size <= 0) break
            if (type == TYPE_STRING_POOL) {
                poolStart = pos
                poolSize = size
                break
            }
            pos += size
        }
        require(poolStart >= 0) { "AXML 解析失败：未找到字符串池" }

        val headerSize = u16(input, poolStart + 2)
        val stringCount = i32(input, poolStart + 8)
        val styleCount = i32(input, poolStart + 12)
        val flags = i32(input, poolStart + 16)
        val stringsStart = i32(input, poolStart + 20)
        val stylesStart = i32(input, poolStart + 24)
        val utf8 = (flags and UTF8_FLAG) != 0
        require(stringCount in 0..200000) { "AXML 解析失败：字符串数量异常 $stringCount" }

        val strings = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val off = i32(input, poolStart + headerSize + i * 4)
            strings.add(
                if (utf8) readUtf8(input, poolStart + stringsStart + off)
                else readUtf16(input, poolStart + stringsStart + off)
            )
        }
        for (i in strings.indices) {
            val nv = map[strings[i]]
            if (nv != null) strings[i] = nv
        }

        val data = ByteArrayOutputStream()
        val offsets = IntArray(stringCount)
        for (i in 0 until stringCount) {
            offsets[i] = data.size()
            if (utf8) writeUtf8(data, strings[i]) else writeUtf16(data, strings[i])
        }
        while (data.size() % 4 != 0) data.write(0)
        val stringsBytes = data.toByteArray()

        val stylesBytes = if (styleCount > 0 && stylesStart > 0 && stylesStart < poolSize) {
            input.copyOfRange(poolStart + stylesStart, poolStart + poolSize)
        } else {
            ByteArray(0)
        }

        val newPool = ByteArrayOutputStream()
        val newSize = 28 + 4 * stringCount + 4 * styleCount + stringsBytes.size + stylesBytes.size
        writeShort(newPool, TYPE_STRING_POOL)
        writeShort(newPool, 28)
        writeInt(newPool, newSize)
        writeInt(newPool, stringCount)
        writeInt(newPool, styleCount)
        writeInt(newPool, if (utf8) UTF8_FLAG else 0)
        writeInt(newPool, 28 + 4 * stringCount + 4 * styleCount)
        writeInt(newPool, if (stylesBytes.isEmpty()) 0 else 28 + 4 * stringCount + 4 * styleCount + stringsBytes.size)
        for (o in offsets) writeInt(newPool, o)
        if (styleCount > 0) {
            for (i in 0 until styleCount) {
                writeInt(newPool, i32(input, poolStart + headerSize + stringCount * 4 + i * 4))
            }
        }
        newPool.write(stringsBytes)
        newPool.write(stylesBytes)
        val poolBytes = newPool.toByteArray()

        val out = ByteArrayOutputStream()
        out.write(input, 0, poolStart)
        out.write(poolBytes)
        out.write(input, poolStart + poolSize, input.size - (poolStart + poolSize))
        val result = out.toByteArray()
        val total = result.size
        result[4] = (total and 0xff).toByte()
        result[5] = ((total ushr 8) and 0xff).toByte()
        result[6] = ((total ushr 16) and 0xff).toByte()
        result[7] = ((total ushr 24) and 0xff).toByte()
        return result
    }

    // ---------------- string codec ----------------

    private fun readUtf8(d: ByteArray, start: Int): String {
        var p = start
        var len16: Int
        val b0 = d[p].toInt() and 0xff
        if (b0 and 0x80 != 0) {
            len16 = ((b0 and 0x7f) shl 8) or (d[p + 1].toInt() and 0xff)
            p += 2
        } else {
            len16 = b0
            p += 1
        }
        var len8: Int
        val b1 = d[p].toInt() and 0xff
        if (b1 and 0x80 != 0) {
            len8 = ((b1 and 0x7f) shl 8) or (d[p + 1].toInt() and 0xff)
            p += 2
        } else {
            len8 = b1
            p += 1
        }
        if (len16 < 0 || len8 < 0) return ""
        return String(d, p, len8, Charsets.UTF_8)
    }

    private fun writeUtf8(o: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        writeUtf8Len(o, s.length)
        writeUtf8Len(o, b.size)
        o.write(b)
        o.write(0)
    }

    private fun writeUtf8Len(o: ByteArrayOutputStream, len: Int) {
        if (len > 0x7f) {
            o.write(((len ushr 8) and 0x7f) or 0x80)
            o.write(len and 0xff)
        } else {
            o.write(len)
        }
    }

    private fun readUtf16(d: ByteArray, start: Int): String {
        var p = start
        var len = u16(d, p)
        p += 2
        if (len and 0x8000 != 0) {
            len = ((len and 0x7fff) shl 16) or u16(d, p)
            p += 2
        }
        if (len < 0) return ""
        return String(d, p, len * 2, Charsets.UTF_16LE)
    }

    private fun writeUtf16(o: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_16LE)
        writeShort(o, s.length)
        o.write(b)
        writeShort(o, 0)
    }

    // ---------------- byte helpers ----------------

    private fun u16(d: ByteArray, o: Int): Int =
        (d[o].toInt() and 0xff) or ((d[o + 1].toInt() and 0xff) shl 8)

    private fun i32(d: ByteArray, o: Int): Int =
        (d[o].toInt() and 0xff) or
            ((d[o + 1].toInt() and 0xff) shl 8) or
            ((d[o + 2].toInt() and 0xff) shl 16) or
            ((d[o + 3].toInt() and 0xff) shl 24)

    private fun writeShort(o: ByteArrayOutputStream, v: Int) {
        o.write(v and 0xff)
        o.write((v ushr 8) and 0xff)
    }

    private fun writeInt(o: ByteArrayOutputStream, v: Int) {
        o.write(v and 0xff)
        o.write((v ushr 8) and 0xff)
        o.write((v ushr 16) and 0xff)
        o.write((v ushr 24) and 0xff)
    }
}
