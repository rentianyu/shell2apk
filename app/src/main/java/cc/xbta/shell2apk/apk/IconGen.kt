package cc.xbta.shell2apk.apk

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import cc.xbta.shell2apk.Task
import java.io.ByteArrayOutputStream

/** 运行时生成桌面图标 PNG（各密度） */
object IconGen {

    private val DPI_SIZE = mapOf(
        "mdpi" to 48,
        "hdpi" to 72,
        "xhdpi" to 96,
        "xxhdpi" to 144,
        "xxxhdpi" to 192
    )

    /** dir 可以是 "mipmap-mdpi"、"mdpi"、"mipmap-xxxhdpi-v4" 等 */
    fun sizeFor(dir: String): Int {
        val d = dir.substringAfter("mipmap-", dir).substringBefore("-v")
        DPI_SIZE[d]?.let { return it }
        return when {
            d.contains("xxxhdpi") -> 192
            d.contains("xxhdpi") -> 144
            d.contains("xhdpi") -> 96
            d.contains("hdpi") -> 72
            else -> 48
        }
    }

    fun render(text: String, kind: String, px: Int): ByteArray {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val top: Int
        val bottom: Int
        val fg: Int
        when (kind) {
            Task.KIND_URL -> {
                top = 0xFF10294A.toInt(); bottom = 0xFF071222.toInt(); fg = 0xFF62B6FF.toInt()
            }
            Task.KIND_ACTIVITY -> {
                top = 0xFF2A1B45.toInt(); bottom = 0xFF140C22.toInt(); fg = 0xFFB79BFF.toInt()
            }
            Task.KIND_INTENT -> {
                top = 0xFF3A2410.toInt(); bottom = 0xFF1A0F06.toInt(); fg = 0xFFFFB454.toInt()
            }
            Task.KIND_BROADCAST -> {
                top = 0xFF3B1418.toInt(); bottom = 0xFF1B090B.toInt(); fg = 0xFFFF8A8A.toInt()
            }
            else -> {
                top = 0xFF0F3B2A.toInt(); bottom = 0xFF071A12.toInt(); fg = 0xFF4CDE80.toInt()
            }
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, px.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        val r = px * 0.22f
        c.drawRoundRect(RectF(0f, 0f, px.toFloat(), px.toFloat()), r, r, p)

        val t = (if (text.isBlank()) ">" else text.trim()).take(2)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG)
        tp.color = fg
        tp.textAlign = Paint.Align.CENTER
        tp.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        tp.textSize = if (t.length <= 1) px * 0.52f else px * 0.38f
        val fm = tp.fontMetrics
        val baseY = px / 2f - (fm.ascent + fm.descent) / 2f
        c.drawText(t, px / 2f, baseY, tp)

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
