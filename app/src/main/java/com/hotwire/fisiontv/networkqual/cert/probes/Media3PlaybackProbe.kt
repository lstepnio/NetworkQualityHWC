package com.hotwire.fisiontv.networkqual.cert.probes

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.hotwire.fisiontv.networkqual.config.PlaybackPhaseConfig
import com.hotwire.fisiontv.networkqual.cert.probes.PlaybackResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Real DASH playback test driven by Media3/ExoPlayer. Measures startup
 * (time-to-first-frame), peak resolution / bitrate the stream actually
 * achieved on this device, rebuffer count + total stall time, and how many
 * ABR adaptations occurred during the window.
 *
 * Notes for the SDK swap: the Ookla SDK doesn't have a direct equivalent,
 * but anything that gives the same shape of [PlaybackResult] will plug in.
 */
class Media3PlaybackProbe(
    private val context: Context,
    private val cfg: PlaybackPhaseConfig
) : PlaybackProbe {

    override suspend fun run(onProgress: (Float) -> Unit): PlaybackResult =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val player = ExoPlayer.Builder(context).build()
                val handler = Handler(Looper.getMainLooper())
                val startedAtMs = System.currentTimeMillis()

                var firstFrameMs = -1L
                var rebufferCount = 0
                var rebufferStartMs = -1L
                var totalRebufferMs = 0L
                var peakBitrate = 0
                var peakHeight = 0
                var bitrateSwitches = 0
                var lastBitrate = -1
                var finished = false

                fun finish() {
                    if (finished) return
                    finished = true
                    handler.removeCallbacksAndMessages(null)
                    player.release()
                    if (cont.isActive) {
                        val result = PlaybackResult(
                            timeToFirstFrameMs = if (firstFrameMs < 0) Long.MAX_VALUE else firstFrameMs,
                            rebufferCount = rebufferCount,
                            totalRebufferMs = totalRebufferMs,
                            peakBitrateKbps = peakBitrate / 1000,
                            peakHeight = peakHeight,
                            bitrateSwitchCount = bitrateSwitches,
                            playedSec = ((System.currentTimeMillis() - startedAtMs) / 1000).toInt()
                        )
                        Log.i(TAG, "$result")
                        cont.resume(result)
                    }
                }

                val listener = object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_READY -> {
                                if (firstFrameMs < 0) firstFrameMs = System.currentTimeMillis() - startedAtMs
                                if (rebufferStartMs > 0) {
                                    totalRebufferMs += System.currentTimeMillis() - rebufferStartMs
                                    rebufferStartMs = -1L
                                }
                            }
                            Player.STATE_BUFFERING -> {
                                if (firstFrameMs >= 0 && rebufferStartMs < 0) {
                                    rebufferStartMs = System.currentTimeMillis()
                                    rebufferCount++
                                }
                            }
                            Player.STATE_ENDED -> finish()
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        tracks.groups.forEach { group ->
                            for (i in 0 until group.length) {
                                if (!group.isTrackSelected(i)) continue
                                val format = group.getTrackFormat(i)
                                if (format.height > 0) {
                                    if (format.height > peakHeight) peakHeight = format.height
                                    val br = format.bitrate
                                    if (br > 0) {
                                        if (br > peakBitrate) peakBitrate = br
                                        if (lastBitrate >= 0 && br != lastBitrate) bitrateSwitches++
                                        lastBitrate = br
                                    }
                                }
                            }
                        }
                    }
                }

                player.addListener(listener)
                player.setMediaItem(MediaItem.fromUri(cfg.manifestUrl))
                player.prepare()
                player.play()

                val tickIntervalMs = 250L
                val totalDurationMs = cfg.durationSec * 1000L
                val ticker = object : Runnable {
                    override fun run() {
                        val elapsed = System.currentTimeMillis() - startedAtMs
                        onProgress((elapsed.toFloat() / totalDurationMs).coerceIn(0f, 1f))
                        if (elapsed >= totalDurationMs) {
                            finish()
                        } else if (!finished) {
                            handler.postDelayed(this, tickIntervalMs)
                        }
                    }
                }
                handler.postDelayed(ticker, tickIntervalMs)

                cont.invokeOnCancellation {
                    handler.post { finish() }
                }
            }
        }

    companion object {
        private const val TAG = "PlaybackProbe"
    }
}
