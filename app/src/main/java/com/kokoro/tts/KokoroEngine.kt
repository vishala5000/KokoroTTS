package com.kokoro.tts

import android.content.Context
import android.content.res.AssetFileDescriptor
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class KokoroEngine(ctx: Context) {
    private val interp: Interpreter
    private val inShape: IntArray
    private val outShape: IntArray

    init {
        val modelBuf = loadAsset(ctx, "kokoro_int8_fp16.litertlm")
        val opts = Interpreter.Options().apply {
            setNumThreads(Runtime.getRuntime().availableProcessors().coerceAtLeast(4))
            try { setUseNNAPI(true) } catch (_: Throwable) {}
        }
        interp = Interpreter(modelBuf, opts)
        inShape = interp.getInputTensor(0).shape()
        outShape = interp.getOutputTensor(0).shape()
    }

    fun synthesize(text: String, onProgress: (Int) -> Unit): ShortArray {
        val chunks = splitText(text, 300)
        val all = ArrayList<Short>()
        chunks.forEachIndexed { idx, chunk ->
            all.addAll(runChunk(chunk).toList())
            onProgress(((idx + 1) * 100) / chunks.size)
        }
        // FIXED: Use built-in toShortArray() instead of invalid toByteArray()
        return all.toShortArray()
    }

    private fun runChunk(text: String): ShortArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val maxLen = inShape.lastOrNull() ?: 512
        val padded = IntArray(maxLen)
        val n = minOf(bytes.size, maxLen)
        for (i in 0 until n) padded[i] = bytes[i].toInt() and 0xFF

        val inBuf = ByteBuffer.allocateDirect(inShape.fold(4) { a, v -> a * v } * 4).order(ByteOrder.nativeOrder())
        padded.forEach { inBuf.putInt(it) }
        inBuf.rewind()

        val outSize = outShape.fold(1) { a, v -> a * v }
        val outBuf = ByteBuffer.allocateDirect(outSize * 4).order(ByteOrder.nativeOrder())

        interp.run(inBuf, outBuf)
        outBuf.rewind()

        val pcm = ShortArray(outSize)
        for (i in 0 until outSize) {
            val v = outBuf.float.coerceIn(-1f, 1f)
            pcm[i] = (v * 32767f).toInt().toShort()
        }
        return pcm
    }

    private fun splitText(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            var end = minOf(i + maxChars, text.length)
            if (end < text.length) {
                val slice = text.substring(i, end)
                val lastBreak = maxOf(slice.lastIndexOf('.'), slice.lastIndexOf('!'), slice.lastIndexOf('?'), slice.lastIndexOf('\n'))
                if (lastBreak > 0) end = i + lastBreak + 1
            }
            out.add(text.substring(i, end))
            i = end
        }
        return out
    }

    private fun loadAsset(ctx: Context, name: String): MappedByteBuffer {
        ctx.assets.openFd(name).use { afd ->
            FileInputStream(afd.fileDescriptor).use { fis ->
                return fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }
    }
}