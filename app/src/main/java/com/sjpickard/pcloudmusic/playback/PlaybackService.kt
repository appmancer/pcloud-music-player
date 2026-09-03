package com.sjpickard.pcloudmusic.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Owns the ExoPlayer instance and exposes it via MediaSession, so lock
 * screen, Bluetooth, and Android Auto controls work without extra plumbing.
 * Carried over unchanged from the Big Finish player - see that project's
 * PlaybackService for the full reasoning (audio focus, becoming-noisy
 * handling). An album's tracks are handed to the player as one playlist (see
 * PlayerController.play) so track-to-track advance is native ExoPlayer
 * behaviour; unlike Big Finish, playback simply stops when that playlist
 * ends (see PlayerController's doc comment - "album only" queuing).
 */
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = false
            }
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onDestroy() {
        mediaSession.release()
        player.release()
        super.onDestroy()
    }
}
