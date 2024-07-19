package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.StreamableAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

object Utils {
    private const val SECRET = "g4el58wc0zvf9na1"
    private val secretIvSpec = IvParameterSpec(byteArrayOf(0,1,2,3,4,5,6,7))

    private fun bitwiseXor(firstVal: Char, secondVal: Char, thirdVal: Char): Char {
        return (BigInteger(byteArrayOf(firstVal.code.toByte())) xor
                BigInteger(byteArrayOf(secondVal.code.toByte())) xor
                BigInteger(byteArrayOf(thirdVal.code.toByte()))).toByte().toInt().toChar()
    }

    fun createBlowfishKey(trackId: String): String {
        val trackMd5Hex = trackId.toMD5()
        var blowfishKey = ""

        for (i in 0..15) {
            val nextChar = bitwiseXor(trackMd5Hex[i], trackMd5Hex[i + 16], SECRET[i])
            blowfishKey += nextChar
        }

        return blowfishKey
    }

    fun decryptBlowfish(chunk: ByteArray, blowfishKey: String): ByteArray {
        val secretKeySpec = SecretKeySpec(blowfishKey.toByteArray(), "Blowfish")
        val thisTrackCipher = Cipher.getInstance("BLOWFISH/CBC/NoPadding")
        thisTrackCipher.init(Cipher.DECRYPT_MODE, secretKeySpec, secretIvSpec)
        return thisTrackCipher.update(chunk)
    }

    fun getContentLength(url: String, client: OkHttpClient): Long {
        var totalLength = 0L
        val request = okhttp3.Request.Builder().url(url).head().build()
        val response = client.newCall(request).execute()
        totalLength += response.header("Content-Length")?.toLong() ?: 0L
        response.close()
        return totalLength
    }
}

private fun bytesToHex(bytes: ByteArray): String {
    var hexString = ""
    for (byte in bytes) {
        hexString += String.format("%02X", byte)
    }
    return hexString
}

fun String.toMD5(): String {
    val bytes = MessageDigest.getInstance("MD5").digest(this.toByteArray(Charsets.ISO_8859_1))
    return bytesToHex(bytes).lowercase()
}

fun getByteStreamAudio(streamable: Streamable, client: OkHttpClient): StreamableAudio {
    val url = streamable.id
    val contentLength = Utils.getContentLength(url, client)
    val key = streamable.extra["key"]!!

    val request = Request.Builder().url(url).build()
    var decChunk = ByteArray(0)

    runBlocking {
        withContext(Dispatchers.IO) {
            val response = client.newCall(request).execute()
            val byteStream = response.body?.byteStream()
                ?: throw IOException("Failed to get byte stream from response")

            try {
                // Read the entire byte stream into memory
                val completeStream = ByteArrayOutputStream()
                val buffer = ByteArray(2 * 1024 * 1024) // Increased buffer size
                var bytesRead: Int
                while (byteStream.read(buffer).also { bytesRead = it } != -1) {
                    completeStream.write(buffer, 0, bytesRead)
                }

                // Ensure complete stream is read
                val completeStreamBytes = completeStream.toByteArray()
                println("Total bytes read: ${completeStreamBytes.size}")

                // Determine chunk size based on decryption block size
                val chunkSize = 2048 * 3072
                val numChunks = (completeStreamBytes.size + chunkSize - 1) / chunkSize
                println("Number of chunks: $numChunks")

                // Measure decryption time
                val startTime = System.nanoTime()

                // Decrypt the chunks concurrently
                val deferredChunks = (0 until numChunks).map { i ->
                    val start = i * chunkSize
                    val end = minOf((i + 1) * chunkSize, completeStreamBytes.size)
                    println("Chunk $i: start $start, end $end")
                    async(Dispatchers.Default) { decryptStreamChunk(completeStreamBytes.copyOfRange(start, end), key) }
                }

                // Wait for all decryption tasks to complete and concatenate the results
                deferredChunks.forEach { deferred ->
                    decChunk += deferred.await()
                }

                val endTime = System.nanoTime()
                val duration = endTime - startTime
                println("Decryption took ${duration / 1_000_000} milliseconds")
            } finally {
                response.close()
                byteStream.close()
            }
        }
    }

    return StreamableAudio.ByteStreamAudio(
        stream = decChunk.inputStream(),
        totalBytes = contentLength
    )
}

private fun decryptStreamChunk(chunk: ByteArray, key: String): ByteArray {
    val decryptedStream = ByteArrayOutputStream()
    var place = 0

    while (place < chunk.size) {
        val remainingBytes = chunk.size - place
        val blockSize = 2048
        val encryptedBlock = blockSize * 3 // Every third block is encrypted

        val currentChunkSize = min(remainingBytes, encryptedBlock)
        val currentChunk = chunk.copyOfRange(place, place + currentChunkSize)
        place += currentChunkSize

        for (i in 0 until currentChunk.size step blockSize) {
            val blockEnd = min(currentChunk.size, i + blockSize)
            val block = currentChunk.copyOfRange(i, blockEnd)

            if ((i / blockSize) % 3 == 0 && block.size == blockSize) {
                val decryptedBlock = Utils.decryptBlowfish(block, key)
                decryptedStream.write(decryptedBlock)
            } else {
                decryptedStream.write(block)
            }
        }
    }

    val decryptedBytes = decryptedStream.toByteArray()
    println("Decrypted chunk size: ${decryptedBytes.size}")
    return decryptedBytes
}

fun generateTrackUrl(trackId: String, md5Origin: String, mediaVersion: String, quality: Int): String {
    val magic = 164
    val step1 = ByteArrayOutputStream()
    step1.write(md5Origin.toByteArray())
    step1.write(164)
    step1.write(quality.toString().toByteArray())
    step1.write(magic)
    step1.write(trackId.toByteArray())
    step1.write(magic)
    step1.write(mediaVersion.toByteArray())

    val md5 = MessageDigest.getInstance("MD5")
    md5.update(step1.toByteArray())
    val digest = md5.digest()
    val md5hex = bytesToHexTrack(digest).lowercase()

    val step2 = ByteArrayOutputStream()
    step2.write(md5hex.toByteArray())
    step2.write(magic)
    step2.write(step1.toByteArray())
    step2.write(magic)

    while (step2.size()%16 > 0) step2.write(46)

    val cipher = Cipher.getInstance("AES/ECB/NoPadding")
    val key = SecretKeySpec("jo6aey6haid2Teih".toByteArray(), "AES")
    cipher.init(Cipher.ENCRYPT_MODE, key)

    val step3 = StringBuilder()
    for (i in 0 until step2.size() / 16) {
        val b = Arrays.copyOfRange(step2.toByteArray(), i*16, (i+1)*16)
        step3.append(bytesToHexTrack(cipher.doFinal(b)).lowercase())
    }

    val url = "https://e-cdns-proxy-" + md5Origin[0] + ".dzcdn.net/mobile/1/" + step3.toString()
    return url
}

private fun bytesToHexTrack(bytes: ByteArray): String {
    val HEX_ARRAY = "0123456789ABCDEF".toCharArray()
    val hexChars = CharArray(bytes.size * 2)
    for (j in bytes.indices) {
        val v = bytes[j].toInt() and 0xFF
        hexChars[j * 2] = HEX_ARRAY[v ushr 4]
        hexChars[j * 2 + 1] = HEX_ARRAY[v and 0x0F]
    }
    return String(hexChars)
}

fun <T> moveElement(array: Array<T>, fromIndex: Int, toIndex: Int): Array<T> {
    if (fromIndex !in array.indices || toIndex !in array.indices) {
        throw IndexOutOfBoundsException("Index out of bounds")
    }

    if (fromIndex == toIndex) {
        return array
    }

    val element = array[fromIndex]
    if (fromIndex < toIndex) {
        // Move elements from fromIndex+1 to toIndex one position to the left
        for (i in fromIndex until toIndex) {
            array[i] = array[i + 1]
        }
    } else {
        // Move elements from toIndex to fromIndex-1 one position to the right
        for (i in fromIndex downTo toIndex + 1) {
            array[i] = array[i - 1]
        }
    }
    array[toIndex] = element

    return array
}