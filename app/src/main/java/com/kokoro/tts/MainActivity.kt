package com.kokoro.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kokoro.tts.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var engine: KokoroEngine
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var isPaused  = false
    private var playJob: Job? = null
    private var lastPcm: ShortArray? = null
    private val SAMPLE_RATE = 24000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.tvTitle.text = "Kokoro TTS"
        b.btnGenerate.isEnabled = false
        b.btnSave.isEnabled     = false
        b.btnPlay.isEnabled     = false
        b.btnPause.isEnabled    = false

        b.tvStatus.text = "Loading Kokoro model…"
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                engine = KokoroEngine(applicationContext)
                withContext(Dispatchers.Main) {
                    b.tvStatus.text = "Ready"
                    b.btnGenerate.isEnabled = true
                    b.progress.progress = 100
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    b.tvStatus.text = "Model load failed: ${t.message}"
                }
            }
        }

        b.btnGenerate.setOnClickListener { generate() }
        b.btnSave.setOnClickListener     { saveWav() }
        b.btnPlay.setOnClickListener     { play() }
        b.btnPause.setOnClickListener    { pauseResume() }
        b.etInput.requestFocus()
    }

    private fun generate() {
        val text = b.etInput.text.toString()
        if (text.isBlank()) { Toast.makeText(this, "Enter text", Toast.LENGTH_SHORT).show(); return }
        setBusy(true)
        b.progress.progress = 0
        b.tvStatus.text = "Generating…"

        lifecycleScope.launch {
            try {
                val pcm = withContext(Dispatchers.IO) {
                    engine.synthesize(text) { p -> runOnUiThread { b.progress.progress = p } }
                }
                lastPcm = pcm
                b.tvStatus.text = "Done • ${pcm.size / SAMPLE_RATE}s audio"
                b.btnSave.isEnabled = true
                b.btnPlay.isEnabled = true
                b.progress.progress = 100
            } catch (t: Throwable) {
                b.tvStatus.text = "Error: ${t.message}"
                t.printStackTrace()
            } finally { setBusy(false) }
        }
    }

    private fun setBusy(busy: Boolean) {
        b.btnGenerate.isEnabled = !busy
        b.btnSave.isEnabled     = !busy && lastPcm != null
        b.btnPlay.isEnabled     = !busy && lastPcm != null
        b.etInput.isEnabled     = !busy
    }

    private fun saveWav() {
        val pcm = lastPcm ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: getExternalFilesDir(null)!!
                dir.mkdirs()
                val f = File(dir, "kokoro_${System.currentTimeMillis()}.wav")
                writeWav(f, pcm, SAMPLE_RATE)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Saved: ${f.name}", Toast.LENGTH_LONG).show()
                    b.tvStatus.text = "Saved → ${f.absolutePath}"
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Save failed: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun play() {
        val pcm = lastPcm ?: return
        stopPlayback()
        isPlaying = true; isPaused = false
        b.btnPause.isEnabled = true

        val bufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
        val attr = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val fmt = AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()

        audioTrack = AudioTrack.Builder().setAudioAttributes(attr).setAudioFormat(fmt).setBufferSizeInBytes(bufSize * 2).setTransferMode(AudioTrack.MODE_STREAM).build()
        audioTrack?.play()

        val chunk = 2048
        playJob = lifecycleScope.launch(Dispatchers.IO) {
            var i = 0
            val total = pcm.size
            val buf = ShortArray(chunk)
            while (i < total && isActive) {
                while (isPaused && isActive) { delay(80) }
                val n = minOf(chunk, total - i)
                System.arraycopy(pcm, i, buf, 0, n)
                audioTrack?.write(buf, 0, n)
                i += n
                runOnUiThread { b.progress.progress = (i * 100) / total }
            }
            withContext(Dispatchers.Main) {
                audioTrack?.stop(); audioTrack?.release(); audioTrack = null
                isPlaying = false; b.btnPause.isEnabled = false
                b.progress.progress = 100
            }
        }
    }

    private fun pauseResume() {
        if (!isPlaying) return
        isPaused = !isPaused
        if (isPaused) { audioTrack?.pause(); b.btnPause.text = "Resume" }
        else          { audioTrack?.play();  b.btnPause.text = "Pause"  }
    }

    private fun stopPlayback() {
        playJob?.cancel(); playJob = null
        audioTrack?.let { try { if (it.state != AudioTrack.STATE_UNINITIALIZED) it.stop() } catch (_: Exception){}; it.flush(); it.release() }
        audioTrack = null; isPlaying = false; isPaused = false
        b.btnPause.text = "Pause"
    }

    override fun onDestroy() { stopPlayback(); super.onDestroy() }

    private fun writeWav(out: File, pcm: ShortArray, sr: Int) {
        val dataBytes = pcm.size * 2
        FileOutputStream(out).use { fos ->
            fun w(s: String) { fos.write(s.toByteArray()) }
            fun w32(v: Int) { fos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()) }
            fun w16(v: Int) { fos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()) }
            w("RIFF"); w32(36 + dataBytes); w("WAVE")
            w("fmt "); w32(16); w16(1); w16(1); w32(sr); w32(sr * 2); w16(2); w16(16)
            w("data"); w32(dataBytes)
            val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { bb.putShort(it) }
            fos.write(bb.array())
        }
    }
}