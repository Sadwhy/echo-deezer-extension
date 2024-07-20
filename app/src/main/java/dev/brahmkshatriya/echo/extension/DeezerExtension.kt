package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.SearchClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.exceptions.LoginRequiredException
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.MediaItemsContainer
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Request.Companion.toRequest
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.http.conn.ConnectTimeoutException
import java.util.Locale

class DeezerExtension : ExtensionClient, HomeFeedClient, TrackClient, SearchClient, AlbumClient, ArtistClient,
    PlaylistClient, ShareClient, LoginClient.WebView.Cookie, LoginClient.UsernamePassword, LoginClient.CustomTextInput,
    LibraryClient {

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    override val settingItems: List<Setting> = listOf(
        SettingSwitch(
            "Use FLAC",
            "flac",
            "Use FLAC if available (loading takes longer)",
            false
        ),
        SettingSwitch(
            "Use 128kbps",
            "128",
            "Use 128kbps(overrides FLAC option)",
            false
        ),
    )

    private lateinit var settings: Settings
    override fun setSettings(settings: Settings) {
        this.settings = settings
    }

    private val useFlac
        get() = settings.getBoolean("flac") ?: false

    private val use128
        get() = settings.getBoolean("128") ?: false

    init {
        // Ensure credentials are initialized when the API is first used
        if (DeezerCredentialsHolder.credentials == null) {
            // Example initialization with placeholder values
            DeezerCredentialsHolder.initialize(
                DeezerCredentials(
                    arl = "",
                    sid = "",
                    token = "",
                    userId = "",
                    licenseToken = "",
                    email = "",
                    pass = ""
                )
            )
        }

    }

    private val credentials: DeezerCredentials
        get() = DeezerCredentialsHolder.credentials ?: throw IllegalStateException("DeezerCredentials not initialized")
    private val utils: DeezerUtils
        get() = DeezerUtils

    private val arl: String
        get() = credentials.arl

    private val arlExpired: Boolean
        get() = utils.arlExpired

    private val api = DeezerApi()

    override suspend fun onExtensionSelected() {}

    //<============= HomeTab =============>

    override suspend fun getHomeTabs() = listOf<Tab>()

    override fun getHomeFeed(tab: Tab?): PagedData<MediaItemsContainer> = PagedData.Continuous {
        if(arl == "" || arlExpired) throw loginRequiredException
        val dataList = mutableListOf<MediaItemsContainer>()
        val jObject = api.homePage()
        val resultObject = jObject["results"]!!.jsonObject
        val sections = resultObject["sections"]!!.jsonArray
        val jsonData = json.decodeFromString<JsonArray>(sections.toString())
        val continuation = it
        jsonData.map { section ->
            val id = section.jsonObject["module_id"]!!.jsonPrimitive.content
            val name = section.jsonObject["title"]!!.jsonPrimitive.content
            // Just for the time being until everything is implemented
            if (id == "348128f5-bed6-4ccb-9a37-8e5f5ed08a62" ||
                id == "8d10a320-f130-4dcb-a610-38baf0c57896" ||
                id == "2a7e897f-9bcf-4563-8e11-b93a601766e1" ||
                id == "7a65f4ed-71e1-4b6e-97ba-4de792e4af62" ||
                id == "25f9200f-1ce0-45eb-abdc-02aecf7604b2" ||
                id == "c320c7ad-95f5-4021-8de1-cef16b053b6d" ||
                id == "b2e8249f-8541-479e-ab90-cf4cf5896cbc" ||
                id == "927121fd-ef7b-428e-8214-ae859435e51c") {
                val data = section.toMediaItemsContainer(name = name)
                dataList.add(data)
            }
        }
        Page(dataList, continuation)
    }
    //<============= Library =============>

    private var allTabs: Pair<String, List<MediaItemsContainer>>? = null

    override suspend fun getLibraryTabs(): List<Tab> {
        if (arl.isEmpty() || arlExpired) return emptyList()

        val tabs = listOf(
            Tab("playlists", "Playlists"),
            Tab("albums", "Albums"),
            Tab("tracks", "Tracks"),
            Tab("artists", "Artists")
        )

        allTabs = "all" to tabs.mapNotNull { tab ->
            val jsonObject = when (tab.id) {
                "playlists" -> api.getPlaylists()
                "albums" -> api.getAlbums()
                "tracks" -> api.getTracks()
                "artists" -> api.getArtists()
                else -> null
            } ?: return@mapNotNull null

            val resultObject = jsonObject["results"]?.jsonObject ?: return@mapNotNull null
            val dataArray = when (tab.id) {
                "playlists", "albums", "artists" -> {
                    val tabObject = resultObject["TAB"]?.jsonObject?.get(tab.id)?.jsonObject
                    tabObject?.get("data")?.jsonArray
                }
                "tracks" -> resultObject["data"]?.jsonArray
                else -> return@mapNotNull null
            }

            dataArray?.toMediaItemsContainer(tab.name)
        }

        return listOf(Tab("all", "All")) + tabs
    }

    override fun getLibraryFeed(tab: Tab?) = PagedData.Single {
        if (arl.isEmpty() || arlExpired) throw loginRequiredException

        val tabId = tab?.id ?: "all"
        val list = when (tabId) {
            "all" -> allTabs?.second ?: emptyList()
            "playlists" -> fetchData { api.getPlaylists() }
            "albums" -> fetchData { api.getAlbums() }
            "tracks" -> fetchData { api.getTracks() }
            "artists" -> fetchData { api.getArtists() }
            else -> emptyList()
        }
        list
    }

    private suspend fun fetchData(apiCall: suspend () -> JsonObject): List<MediaItemsContainer> {
        val jsonObject = apiCall()
        val resultObject = jsonObject["results"]?.jsonObject ?: return emptyList()
        val dataArray = when {
            resultObject["data"] != null -> resultObject["data"]!!.jsonArray
            resultObject["TAB"] != null -> resultObject["TAB"]!!.jsonObject.values.firstOrNull()?.jsonObject?.get("data")?.jsonArray
            else -> null
        }

        return dataArray?.mapNotNull { item ->
            item.toEchoMediaItem()?.toMediaItemsContainer()
        } ?: emptyList()
    }

    override suspend fun addTracksToPlaylist(playlist: Playlist, tracks: List<Track>, index: Int, new: List<Track>) {
        if(arl == "" || arlExpired) throw loginRequiredException
        api.addToPlaylist(playlist, new)
    }

    override suspend fun createPlaylist(title: String, description: String?): Playlist {
        if(arl == "" || arlExpired) throw loginRequiredException
        val jsonObject = api.createPlaylist(title, description)
        val id = jsonObject["results"]?.jsonPrimitive?.content ?: ""
        val playlist = Playlist(
            id = id,
            title = title,
            description = description,
            isEditable = true
        )
        return playlist
    }

    override suspend fun deletePlaylist(playlist: Playlist) {
        if(arl == "" || arlExpired) throw loginRequiredException
        api.deletePlaylist(playlist.id)
    }

    override suspend fun editPlaylistMetadata(playlist: Playlist, title: String, description: String?) {
        if(arl == "" || arlExpired) throw loginRequiredException
        api.updatePlaylist(playlist.id, title, description)
    }

    override suspend fun likeTrack(track: Track, liked: Boolean): Boolean {
        if(arl == "" || arlExpired) throw loginRequiredException
        if(liked) {
            api.addFavoriteTrack(track.id)
            return true
        } else {
            api.removeFavoriteTrack(track.id)
            return false
        }
    }

    override suspend fun listEditablePlaylists(): List<Playlist> {
        if(arl == "" || arlExpired) throw loginRequiredException
        val playlistList = mutableListOf<Playlist>()
        val jsonObject = api.getPlaylists()
        val resultObject = jsonObject["results"]!!.jsonObject
        val tabObject = resultObject["TAB"]!!.jsonObject
        val playlistObject = tabObject["playlists"]!!.jsonObject
        val dataArray = playlistObject["data"]!!.jsonArray
        dataArray.map {
            val playlist = it.toPlaylist()
            if(playlist.isEditable) {
                playlistList.add(playlist)
            }

        }
        return playlistList
    }

    override suspend fun moveTrackInPlaylist(playlist: Playlist, tracks: List<Track>, fromIndex: Int, toIndex: Int) {
        if(arl == "" || arlExpired) throw loginRequiredException
        val idArray = tracks.map { it.id }.toTypedArray()
        val newIdArray = moveElement(idArray, fromIndex, toIndex)
        api.updatePlaylistOrder(playlist.id, newIdArray)
    }

    override suspend fun removeTracksFromPlaylist(playlist: Playlist, tracks: List<Track>, indexes: List<Int>) {
        if(arl == "" || arlExpired) throw loginRequiredException
        api.removeFromPlaylist(playlist, tracks, indexes)
    }

    //<============= Search =============>

    override suspend fun quickSearch(query: String?) = query?.run {
        try {
            val jsonObject = api.searchSuggestions(query)
            val resultObject = jsonObject["results"]!!.jsonObject
            val suggestionArray = resultObject["SUGGESTION"]!!.jsonArray
            suggestionArray.map { item ->
                val queryItem = item.jsonObject["QUERY"]!!.jsonPrimitive.content
                QuickSearchItem.SearchQueryItem(queryItem, false)
            }
        } catch (e: NullPointerException) {
            null
        } catch (e: ConnectTimeoutException) {
            null
        }
    } ?: listOf()

    private var oldSearch: Pair<String, List<MediaItemsContainer>>? = null
    override fun searchFeed(query: String?, tab: Tab?) = PagedData.Single {
        if(arl == "" || arlExpired) throw loginRequiredException
        query ?: return@Single emptyList()
        val old = oldSearch?.takeIf {
            it.first == query && (tab == null || tab.id == "All")
        }?.second
        if (old != null) return@Single old

        var list = listOf<MediaItemsContainer>()
        if(tab?.id != "TOP_RESULT") {
            val jsonObject = api.search(query)
            val resultObject = jsonObject["results"]!!.jsonObject
            val tabObject = resultObject[tab?.id]!!.jsonObject
            val dataArray = tabObject["data"]!!.jsonArray

            val itemArray =  dataArray.mapNotNull { item ->
                item.toEchoMediaItem()?.toMediaItemsContainer()
            }
            list = itemArray
        }
        list
    }

    override suspend fun searchTabs(query: String?): List<Tab> {
        if (arl == "" || arlExpired) return emptyList()
        query ?: return emptyList()
        val jsonObject = api.search(query)
        val resultObject = jsonObject["results"]!!.jsonObject
        val orderObject = resultObject["ORDER"]!!.jsonArray

        val tabs = orderObject.mapNotNull {
            val tab = it.jsonPrimitive.content
            Tab(
                id = tab,
                name = tab.lowercase().capitalize(Locale.ROOT)
            )
        }.filter {
            it.id != "TOP_RESULT" &&
            it.id != "FLOW_CONFIG"
        }

        oldSearch = query to tabs.map { tab ->
            val name = tab.id
            val tabObject = resultObject[name]!!.jsonObject
            val dataArray = tabObject["data"]!!.jsonArray
            dataArray.toMediaItemsContainer(name.lowercase().capitalize(
                Locale.ROOT))
        }
        return listOf(Tab("All", "All")) + tabs
    }

    //<============= Play =============>

    private val client = OkHttpClient()

    override suspend fun getStreamableAudio(streamable: Streamable) = getByteStreamAudio(streamable, client)

    override suspend fun getStreamableVideo(streamable: Streamable) = throw Exception("not Used")

    override suspend fun loadTrack(track: Track) = coroutineScope {
        val jsonObject = if (track.extras["FILESIZE_MP3_MISC"] != "0" && track.extras["FILESIZE_MP3_MISC"] != null) {
            api.getMP3MediaUrl(track)
        } else {
            api.getMediaUrl(track, useFlac, use128)
        }

        val key = Utils.createBlowfishKey(track.id)

        suspend fun fetchTrackData(track: Track): JsonObject {
            val trackObject = api.track(arrayOf(track))
            return trackObject["results"]!!.jsonObject["data"]!!.jsonArray[0].jsonObject
        }

        suspend fun generateUrl(trackId: String, md5Origin: String, mediaVersion: String): String {
            var url = generateTrackUrl(trackId, md5Origin, mediaVersion, 1)
            val request = Request.Builder().url(url).build()
            val code = client.newCall(request).execute().code
            if (code == 403) {
                val fallbackObject = fetchTrackData(track)["FALLBACK"]!!.jsonObject
                val backMd5Origin = fallbackObject["MD5_ORIGIN"]?.jsonPrimitive?.content ?: ""
                val backMediaVersion = fallbackObject["MEDIA_VERSION"]?.jsonPrimitive?.content ?: ""
                url = generateTrackUrl(trackId, backMd5Origin, backMediaVersion, 1)
            }
            return url
        }

        val url = when {
            jsonObject.toString().contains("Track token has no sufficient rights on requested media") -> {
                val dataObject = fetchTrackData(track)
                val md5Origin = dataObject["MD5_ORIGIN"]?.jsonPrimitive?.content ?: ""
                val mediaVersion = dataObject["MEDIA_VERSION"]?.jsonPrimitive?.content ?: ""
                generateUrl(track.id, md5Origin, mediaVersion)
            }
            jsonObject["data"]!!.jsonArray.first().jsonObject["media"]?.jsonArray?.isEmpty() == true -> {
                val dataObject = fetchTrackData(track)
                val fallbackObject = dataObject["FALLBACK"]!!.jsonObject
                val backId = fallbackObject["SNG_ID"]?.jsonPrimitive?.content ?: ""
                val newTrack = track.copy(id = backId)
                val newDataObject = fetchTrackData(newTrack)
                val md5Origin = newDataObject["MD5_ORIGIN"]?.jsonPrimitive?.content ?: ""
                val mediaVersion = newDataObject["MEDIA_VERSION"]?.jsonPrimitive?.content ?: ""
                generateUrl(track.id, md5Origin, mediaVersion)
            }
            else -> {
                val dataObject = jsonObject["data"]!!.jsonArray.first().jsonObject
                val mediaObject = dataObject["media"]!!.jsonArray.first().jsonObject
                val sourcesObject = mediaObject["sources"]!!.jsonArray[0]
                sourcesObject.jsonObject["url"]!!.jsonPrimitive.content
            }
        }

        Track(
            id = track.id,
            title = track.title,
            cover = track.cover,
            artists = track.artists,
            audioStreamables = listOf(
                Streamable(
                    id = url,
                    quality = 0,
                    extra = mapOf("key" to key)
                )
            )
        )
    }

    override fun getMediaItems(track: Track): PagedData<MediaItemsContainer> = getMediaItems(track.artists.first())

    //<============= Album =============>

    override fun getMediaItems(album: Album) = getMediaItems(album.artists.first())

    override suspend fun loadAlbum(album: Album): Album {
        val jsonObject = api.album(album)
        val resultsObject = jsonObject["results"]!!.jsonObject
        return resultsObject.toAlbum()
    }

    override fun loadTracks(album: Album): PagedData<Track> = PagedData.Single {
        val jsonObject = api.album(album)
        val resultsObject = jsonObject["results"]!!.jsonObject
        val songsObject = resultsObject["SONGS"]!!.jsonObject
        val dataArray = songsObject["data"]!!.jsonArray
        val data = dataArray.map { song ->
            song.jsonObject.toTrack()
        }
        data
    }

    //<============= Playlist =============>

    override fun getMediaItems(playlist: Playlist) = PagedData.Single {
        val jsonObject = api.playlist(playlist)
        val resultsObject = jsonObject["results"]!!.jsonObject
        val songsObject = resultsObject["SONGS"]!!.jsonObject
        val dataArray = songsObject["data"]!!.jsonArray
        val data = dataArray.map { song ->
            song.jsonObject.toMediaItemsContainer(name = "")
        }
        emptyList<MediaItemsContainer>()
    }

    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        val jsonObject = api.playlist(playlist)
        val resultsObject = jsonObject["results"]!!.jsonObject
        return resultsObject.toPlaylist()
    }

    override fun loadTracks(playlist: Playlist): PagedData<Track> = PagedData.Single {
        val jsonObject = api.playlist(playlist)
        val resultsObject = jsonObject["results"]!!.jsonObject
        val songsObject = resultsObject["SONGS"]!!.jsonObject
        val dataArray = songsObject["data"]!!.jsonArray
        val data = dataArray.map { song ->
            song.jsonObject.toTrack()
        }
        data
    }

    //<============= Artist =============>

    override fun getMediaItems(artist: Artist) = PagedData.Single {
        val dataList = mutableListOf<MediaItemsContainer>()
        val jsonObject = api.artist(artist)

        val resultsObject = jsonObject["results"]!!.jsonObject
        for (result in resultsObject) {
            when (result.key) {
                "TOP" -> {
                    val jObject = resultsObject["TOP"]!!.jsonObject
                    val jArray = jObject["data"]!!.jsonArray
                    val data = jArray.toMediaItemsContainer(name = "Top")
                    dataList.add(data)
                }
                "HIGHLIGHT" -> {
                    val jObject = resultsObject["HIGHLIGHT"]!!.jsonObject
                    val itemObject = jObject["ITEM"]!!.jsonObject
                    val data = itemObject.toMediaItemsContainer(name = "Highlight")
                    dataList.add(data)
                }
                "SELECTED_PLAYLIST" -> {
                    val jObject = resultsObject["SELECTED_PLAYLIST"]!!.jsonObject
                    val jArray = jObject["data"]!!.jsonArray
                    val data = jArray.toMediaItemsContainer(name = "Selected Playlists")
                    dataList.add(data)
                }
                "RELATED_PLAYLIST" -> {
                    val jObject = resultsObject["RELATED_PLAYLIST"]!!.jsonObject
                    val jArray = jObject["data"]!!.jsonArray
                    val data = jArray.toMediaItemsContainer(name = "Related Playlists")
                    dataList.add(data)
                }
                "RELATED_ARTISTS" -> {
                    val jObject = resultsObject["RELATED_ARTISTS"]!!.jsonObject
                    val jArray = jObject["data"]!!.jsonArray
                    val data = jArray.toMediaItemsContainer(name = "Related Artists")
                    dataList.add(data)
                }
                "ALBUMS" -> {
                    val jObject = resultsObject["ALBUMS"]!!.jsonObject
                    val jArray = jObject["data"]!!.jsonArray
                    val data = jArray.toMediaItemsContainer(name = "Albums")
                    dataList.add(data)
                }
            }
            }
        dataList
    }

    override suspend fun loadArtist(artist: Artist): Artist {
        val jsonObject = api.artist(artist)
        val resultsObject = jsonObject["results"]!!.jsonObject["DATA"]!!.jsonObject
        return resultsObject.toArtist()
    }

    //<============= Login =============>

    private val loginRequiredException = LoginRequiredException("deezer", "Deezer", ExtensionType.MUSIC)

    override val loginWebViewInitialUrl = "https://www.deezer.com/login?redirect_type=page&redirect_link=%2Faccount%2F"
        .toRequest(mapOf(Pair("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")))

    override val loginWebViewStopUrlRegex = "https://www\\.deezer\\.com/account/.*".toRegex()

    override suspend fun onLoginWebviewStop(url: String, data: String): List<User> {
        if (data.contains("arl=")) {
            DeezerCredentialsHolder.updateCredentials(arl = data.substringAfter("arl=").substringBefore(";"))
            DeezerCredentialsHolder.updateCredentials(sid = data.substringAfter("sid=").substringBefore(";"))
            val userList = api.makeUser()
            return userList
        } else {
            return emptyList()
        }
    }

    override val loginInputFields: List<LoginClient.InputField>
        get() = listOf(
            LoginClient.InputField(
                key = "arl",
                label = "ARL",
                isRequired = false,
                isPassword = true

            )
        )

    override suspend fun onLogin(data: Map<String, String?>): List<User> {
        DeezerCredentialsHolder.updateCredentials(arl = data["arl"] ?: "")
        api.getSid()
        val userList = api.makeUser()
        return userList
    }

    override suspend fun onLogin(username: String, password: String): List<User> {
        // Set shared credentials
        DeezerCredentialsHolder.updateCredentials(email = username)
        DeezerCredentialsHolder.updateCredentials(pass = password)

        api.getArlByEmail(username, password)
        val userList = api.makeUser(username, password)
        return userList
    }

    override suspend fun onSetLoginUser(user: User?) {
        if (user != null) {
            DeezerCredentialsHolder.updateCredentials(arl = user.extras["arl"] ?: "")
            DeezerCredentialsHolder.updateCredentials(userId = user.extras["user_id"] ?: "")
            DeezerCredentialsHolder.updateCredentials(sid = user.extras["sid"] ?: "")
            DeezerCredentialsHolder.updateCredentials(token = user.extras["token"] ?: "")
            DeezerCredentialsHolder.updateCredentials(licenseToken = user.extras["license_token"] ?: "")
            DeezerCredentialsHolder.updateCredentials(email = user.extras["email"] ?: "")
            DeezerCredentialsHolder.updateCredentials(pass = user.extras["pass"] ?: "")
        }
    }

    //<============= Share =============>

    override suspend fun onShare(album: Album): String = "https://www.deezer.com/album/${album.id}"

    override suspend fun onShare(artist: Artist): String = "https://www.deezer.com/artist/${artist.id}"

    override suspend fun onShare(playlist: Playlist): String = "https://www.deezer.com/playlist/${playlist.id}"

    override suspend fun onShare(track: Track): String = "https://www.deezer.com/track/${track.id}"

    override suspend fun onShare(user: User): String = "https://www.deezer.com/profile/${user.id}"
}