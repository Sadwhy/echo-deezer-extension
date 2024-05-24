package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.MediaItemsContainer
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive


fun JsonElement.toMediaItemsContainer(
    api: DeezerApi = DeezerApi(),
    name: String?
): MediaItemsContainer {
    val itemsArray = jsonObject["items"]!!.jsonArray
    return MediaItemsContainer.Category(
        title = name ?: "Unknown",
        list = itemsArray.mapNotNull { item ->
            item.jsonObject.toEchoMediaItem(api)
        }
    )
}
fun JsonObject.toMediaItemsContainer(
    api: DeezerApi = DeezerApi(),
    name: String?
): MediaItemsContainer {
    return MediaItemsContainer.Category(
        title = name ?: "Unknown",
        list = listOf(jsonObject.toEchoMediaItem(api) ?: emptyList<EchoMediaItem>().first())
    )
}

fun JsonArray.toMediaItemsContainer(
    api: DeezerApi = DeezerApi(),
    name: String?
): MediaItemsContainer {
    val itemsArray = jsonArray
    return MediaItemsContainer.Category(
        title = name ?: "Unknown",
        list = itemsArray.mapNotNull { item ->
            item.jsonObject.toEchoMediaItem(api)
        }
    )
}

fun JsonElement.toEchoMediaItem(
    api: DeezerApi
): EchoMediaItem? {
    val data = jsonObject["data"]?.jsonObject ?: jsonObject
    val type = data["__TYPE__"]!!.jsonPrimitive.content
    return when {
        type.contains("playlist") -> EchoMediaItem.Lists.PlaylistItem(toPlaylist(api))
        type.contains("album") -> EchoMediaItem.Lists.AlbumItem(toAlbum())
        type.contains("song") -> EchoMediaItem.TrackItem(toTrack())
        type.contains("artist") -> EchoMediaItem.Profile.ArtistItem(toArtist())
        else -> null
    }
}



fun JsonElement.toAlbum(): Album {
    val data = jsonObject["data"]?.jsonObject ?: jsonObject["DATA"]?.jsonObject ?: jsonObject
    val md5 = data["ALB_PICTURE"]?.jsonPrimitive?.content ?: ""
    return Album(
        id = data["ALB_ID"]?.jsonPrimitive?.content ?: "",
        title = data["ALB_TITLE"]?.jsonPrimitive?.content ?: "",
        cover = getCover(md5, "cover"),
        description = jsonObject["description"]?.jsonPrimitive?.content ?: "",
        subtitle = jsonObject["subtitle"]?.jsonPrimitive?.content ?: "",
    )
}

fun JsonElement.toArtist(): Artist {
    val data = jsonObject["data"]?.jsonObject ?: jsonObject
    val md5 = data["ART_PICTURE"]?.jsonPrimitive?.content ?: ""
    return Artist(
        id = data["ART_ID"]?.jsonPrimitive?.content ?: "",
        name = data["ART_NAME"]?.jsonPrimitive?.content ?: "",
        cover = getCover(md5, "artist"),
        description = jsonObject["description"]?.jsonPrimitive?.content ?: "",
        subtitle = jsonObject["subtitle"]?.jsonPrimitive?.content ?: "",
    )
}

fun JsonElement.toTrack(): Track {
    val data = jsonObject["data"]?.jsonObject ?: jsonObject
    val md5 = data["ALB_PICTURE"]?.jsonPrimitive?.content ?: ""
    return Track(
        id = data["SNG_ID"]!!.jsonPrimitive.content,
        title = data["SNG_TITLE"]!!.jsonPrimitive.content,
        cover = getCover(md5, "cover"),
        extras = mapOf(
            "TRACK_TOKEN" to (data["TRACK_TOKEN"]?.jsonPrimitive?.content ?: ""),
            "FILESIZE_MP3_MISC" to (data["FILESIZE_MP3_MISC"]?.jsonPrimitive?.content ?: "")
        )
    )
}

fun JsonElement.toPlaylist(api: DeezerApi): Playlist {
    val data = jsonObject["data"]?.jsonObject ?: jsonObject["DATA"]?.jsonObject ?: jsonObject
    val type = data["PICTURE_TYPE"]?.jsonPrimitive?.content ?: ""
    val md5 = data["PLAYLIST_PICTURE"]?.jsonPrimitive?.content ?: ""
    return Playlist(
        id = data["PLAYLIST_ID"]?.jsonPrimitive?.content ?: "",
        title = data["TITLE"]?.jsonPrimitive?.content ?: "",
        cover = getCover(md5, type),
        description = data["DESCRIPTION"]?.jsonPrimitive?.content ?: "",
        subtitle = jsonObject["subtitle"]?.jsonPrimitive?.content ?: "",
        isEditable = data["PARENT_USER_ID"]!!.jsonPrimitive.content == api.userId,
        tracks = data["NB_SONG"]?.jsonPrimitive?.int ?: 0,
    )
}

fun getCover(md5: String, type: String): ImageHolder {
    val url = "https://e-cdns-images.dzcdn.net/images/$type/$md5/1200x1200-000000-80-0-0.jpg".toImageHolder()
    return url
}