package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.LyricsExtension
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.TrackerExtension
import dev.brahmkshatriya.echo.common.clients.TrackerClient
import dev.brahmkshatriya.echo.common.models.TrackDetails
import dev.brahmkshatriya.echo.common.providers.LyricsExtensionsProvider
import dev.brahmkshatriya.echo.common.providers.MusicExtensionsProvider
import dev.brahmkshatriya.echo.common.providers.TrackerExtensionsProvider
import dev.brahmkshatriya.echo.common.settings.SettingsProvider

abstract class EDExtension : MusicExtensionsProvider, LyricsExtensionsProvider, TrackerExtensionsProvider, TrackerClient, SettingsProvider {
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

    override val requiredTrackerExtensions = listOf<String>()

    var trackerExtensionList: List<TrackerExtension> = emptyList()
    override fun setTrackerExtensions(extensions: List<TrackerExtension>) {
        trackerExtensionList = extensions
    }

    override suspend fun onTrackChanged(details: TrackDetails?) {}
    override suspend fun onPlayingStateChanged(details: TrackDetails?, isPlaying: Boolean) {}
}