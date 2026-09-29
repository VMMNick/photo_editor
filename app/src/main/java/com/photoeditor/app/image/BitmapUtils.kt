package com.photoeditor.app.image

import android.graphics.Bitmap
import com.photoeditor.app.core.PixelImage

fun Bitmap.toPixelImage(): PixelImage {
    val px = IntArray(width * height)
    getPixels(px, 0, width, 0, 0, width, height)
    return PixelImage(width, height, px)
}

fun PixelImage.toBitmap(): Bitmap {
    val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    b.setPixels(pixels, 0, width, 0, 0, width, height)
    return b
}

/** Записати пікселі у вже наявний Bitmap того ж розміру (без додаткової алокації Bitmap). */
fun PixelImage.writeInto(b: Bitmap) {
    require(b.width == width && b.height == height && b.isMutable)
    b.setPixels(pixels, 0, width, 0, 0, width, height)
}
