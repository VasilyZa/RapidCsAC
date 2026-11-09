package com.xiaobai.rapidcsac.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.xiaobai.rapidcsac.core.network.UploadPart
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaFiles(private val context: Context) {
    suspend fun prepare(uri: Uri, field: String, image: Boolean = false): UploadPart =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "uploads").apply { mkdirs() }
            var name = "attachment"
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst())
                        name = cursor.getString(0).substringAfterLast('/').take(120)
                }
            val file = File(directory, UUID.randomUUID().toString())
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var total = 0L
                        while (true) {
                            val size = input.read(buffer)
                            if (size < 0) break
                            total += size
                            if (total > 32L * 1024 * 1024) throw IOException("附件不能超过 32 MB")
                            output.write(buffer, 0, size)
                        }
                    }
                } ?: throw IOException("无法读取这个文件")
                if (!image)
                    return@withContext UploadPart(
                        field,
                        file,
                        name,
                        context.contentResolver.getType(uri) ?: "application/octet-stream",
                    )
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("图片格式无法读取")
                val options =
                    BitmapFactory.Options().apply {
                        inSampleSize = 1
                        while (
                            maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 1600
                        ) inSampleSize *= 2
                    }
                val bitmap =
                    BitmapFactory.decodeFile(file.path, options) ?: throw IOException("图片解码失败")
                var rotated: Bitmap? = null
                val output = File(directory, "${UUID.randomUUID()}.jpg")
                try {
                    val exif = ExifInterface(file)
                    val matrix =
                        Matrix().apply {
                            if (exif.isFlipped) postScale(-1f, 1f)
                            postRotate(exif.rotationDegrees.toFloat())
                        }
                    rotated =
                        if (matrix.isIdentity) bitmap
                        else
                            Bitmap.createBitmap(
                                bitmap,
                                0,
                                0,
                                bitmap.width,
                                bitmap.height,
                                matrix,
                                true,
                            )
                    output.outputStream().use {
                        if (!rotated.compress(Bitmap.CompressFormat.JPEG, 86, it))
                            throw IOException("图片压缩失败")
                    }
                    file.delete()
                    UploadPart(field, output, name.substringBeforeLast('.') + ".jpg", "image/jpeg")
                } catch (failure: Exception) {
                    output.delete()
                    throw failure
                } finally {
                    if (rotated !== bitmap) rotated?.recycle()
                    bitmap.recycle()
                }
            } catch (failure: Exception) {
                file.delete()
                throw failure
            }
        }
}
