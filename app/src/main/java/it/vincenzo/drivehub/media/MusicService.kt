package it.vincenzo.drivehub.media

import android.content.ContentUris
import android.content.pm.PackageManager
import android.provider.MediaStore
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import it.vincenzo.drivehub.core.Reports

/** Lettore dei brani salvati sul telefono, visibile in Android Auto come app multimediale */
class MusicService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private var songs: List<MediaItem> = emptyList()

    private val root = MediaItem.Builder().setMediaId(ROOT).setMediaMetadata(
        MediaMetadata.Builder().setTitle("DriveHub").setIsBrowsable(true).setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()
    ).build()

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        session = MediaLibrarySession.Builder(this, player, Callback()).build()
        Reports.log("Musica: servizio avviato")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        Reports.log("Musica: servizio chiuso")
        Reports.flush("musica")
        session.release()
        player.release()
        super.onDestroy()
    }

    private fun loadSongs(): List<MediaItem> {
        if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Reports.log("Musica: permesso audio mancante")
            return emptyList()
        }
        val out = mutableListOf<MediaItem>()
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM),
            // niente vocali di WhatsApp/Telegram, registrazioni, suonerie e notifiche: solo musica e podcast
            "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.IS_PODCAST} != 0)" +
                EXCLUDED.joinToString("") { " AND ${MediaStore.Audio.Media.RELATIVE_PATH} NOT LIKE '%$it%'" },
            null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE"
        )?.use { c ->
            while (c.moveToNext() && out.size < 1000) {
                val id = c.getLong(0)
                out += MediaItem.Builder()
                    .setMediaId("song:$id")
                    .setUri(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id))
                    .setMediaMetadata(
                        MediaMetadata.Builder().setTitle(c.getString(1)).setArtist(c.getString(2).takeIf { it != MediaStore.UNKNOWN_STRING })
                            .setAlbumTitle(c.getString(3).takeIf { it != MediaStore.UNKNOWN_STRING })
                            .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()
                    ).build()
            }
        }
        Reports.log("Musica: ${out.size} brani trovati")
        return out
    }

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(root, params))

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId != ROOT) return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
            songs = loadSongs()
            val from = (page * pageSize).coerceAtMost(songs.size)
            val to = (from + pageSize).coerceAtMost(songs.size)
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(songs.subList(from, to)), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = songs.firstOrNull { it.mediaId == mediaId }
                ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        /** Android Auto manda solo l'id: si ricostruisce l'elemento con l'URI; scegliendo un brano parte tutta la lista da lì */
        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (songs.isEmpty()) songs = loadSongs()
            if (mediaItems.size == 1) {
                val i = songs.indexOfFirst { it.mediaId == mediaItems[0].mediaId }
                if (i >= 0) {
                    Reports.log("Musica: riproduco ${songs[i].mediaMetadata.title}")
                    return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(songs, i, C.TIME_UNSET))
                }
            }
            val resolved = mediaItems.mapNotNull { m -> songs.firstOrNull { it.mediaId == m.mediaId } }
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(resolved, 0, C.TIME_UNSET))
        }
    }

    companion object {
        const val ROOT = "root"
        private val EXCLUDED = listOf("WhatsApp", "Telegram", "Recordings", "Ringtones", "Notifications", "Alarms", "Call")
    }
}
