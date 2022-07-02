package com.example.richtapaudioplayer

import android.content.Context
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.apprichtap.haptic.RichTapPlayer
import com.apprichtap.haptic.RichTapUtils
import com.apprichtap.haptic.sync.SyncCallback
import com.example.richtapaudioplayer.databinding.ActivityMainBinding
import com.xw.repo.BubbleSeekBar
import java.io.*
import java.util.*

data class AudioAsset(val mediaFile: String, val heFile: String)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val mediaList = ArrayList<AudioAsset>()
    private var curAssetIndex = 0

    private val mediaPlayer = MediaPlayer()
    private lateinit var hapticPlayer: RichTapPlayer
    // IMPORTANT: this callback is used for sync between media player and haptic player
    //  If necessary, you can set an offset to do a hard sync with audio player.
    // 重要：下面这个回调用于媒体播放器与触感播放器之间的播放进度同步
    //  必要的时候可以设置一个偏移值，用以校准媒体播放与振动之间的同步：负数表示将振动延迟，整数表示将振动前置。
    private val syncCallback = SyncCallback {
        mediaPlayer.currentPosition + binding.sbPlayerOffset.progress
    }

    private val seekBarUpdateTimer = Timer(true)
    private var seekBarUpdateTask: TimerTask? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // copy .mp3 and .he files from assets folder, as if they are downloaded from Internet
        // 从assets目录释放资源，模拟媒体资源的网络下载
        downloadAssets()

        hapticPlayer = RichTapPlayer.create(this)
        prepareForPlayback()

        // This button is used to switch player status between play and pause
        // 开始播放/暂停 之间的切换
        binding.btnStart.setOnClickListener {
            if (mediaPlayer.isPlaying) {
                mediaPlayer.pause()
                hapticPlayer.pause()
                binding.btnStart.text = "Play"
                seekBarUpdateTask?.cancel()  // Stop updating the progress bar
            } else {
                mediaPlayer.start()
                hapticPlayer.start()
                binding.btnStart.text = "Pause"
                // Start a timer to update the playback progress-bar
                seekBarUpdateTask = PlayerTimerTask()
                seekBarUpdateTimer.schedule(seekBarUpdateTask, 0, 100)
            }
        }

        // This button is used to stop the current playback
        // 停止播放当前的音乐
        binding.btnStop.setOnClickListener {
            seekBarUpdateTask?.cancel()
            seekBarUpdateTimer.purge() // removed all cancelled tasks
            hapticPlayer.stop()  // 先停haptic player
            mediaPlayer.stop()

            prepareForPlayback()  // 准备下一次的播放
        }

        // This button is used to switch to the next music file in the playlist
        // 停止播放当前的音乐
        binding.btnPlayNext.setOnClickListener {
            // Move back to the first one when reaching the end
            // 切换到播放列表中的下一首；如果已是最后一首，则轮回到第一首
            if (++curAssetIndex > 1) curAssetIndex = 0
            binding.btnStop.performClick()

            binding.btnStart.performClick()
        }

        // When the playback finishes, reset all UI control's status
        // 当前播放的音乐自然结束时，重置相关的UI状态
        mediaPlayer.setOnCompletionListener {
            binding.btnStop.performClick()
        }

        // Seeking support during the playback
        // 支持在播放进度条上的拖动
        binding.sbPlayer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                mediaPlayer.run {
                    val newPos = duration * seekBar.progress / 100
                    seekTo(newPos)
                    hapticPlayer.seekTo(newPos)
                }
            }
        })

        // Speed can be be changed at any time during the playback
        // 支持倍速播放
        binding.sbPlayRate.onProgressChangedListener = object : BubbleSeekBar.OnProgressChangedListenerAdapter() {
            override fun getProgressOnActionUp(
                bubbleSeekBar: BubbleSeekBar,
                progress: Int,
                progressFloat: Float
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // Android 6.0
                    mediaPlayer.run {
                        // Setting the speed will automatically start the playback.
                        // So we choose to manually start the playback in order to keep all UI status consistent.
                        // 设置播放速度后会自动开始播放，所以还是自己先启动吧...
                        if (!isPlaying) {
                            binding.btnStart.performClick()
                        }
                        // 设置新的播放速度（Tips：在prepared状态下会自动开播！）
                        playbackParams = playbackParams.setSpeed(progressFloat)
                        hapticPlayer.speed = progressFloat
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        seekBarUpdateTimer.cancel()
        // Remember to stop and release haptic player
        // 记得停止触感播放器，并释放其资源
        hapticPlayer.stop()
        hapticPlayer.release()

        mediaPlayer.stop()
        mediaPlayer.release()
        super.onDestroy()
    }

    private fun prepareForPlayback() {
        val fileToPlay = mediaList[curAssetIndex].mediaFile
        binding.sourceFile.text = "Now Playing: ${fileToPlay}"
        binding.btnStart.text = "Play"
        binding.sbPlayer.progress = 0
        binding.sbPlayRate.setProgress(1.0F) // speed = 1.0 by default
        binding.sbPlayerOffset.setProgress(0F);

        try {
            mediaPlayer.run {
                reset() // call reset prior to setDataSource, otherwise it may crash!
                setDataSource(fileToPlay)
                prepare()
            }

            hapticPlayer.apply {
                reset()
                setDataSource(File(mediaList[curAssetIndex].heFile), 255, 0, syncCallback)
                prepare()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun downloadAssets() {
        dumpAssetToDataStorage("music1.mp3")
        dumpAssetToDataStorage("music1.he")
        dumpAssetToDataStorage("music2.mp3")
        dumpAssetToDataStorage("music2.he")

        mediaList.add(AudioAsset("$filesDir/music1.mp3", "$filesDir/music1.he"))
        mediaList.add(AudioAsset("$filesDir/music2.mp3", "$filesDir/music2.he"))
    }

    private fun dumpAssetToDataStorage(filename: String) {
        try {
            val file = getFileStreamPath(filename)
            if (file.exists()) return

            assets.open(filename, Context.MODE_PRIVATE).use {
                val output = openFileOutput(filename, Context.MODE_PRIVATE)
                it.copyTo(output)
                output.close()
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun updatePlaybackProgress() {
        mediaPlayer.run {
            if (isPlaying) {
                val curPos = 100 * currentPosition / duration
                binding.sbPlayer.progress = curPos
            }
        }
    }

    inner class PlayerTimerTask() : TimerTask() {
        override fun run() {
            updatePlaybackProgress()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    // Make sure we're using the correct version of RichTap SDK
    // 当遇到问题时，首先需要确认我们是否使用了正确版本的 RichTap SDK
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.about -> {
                AlertDialog.Builder(this).apply {
                    setTitle("About...")
                    setMessage("App Version: ${BuildConfig.VERSION_NAME}\n" +
                            "RichTap SDK: ${RichTapUtils.VERSION_NAME}")
                    setCancelable(true)
                    setPositiveButton("OK") { _, _ ->}
                    show()
                }
            }

            R.id.close -> finish()
        }
        return true
    }
}