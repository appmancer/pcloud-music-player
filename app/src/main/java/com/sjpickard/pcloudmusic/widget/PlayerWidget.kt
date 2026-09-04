package com.sjpickard.pcloudmusic.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.sjpickard.pcloudmusic.PcloudMusicApplication
import com.sjpickard.pcloudmusic.data.TrackEntity
import com.sjpickard.pcloudmusic.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val COVER_SIZE_DP = 56
private const val COVER_DECODE_PX = 160 // downsampled well past the display size - these can be multi-MB source files (see RemoteLibraryScanner)

/** Home-screen widget: cover art, title/artist, prev/play-pause/next - the
 * same "now playing" state PlayerController already exposes, just rendered
 * outside the app. Written with Glance rather than classic RemoteViews/View-
 * XML to match the rest of this app's all-Compose UI; PcloudMusicApplication
 * calls [updateAll] (via WidgetUpdater) whenever the underlying state
 * changes, which re-invokes [provideGlance] to read fresh values - no
 * separate state store of our own needed. */
class PlayerWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as PcloudMusicApplication
        val track = app.playerController.nowPlaying.value
        val isPlaying = app.playerController.isPlaying.value
        val coverBitmap = track?.coverPath?.let { loadDownsampledBitmap(it) }

        provideContent {
            WidgetContent(track, isPlaying, coverBitmap)
        }
    }

    private suspend fun loadDownsampledBitmap(path: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sampleSize = 1
            while (bounds.outWidth / (sampleSize * 2) >= COVER_DECODE_PX && bounds.outHeight / (sampleSize * 2) >= COVER_DECODE_PX) {
                sampleSize *= 2
            }
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        } catch (e: Exception) {
            null
        }
    }
}

@androidx.compose.runtime.Composable
private fun WidgetContent(track: TrackEntity?, isPlaying: Boolean, coverBitmap: Bitmap?) {
    val context = androidx.glance.LocalContext.current
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(Color(0xFFFFFBFE)))
            .padding(8.dp)
            .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Image(
            provider = if (coverBitmap != null) ImageProvider(coverBitmap) else ImageProvider(android.R.drawable.ic_media_play),
            contentDescription = null,
            modifier = GlanceModifier.size(COVER_SIZE_DP.dp).cornerRadius(6.dp),
        )
        Column(modifier = GlanceModifier.defaultWeight().padding(horizontal = 8.dp)) {
            Text(
                text = track?.title ?: "Nothing playing",
                maxLines = 1,
                style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
            )
            if (track?.performerArtist != null) {
                Text(
                    text = track.performerArtist,
                    maxLines = 1,
                    style = TextStyle(fontSize = 12.sp, color = ColorProvider(Color(0xFF49454F))),
                )
            }
        }
        Image(
            provider = ImageProvider(android.R.drawable.ic_media_previous),
            contentDescription = "Previous",
            modifier = GlanceModifier.size(32.dp).clickable(actionRunCallback<SkipPreviousAction>()),
        )
        Image(
            provider = ImageProvider(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
            contentDescription = if (isPlaying) "Pause" else "Play",
            modifier = GlanceModifier.size(32.dp).clickable(actionRunCallback<TogglePlayPauseAction>()),
        )
        Image(
            provider = ImageProvider(android.R.drawable.ic_media_next),
            contentDescription = "Next",
            modifier = GlanceModifier.size(32.dp).clickable(actionRunCallback<SkipNextAction>()),
        )
    }
}
