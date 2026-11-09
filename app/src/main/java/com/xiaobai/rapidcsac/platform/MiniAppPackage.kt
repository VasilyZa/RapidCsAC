package com.xiaobai.rapidcsac.platform

import android.content.Context
import android.util.Base64
import com.xiaobai.rapidcsac.core.network.HttpApi
import com.xiaobai.rapidcsac.core.network.json
import com.xiaobai.rapidcsac.core.network.objects
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Signed packages are extracted into a bounded, isolated cache directory. */
class MiniAppPackage(
    private val context: Context,
    private val api: HttpApi,
    private val namespace: String,
) {
    data class Loaded(val title: String, val root: File, val entry: String, val host: String)

    suspend fun open(id: String): Loaded =
        withContext(Dispatchers.IO) {
            val info =
                api.postJson("/emapps/public/info", json("app_id" to id)).getJSONObject("data")
            val public = api.getJson("/emapps/public/key")
            val directory =
                File(context.cacheDir, "miniapps/${UUID.randomUUID()}").apply { mkdirs() }
            val archive = File(directory, "package.bin")
            try {
                api.download(
                    info.optString("download_url").ifBlank {
                        "/emapps/dl/${android.net.Uri.encode(id)}"
                    },
                    archive,
                    16L * 1024 * 1024,
                )
                val hash = MessageDigest.getInstance("SHA-256")
                archive.inputStream().use { input ->
                    val buffer = ByteArray(16384)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        hash.update(buffer, 0, n)
                    }
                }
                val expected = info.optString("file_hash")
                val digest = hash.digest().joinToString("") { "%02x".format(it) }
                require(expected.isEmpty() || expected.equals(digest, true)) { "小程序文件校验失败" }
                val pem = public.getString("pem")
                val rawKey =
                    Base64.decode(
                        pem.lines().filterNot { it.startsWith("---") }.joinToString(""),
                        Base64.DEFAULT,
                    )
                val der =
                    if (pem.contains("BEGIN RSA PUBLIC KEY"))
                        sequence(
                            byteArrayOf(
                                0x30,
                                0x0d,
                                0x06,
                                0x09,
                                0x2a,
                                0x86.toByte(),
                                0x48,
                                0x86.toByte(),
                                0xf7.toByte(),
                                0x0d,
                                0x01,
                                0x01,
                                0x01,
                                0x05,
                                0x00,
                            ) + tlv(0x03, byteArrayOf(0) + rawKey)
                        )
                    else rawKey
                val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
                val signature = Signature.getInstance("SHA256withRSA").apply { initVerify(key) }
                archive.inputStream().use { input ->
                    val buffer = ByteArray(16384)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        signature.update(buffer, 0, n)
                    }
                }
                require(
                    signature.verify(Base64.decode(info.getString("signature"), Base64.DEFAULT))
                ) {
                    "小程序签名验证失败"
                }
                val content = File(directory, "content").apply { mkdirs() }
                val first = archive.inputStream().use { it.read() }
                if (first == 0x50) extractZip(archive, content)
                else {
                    val files = JSONObject(archive.readText()).objects("files")
                    require(files.size in 1..512) { "小程序文件数量无效" }
                    var total = 0L
                    files.forEach { file ->
                        val bytes = file.getString("content").toByteArray()
                        total += bytes.size
                        require(total <= 32 * 1024 * 1024 && bytes.size <= 8 * 1024 * 1024) {
                            "小程序解压后过大"
                        }
                        safeFile(content, file.getString("filename")).apply {
                            parentFile?.mkdirs()
                            writeBytes(bytes)
                        }
                    }
                }
                archive.delete()
                val entry = info.optString("entry_page", "index.html").ifBlank { "index.html" }
                require(safeFile(content, entry).isFile) { "小程序缺少入口页面" }
                val host =
                    "app-${MessageDigest.getInstance("SHA-256").digest("$namespace:$id".toByteArray()).take(8).joinToString("") { "%02x".format(it) }}.rapidcsac.invalid"
                Loaded(info.optString("name", id), content, entry, host)
            } catch (failure: Exception) {
                directory.deleteRecursively()
                throw failure
            }
        }

    private fun extractZip(archive: File, root: File) {
        var count = 0
        var total = 0L
        ZipInputStream(archive.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= 512) { "小程序文件过多" }
                val target = safeFile(root, entry.name)
                if (entry.isDirectory) target.mkdirs()
                else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output ->
                        val buffer = ByteArray(16384)
                        var size = 0L
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            total += n
                            size += n
                            require(total <= 32 * 1024 * 1024 && size <= 8 * 1024 * 1024) {
                                "小程序解压后过大"
                            }
                            output.write(buffer, 0, n)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    companion object {
        fun safeFile(root: File, path: String): File {
            require(!path.contains('\\') && !path.startsWith('/')) { "无效的小程序文件路径" }
            val target = File(root, path).canonicalFile
            require(target.path.startsWith(root.canonicalPath + File.separator)) { "无效的小程序文件路径" }
            return target
        }

        private fun sequence(data: ByteArray) = tlv(0x30, data)

        private fun tlv(tag: Int, data: ByteArray): ByteArray {
            val length =
                when {
                    data.size < 128 -> byteArrayOf(data.size.toByte())
                    data.size < 256 -> byteArrayOf(0x81.toByte(), data.size.toByte())
                    else ->
                        byteArrayOf(0x82.toByte(), (data.size shr 8).toByte(), data.size.toByte())
                }
            return byteArrayOf(tag.toByte()) + length + data
        }
    }
}
