package cc.xbta.shell2apk.apk

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipFile

/** 一个 zip 条目（APK 就是 zip） */
class ZipEntryData(val name: String, val method: Int, val data: ByteArray)

/**
 * 极简 zip 读写：读用 ZipFile；写自己实现，好处是能对未压缩条目做 4 字节对齐
 * （Android 对 resources.arsc 有未压缩 + 对齐的要求）。
 */
object ZipKit {

    private const val LOCAL_SIG = 0x04034b50
    private const val CENTRAL_SIG = 0x02014b50
    private const val EOCD_SIG = 0x06054b50

    fun read(file: File): MutableList<ZipEntryData> {
        val list = mutableListOf<ZipEntryData>()
        ZipFile(file).use { zf ->
            val en = zf.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (e.isDirectory) continue
                val bytes = zf.getInputStream(e).use { it.readBytes() }
                list.add(ZipEntryData(e.name, e.method, bytes))
            }
        }
        return list
    }

    fun write(entries: List<ZipEntryData>, out: File) {
        val fos = out.outputStream().buffered()
        val cd = ByteArrayOutputStream()
        var offset = 0L
        val now = System.currentTimeMillis()
        val time = dosTime(now)
        val date = dosDate(now)

        for (e in entries) {
            val nameBytes = e.name.toByteArray(Charsets.UTF_8)
            val crc = crc32(e.data)
            val stored = e.method == 0
            val payload: ByteArray = if (stored) e.data else deflate(e.data)
            val method = if (stored) 0 else 8
            val compressedSize = payload.size
            val uncompressedSize = e.data.size

            var extra = ByteArray(0)
            if (method == 0) {
                val base = offset + 30 + nameBytes.size
                val need = ((4 - (base % 4)) % 4).toInt()
                if (need != 0) {
                    val extraLen = 4 + need
                    extra = ByteArray(extraLen)
                    extra[0] = 0x35
                    extra[1] = 0xD9.toByte()
                    extra[2] = (extraLen - 4).toByte()
                    extra[3] = 0
                }
            }

            val localOffset = offset
            val lh = ByteArrayOutputStream()
            writeInt(lh, LOCAL_SIG)
            writeShort(lh, 20)
            writeShort(lh, 0)
            writeShort(lh, method)
            writeShort(lh, time)
            writeShort(lh, date)
            writeInt(lh, crc)
            writeInt(lh, compressedSize)
            writeInt(lh, uncompressedSize)
            writeShort(lh, nameBytes.size)
            writeShort(lh, extra.size)
            lh.write(nameBytes)
            lh.write(extra)
            val lhBytes = lh.toByteArray()
            fos.write(lhBytes)
            fos.write(payload)
            offset += lhBytes.size.toLong() + payload.size

            writeInt(cd, CENTRAL_SIG)
            writeShort(cd, 20)
            writeShort(cd, 20)
            writeShort(cd, 0)
            writeShort(cd, method)
            writeShort(cd, time)
            writeShort(cd, date)
            writeInt(cd, crc)
            writeInt(cd, compressedSize)
            writeInt(cd, uncompressedSize)
            writeShort(cd, nameBytes.size)
            writeShort(cd, 0)
            writeShort(cd, 0)
            writeShort(cd, 0)
            writeShort(cd, 0)
            writeInt(cd, 0)
            writeInt(cd, localOffset.toInt())
            cd.write(nameBytes)
        }

        val cdBytes = cd.toByteArray()
        fos.write(cdBytes)
        val eocd = ByteArrayOutputStream()
        writeInt(eocd, EOCD_SIG)
        writeShort(eocd, 0)
        writeShort(eocd, 0)
        writeShort(eocd, entries.size)
        writeShort(eocd, entries.size)
        writeInt(eocd, cdBytes.size)
        writeInt(eocd, offset.toInt())
        writeShort(eocd, 0)
        fos.write(eocd.toByteArray())
        fos.flush()
        fos.close()
    }

    fun crc32(b: ByteArray): Int {
        val c = CRC32()
        c.update(b)
        return c.value.toInt()
    }

    fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream(data.size + 64)
        val buf = ByteArray(8192)
        while (!d.finished()) {
            val n = d.deflate(buf)
            if (n > 0) out.write(buf, 0, n) else break
        }
        d.end()
        return out.toByteArray()
    }

    private fun dosTime(t: Long): Int {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = t
        return (c.get(java.util.Calendar.HOUR_OF_DAY) shl 11) or
            (c.get(java.util.Calendar.MINUTE) shl 5) or
            (c.get(java.util.Calendar.SECOND) / 2)
    }

    private fun dosDate(t: Long): Int {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = t
        return ((c.get(java.util.Calendar.YEAR) - 1980) shl 9) or
            ((c.get(java.util.Calendar.MONTH) + 1) shl 5) or
            c.get(java.util.Calendar.DAY_OF_MONTH)
    }

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
