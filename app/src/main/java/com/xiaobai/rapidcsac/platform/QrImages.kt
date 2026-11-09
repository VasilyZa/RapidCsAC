package com.xiaobai.rapidcsac.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun decodeQr(context: Context, uri: Uri): String =
    withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片无法读取" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val bitmap =
            context.contentResolver.openInputStream(uri).use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            } ?: error("图片无法读取")
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val reader =
                MultiFormatReader().apply {
                    setHints(
                        mapOf(
                            DecodeHintType.TRY_HARDER to true,
                            DecodeHintType.CHARACTER_SET to "UTF-8",
                        )
                    )
                }
            try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            } catch (_: com.google.zxing.NotFoundException) {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert()))).text
            } finally {
                reader.reset()
            }
        } catch (_: com.google.zxing.ReaderException) {
            error("未找到可识别的二维码")
        } finally {
            bitmap.recycle()
        }
    }

suspend fun saveQr(context: Context, bitmap: Bitmap, uri: Uri) =
    withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "保存失败" }
        } ?: error("无法写入所选位置")
    }
