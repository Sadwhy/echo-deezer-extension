package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

// Settings placeholder
class Settings {
    val deezerLanguage: String = "en"
    val deezerCountry: String = "US"
}

class DeezerApi {

    private val settings = Settings()

    // Access shared email and pass
    private val arl: String
        get() = DeezerCredentials.arl

    private val sid: String
        get() = DeezerCredentials.sid

    private val token: String
        get() = DeezerCredentials.token

    private val userId: String
        get() = DeezerCredentials.userId

    private val licenseToken: String
        get() = DeezerCredentials.licenseToken

    private val email: String
        get() = DeezerCredentials.email

    private val pass: String
        get() = DeezerCredentials.pass

    private val proxy: Boolean
        get() = DeezerUtils.proxy

    private val client: OkHttpClient = OkHttpClient.Builder().apply {
        addInterceptor { chain ->
            val originalResponse = chain.proceed(chain.request())
            if (originalResponse.header("Content-Encoding") == "gzip") {
                val gzipSource = GZIPInputStream(originalResponse.body?.byteStream())
                val decompressedBody = gzipSource.readBytes().toResponseBody(originalResponse.body?.contentType())
                originalResponse.newBuilder().body(decompressedBody).build()
            } else {
                originalResponse
            }
        }
        if(proxy) {
            proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("uk.proxy.murglar.app", 3128)))
        }
    }.build()

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    //Get headers
    private fun getHeaders(method: String? = ""): Headers {
        val headersBuilder = Headers.Builder()
        headersBuilder.add("Accept", "*/*")
        headersBuilder.add("Accept-Charset", "utf-8,ISO-8859-1;q=0.7,*;q=0.3")
        headersBuilder.add("Accept-Encoding", "gzip")
        headersBuilder.add("Accept-Language", settings.deezerLanguage)
        headersBuilder.add("Cache-Control", "max-age=0")
        headersBuilder.add("Connection", "keep-alive")
        headersBuilder.add("Content-Language", "${settings.deezerLanguage}-${settings.deezerCountry}")
        headersBuilder.add("Content-Type", "application/json; charset=utf-8")
        if (method != "user.getArl") {
            headersBuilder.add("Cookie", "arl=$arl; sid=$sid")
        } else {
            headersBuilder.add("Cookie", "sid=$sid")
        }
        headersBuilder.add("Host", "www.deezer.com")
        headersBuilder.add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")
        return headersBuilder.build()
    }

    private suspend fun callApi(method: String, params: Map<*,*>? = null, gatewayInput: String? = ""): String = withContext(Dispatchers.IO) {
        // Generate URL
        val urlBuilder = HttpUrl.Builder()
            .scheme("https")
            .host("www.deezer.com")
            .addPathSegment("ajax")
            .addPathSegment("gw-light.php")
            .addQueryParameter("method", method)
            .addQueryParameter("input", "3")
            .addQueryParameter("api_version", "1.0")
            .addQueryParameter("api_token", token)

        // Conditionally add gateway_input if it's not empty
        if (!gatewayInput.isNullOrEmpty()) {
            urlBuilder.addQueryParameter("gateway_input", gatewayInput)
        }


        val url = urlBuilder.build()

        // Create request body
        val requestBody = JSONObject(params ?: emptyMap<String, Any>()).toString()
            .toRequestBody()

        // Create request
        val requestBuilder = Request.Builder()
        requestBuilder.url(url)
        if (method != "user.getArl") {
            requestBuilder.post(requestBody)
        } else {
            requestBuilder.get()
        }
        requestBuilder.headers(getHeaders(method))
        val request = requestBuilder.build()

        // Execute request
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()
        val body = responseBody.toString()

        if (method == "deezer.getUserData") {
            response.headers.forEach {
                if (it.second.startsWith("sid=")) {
                    DeezerCredentials.sid = it.second.substringAfter("sid=").substringBefore(";")
                }
            }
        }

        if(body.contains("\"VALID_TOKEN_REQUIRED\":\"Invalid CSRF token\"")) {
            if(email == "" && pass == "") {
                DeezerUtils.arl_expired = true
                throw Exception("Please re-login (Best use User + Pass method)")
            } else {
                DeezerUtils.arl_expired = false
                val userList = DeezerExtension().onLogin(email, pass)
                DeezerExtension().onSetLoginUser(userList.first())
                return@withContext callApi(method, params, gatewayInput)
            }
        }

        body
    }

    suspend fun makeUser(email: String = "", pass: String = ""): List<User> {
        val userList = mutableListOf<User>()
        val jsonData = callApi("deezer.getUserData")
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val userResults = jObject["results"]
        val userObject = userResults!!.jsonObject["USER"]
        val token = userResults.jsonObject["checkForm"]!!.jsonPrimitive.content
        val userId = userObject!!.jsonObject["USER_ID"]!!.jsonPrimitive.content
        val licenseToken = userObject.jsonObject["OPTIONS"]!!.jsonObject["license_token"]!!.jsonPrimitive.content
        val name = userObject.jsonObject["BLOG_NAME"]!!.jsonPrimitive.content
        val cover = userObject.jsonObject["USER_PICTURE"]!!.jsonPrimitive.content
        val user = User(
            id = userId,
            name = name,
            cover = "https://e-cdns-images.dzcdn.net/images/user/$cover/100x100-000000-80-0-0.jpg".toImageHolder(),
            extras = mapOf(
                "arl" to arl,
                "user_id" to userId,
                "sid" to sid,
                "token" to token,
                "license_token" to licenseToken,
                "email" to email,
                "pass" to pass
            )
        )
        userList.add(user)
        return userList
    }

    suspend fun getArlByEmail(mail: String, password: String) {
        //Get SID
        getSid()

        val clientId = "447462"
        val clientSecret = "a83bf7f38ad2f137e444727cfc3775cf"
        val md5Password = md5(password)

        val params = mapOf(
            "app_id" to clientId,
            "login" to mail,
            "password" to md5Password,
            "hash" to md5(clientId + mail + md5Password + clientSecret)
        )

        //Get access token
        val responseJson = getToken(params)
        val apiResponse = json.decodeFromString<JsonObject>(responseJson)
        DeezerCredentials.token = apiResponse.jsonObject["access_token"]!!.jsonPrimitive.content

        // Get ARL
        val arlResponse = callApi("user.getArl")
        val arlObject = json.decodeFromString<JsonObject>(arlResponse)
        DeezerCredentials.arl = arlObject["results"]!!.jsonPrimitive.content
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return BigInteger(1, digest).toString(16).padStart(32, '0')
    }

    private fun getToken(params: Map<String, String> = emptyMap()): String {
        val url = "https://connect.deezer.com/oauth/user_auth.php"
        val httpUrl = url.toHttpUrlOrNull()!!.newBuilder().apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()

        val request = Request.Builder()
            .url(httpUrl)
            .get()
            .headers(
                Headers.headersOf(
                    "Cookie", "sid=$sid",
                    "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
                )
            )
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Unexpected code $response")
            return response.body?.string() ?: throw Exception("Empty response body")
        }
    }

      fun getSid() {
        //Get SID
        val url = "https://www.deezer.com/"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).execute()
        response.headers.forEach {
            if (it.second.startsWith("sid=")) {
               DeezerCredentials.sid = it.second.substringAfter("sid=").substringBefore(";")
            }
        }
     }

    suspend fun getMP3MediaUrl(track: Track): JsonObject = withContext(Dispatchers.IO) {
        val headersBuilder = Headers.Builder()
        headersBuilder.add("Accept-Encoding", "gzip")
        headersBuilder.add("Accept-Language", settings.deezerLanguage)
        headersBuilder.add("Cache-Control", "max-age=0")
        headersBuilder.add("Connection", "Keep-alive")
        headersBuilder.add("Content-Type", "application/json; charset=utf-8")
        headersBuilder.add("Cookie", "arl=$arl&sid=$sid")
        headersBuilder.add("Host", "media.deezer.com")
        headersBuilder.add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")
        val headers = headersBuilder.build()

        val urlBuilder = HttpUrl.Builder()
            .scheme("https")
            .host("media.deezer.com")
            .addPathSegment("v1")
            .addPathSegment("get_url")

        val url = urlBuilder.build()

        // Create request body
        val requestBody =
        JSONObject(
        mapOf(
            "license_token" to licenseToken,
            "media" to arrayOf(
                mapOf(
                    "type" to "FULL",
                    "formats" to arrayOf(
                        mapOf(
                            "cipher" to "BF_CBC_STRIPE",
                            "format" to "MP3_MISC"
                        )
                    )
                )
            ),
            "track_tokens" to arrayOf(track.extras["TRACK_TOKEN"])
        )
        ).toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        // Create request
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .headers(headers)
            .build()

        // Execute request
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()
        val body = responseBody.toString()

        val jObject = json.decodeFromString<JsonObject>(body)

        jObject
    }

    suspend fun getMediaUrl(track: Track, useFlac: Boolean, use128: Boolean): JsonObject = withContext(Dispatchers.IO) {
        val urlBuilder = HttpUrl.Builder()
            .scheme("https")
            .host("dzmedia.fly.dev")
            .addPathSegment("get_url")

        val url = urlBuilder.build()

        // Create request body
        val requestBody = JSONObject(mapOf(
            if(use128) {
                "formats" to arrayOf("MP3_128", "MP3_64", "MP3_MISC")
            } else {
                if (useFlac) {
                    "formats" to arrayOf("FLAC", "MP3_320", "MP3_128", "MP3_64", "MP3_MISC")
                } else {
                    "formats" to arrayOf("MP3_320", "MP3_128", "MP3_64", "MP3_MISC")
                }
            },
            "ids" to arrayOf(track.id.toLong())
        )).toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        // Create request
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        // Execute request
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()
        val body = responseBody.toString()

        val jObject = json.decodeFromString<JsonObject>(body)

        jObject
    }

    suspend fun search(query: String): JsonObject {
        val jsonData = callApi(
            method = "deezer.pageSearch",
            params = mapOf(
                "nb" to 128,
                "query" to query,
                "start" to 0
            )
        )
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        return jObject
    }

    suspend fun searchSuggestions(query: String): JsonObject {
        val jsonData = callApi(
            method = "search_getSuggestedQueries",
            params = mapOf(
                "QUERY" to query
            )
        )
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        return jObject
    }

    suspend fun track(tracks: Array<Track>): JsonObject {
        val jsonData = callApi(
            method = "song.getListData",
            params = mapOf(
                "sng_ids" to tracks.map { it.id }
            )
        )
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        return jObject
    }

    //Get favorite tracks
    suspend fun getTracks(): JsonObject {
        val jsonData = callApi(
            method = "favorite_song.getList",
            params = mapOf(
                "user_id" to userId,
                "tab" to "loved",
                "nb" to 50,
                "start" to 0
            )
        )
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        return jObject
    }

    suspend fun addFavoriteTrack(id: String) {
        callApi(
            method = "favorite_song.add",
            params = mapOf(
                "SNG_ID" to id
            )
        )
    }

    suspend fun removeFavoriteTrack(id: String) {
        callApi(
            method = "favorite_song.remove",
            params = mapOf(
                "SNG_ID" to id
            )
        )
    }

    suspend fun artist(artist: Artist): JsonObject {
        val jsonData = callApi(
            method = "deezer.pageArtist",
            params = mapOf(
                "art_id" to artist.id,
                "lang" to settings.deezerLanguage
            )
        )
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        return jObject
    }

    //Get favorite artists
    suspend fun getArtists(): JsonObject {
        val jsonData = callApi(
            method = "deezer.pageProfile",
            params = mapOf(
                "nb" to 40,
                "tab" to "artists",
                "user_id" to userId
            )
        )
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        return jObject
    }

    suspend fun album(album: Album): JsonObject {
        val jsonData = callApi(
            method = "deezer.pageAlbum",
            params = mapOf(
                "alb_id" to album.id,
                "header" to true,
                "lang" to settings.deezerLanguage
            )
        )
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        return jObject
    }

    //Get favorite albums
    suspend fun getAlbums(): JsonObject {
        val jsonData = callApi(
            method = "deezer.pageProfile",
            params = mapOf(
                "user_id" to userId,
                "tab" to "albums",
                "nb" to 50
            )
        )
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        return jObject
    }

    suspend fun playlist(playlist: Playlist): JsonObject {
        val jsonData = callApi(
            method = "deezer.pagePlaylist",
            params = mapOf(
                "playlist_id" to playlist.id,
                "lang" to settings.deezerLanguage,
                "nb" to playlist.tracks,
                "tags" to true,
                "start" to 0
            )
        )
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        return jObject
    }


    //Get users playlists
    suspend fun getPlaylists(): JsonObject {
        val jsonData = callApi(
            method = "deezer.pageProfile",
            params = mapOf(
                "user_id" to userId,
                "tab" to "playlists",
                "nb" to 100
            )
        )
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        return jObject
    }

    suspend fun addToPlaylist(playlist: Playlist, tracks: List<Track>) {
       callApi(
            method = "playlist.addSongs",
            params = mapOf(
                "playlist_id" to playlist.id,
                "songs" to arrayOf(tracks.map { it.id } + 0)
            )
       )
    }

    suspend fun removeFromPlaylist(playlist: Playlist, tracks: List<Track> , indexes: List<Int>) {
        val trackIds = tracks.map { it.id }
        val ids = indexes.map { index -> trackIds[index] }
        callApi(
            method = "playlist.deleteSongs",
            params = mapOf(
                "playlist_id" to playlist.id,
                "songs" to arrayOf(ids + 0)
            )
        )
    }

    suspend fun createPlaylist(title: String, description: String? = ""): JsonObject {
        val jsonData = callApi(
            method = "playlist.create",
            params = mapOf(
                "title" to title,
                "description" to description,
                "songs" to emptyArray<String>(),
                "status" to 0
            )
        )
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        return jObject
    }

    suspend fun deletePlaylist(id: String) {
        callApi(
            method = "playlist.delete",
            params = mapOf(
                "playlist_id" to id
            )
        )
    }

    //Update playlist metadata, status = see createPlaylist
    suspend fun updatePlaylist(id: String, title: String, description: String? = "") {
        callApi(
            method = "playlist.update",
            params = mapOf(
                "description" to description,
                "playlist_id" to id,
                "status" to 0,
                "title" to title
            )
        )
    }

    suspend fun updatePlaylistOrder(playlistId: String, ids: Array<String>) {
        callApi(
            method = "playlist.updateOrder",
            params = mapOf(
                "order" to ids,
                "playlist_id" to playlistId,
                "position" to 0,
            )
        )
    }


    suspend fun homePage(): JsonObject {
        val jsonData = callApi(
            method = "page.get",
            gatewayInput = """
                {"PAGE":"home","VERSION":"2.5","SUPPORT":{"ads":[],"deeplink-list":["deeplink"],"event-card":["live-event"],"grid-preview-one":["album","artist","artistLineUp","channel","livestream","flow","playlist","radio","show","smarttracklist","track","user","video-link","external-link"],"grid-preview-two":["album","artist","artistLineUp","channel","livestream","flow","playlist","radio","show","smarttracklist","track","user","video-link","external-link"],"grid":["album","artist","artistLineUp","channel","livestream","flow","playlist","radio","show","smarttracklist","track","user","video-link","external-link"],"horizontal-grid":["album","artist","artistLineUp","channel","livestream","flow","playlist","radio","show","smarttracklist","track","user","video-link","external-link"],"horizontal-list":["track","song"],"item-highlight":["radio"],"large-card":["album","external-link","playlist","show","video-link"],"list":["episode"],"mini-banner":["external-link"],"slideshow":["album","artist","channel","external-link","flow","livestream","playlist","show","smarttracklist","user","video-link"],"small-horizontal-grid":["flow"],"long-card-horizontal-grid":["album","artist","artistLineUp","channel","livestream","flow","playlist","radio","show","smarttracklist","track","user","video-link","external-link"],"filterable-grid":["flow"]},"LANG":"en","OPTIONS":["deeplink_newsandentertainment","deeplink_subscribeoffer"]}
            """.trimIndent()
        )
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        return jObject
    }
}