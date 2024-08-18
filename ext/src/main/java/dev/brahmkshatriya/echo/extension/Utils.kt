package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.StreamableAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import okhttp3.internal.http2.StreamResetException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Arrays
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
        return thisTrackCipher.doFinal(chunk)
    }

    fun getContentLength(url: String, client: OkHttpClient): Long {
        var totalLength = 0L
        val request = Request.Builder().url(url).head().build()
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

@Suppress("NewApi")
@OptIn(ExperimentalCoroutinesApi::class)
fun getByteStreamAudio(scope: CoroutineScope, streamable: Streamable, client: OkHttpClient): StreamableAudio {
    val url = streamable.id
    val contentLength = Utils.getContentLength(url, client)
    val key = streamable.extra["key"]!!

    val request = Request.Builder().url(url).build()
    val pipedInputStream = PipedInputStream()
    val pipedOutputStream = PipedOutputStream(pipedInputStream)

    val clientWithTimeouts = client.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    scope.launch(Dispatchers.IO) {
        retry(3) {
            val response = clientWithTimeouts.newCall(request).executeAsync()
            val byteStream = response.body.byteStream().buffered()

            try {
                val buffer = ByteArray(2048)
                var totalRead: Int
                var counter = 0

                while (true) {
                    totalRead = 0
                    while (totalRead < 2048) {
                        val bytesRead = byteStream.read(buffer, totalRead, 2048 - totalRead)
                        if (bytesRead == -1) break

                        totalRead += bytesRead
                    }

                    if (totalRead == 0) break

                    if (totalRead == 2048) {
                        if (counter % 3 == 0) {
                            val decryptedChunk = Utils.decryptBlowfish(buffer, key)
                            pipedOutputStream.write(decryptedChunk)
                        } else {
                            pipedOutputStream.write(buffer, 0, 2048)
                        }
                    } else {
                        if (counter % 3 == 0) {
                            val partialBuffer = buffer.copyOf(totalRead)
                            val decryptedChunk = Utils.decryptBlowfish(partialBuffer, key)
                            pipedOutputStream.write(decryptedChunk, 0, totalRead)
                        } else {
                            pipedOutputStream.write(buffer, 0, totalRead)
                        }
                    }

                    counter++
                    pipedOutputStream.flush()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    response.close()
                    byteStream.close()
                    pipedOutputStream.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }

    return StreamableAudio.ByteStreamAudio(
        stream = pipedInputStream,
        totalBytes = contentLength
    )
}

suspend fun retry(times: Int, block: suspend () -> Unit) {
    repeat(times) {
        try {
            block()
            return
        } catch (e: StreamResetException) {
            if (it == times - 1) {
                throw e
            }
            delay(1000)
        }
    }
}

@Suppress("NewApi")
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