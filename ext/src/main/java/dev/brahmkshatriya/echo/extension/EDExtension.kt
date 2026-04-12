package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.LyricsExtension
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.TrackerClient
import dev.brahmkshatriya.echo.common.models.TrackDetails
import dev.brahmkshatriya.echo.common.providers.LyricsExtensionsProvider
import dev.brahmkshatriya.echo.common.providers.MusicExtensionsProvider

abstract class EDExtension : MusicExtensionsProvider, LyricsExtensionsProvider, TrackerClient {
    override val requiredMusicExtensions = listOf<String>()

    var musicExtensionList: List<MusicExtension> = emptyList()
    override fun setMusicExtensions(extensions: List<MusicExtension>) {
        musicExtensionList = extensions
    }

    override val requiredLyricsExtensions = listOf<String>()

    var lyricsExtensionList: List<LyricsExtension> = emptyList()
    override fun setLyricsExtensions(extensions: List<LyricsExtension>) {
        lyricsExtensionList = extensions
    }

    override suspend fun onTrackChanged(details: TrackDetails?) {}
    override suspend fun onPlayingStateChanged(details: TrackDetails?, isPlaying: Boolean) {}
}