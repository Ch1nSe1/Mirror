package memore.app.utils

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

object HashUtils {

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(data)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (stream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun quickHash(content: String): String {
        val digest = MessageDigest.getInstance("MD5")
        digest.update(content.toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
