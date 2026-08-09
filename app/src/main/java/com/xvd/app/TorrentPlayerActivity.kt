package com.xvd.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TorrentPlayerActivity : AppCompatActivity() {

    private lateinit var infoHash: String
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_torrent_player)

        val hash = intent.getStringExtra(EXTRA_INFO_HASH)
        if (hash.isNullOrBlank()) {
            finish()
            return
        }
        infoHash = hash

        val tvProgress = findViewById<TextView>(R.id.tvPlayerProgress)
        findViewById<MaterialButton>(R.id.btnPlayerBack).setOnClickListener { finish() }

        TorrentManager.init(this)
        ensureService()
        TorrentManager.resume(infoHash)

        val sourceFactory = ProgressiveMediaSource.Factory(TorrentStreamSource.Factory(infoHash))
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(sourceFactory)
            .build()
        player = exo
        findViewById<PlayerView>(R.id.playerView).player = exo
        exo.setMediaItem(MediaItem.fromUri("torrent://$infoHash"))
        exo.playWhenReady = true
        exo.prepare()
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                tvProgress.text = "播放出错：${error.errorCodeName} ${error.message ?: ""}"
            }
        })

        lifecycleScope.launch {
            while (isActive) {
                val item = TorrentManager.torrents.value.firstOrNull { it.infoHash == infoHash }
                if (item != null) {
                    val pct = item.progressPercent()
                    tvProgress.text = if (item.isFinished) {
                        "已完成 $pct%，本地播放中"
                    } else {
                        "边下边播 已完成 $pct%"
                    }
                }
                delay(1000)
            }
        }
    }

    private fun ensureService() {
        val intent = Intent(this, TorrentService::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_INFO_HASH = "infoHash"
    }
}
