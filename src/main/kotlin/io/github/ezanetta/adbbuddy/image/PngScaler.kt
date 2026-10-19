package io.github.ezanetta.adbbuddy.image

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

class ScaledImage(
    val png: ByteArray,
    val width: Int,
    val height: Int,
    val originalWidth: Int,
    val originalHeight: Int,
) {
    /** Device pixels per image pixel. Uses the longest side, which is exact; the other side is rounded. */
    val deviceToImageRatio: Double get() = maxOf(originalWidth, originalHeight).toDouble() / maxOf(width, height)
}

/** Downscales screenshots so they stay cheap to send to a model. Needs no display (headless AWT). */
object PngScaler {
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size > PNG_SIGNATURE.size && bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)

    /** Fits the image within [maxSide] pixels on its longest side; returns it unchanged if already smaller. */
    fun fit(png: ByteArray, maxSide: Int): ScaledImage {
        val source = ImageIO.read(ByteArrayInputStream(png)) ?: error("not a readable PNG")
        val longest = maxOf(source.width, source.height)
        if (maxSide <= 0 || longest <= maxSide) {
            return ScaledImage(png, source.width, source.height, source.width, source.height)
        }
        val ratio = maxSide.toDouble() / longest
        val width = (source.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (source.height * ratio).roundToInt().coerceAtLeast(1)
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        target.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(source, 0, 0, width, height, null)
            dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(target, "png", out)
        return ScaledImage(out.toByteArray(), width, height, source.width, source.height)
    }
}
