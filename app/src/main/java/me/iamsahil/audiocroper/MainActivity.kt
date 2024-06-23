package me.iamsahil.audiocroper

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AudioCropperScreen()
        }
    }
}

@Composable
fun AudioCropperScreen() {
    val context = LocalContext.current
    var startMs by remember { mutableLongStateOf(0L) }
    var endMs by remember { mutableLongStateOf(0L) }
    val inputFile = File(context.cacheDir, "input.aac")
    val outputFile = File(context.cacheDir, "output.aac")
    var samples by remember { mutableStateOf(intArrayOf()) }
    var durationMs by remember { mutableLongStateOf(0L) }


    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            downloadAudioFile("http://64.227.157.121:12345/musicUpload/aac/1696842735884output.aac", inputFile)
            samples = extractAudioSamples(inputFile)
            durationMs = getAudioDuration(inputFile)
            Log.d("AudioCropper","Samples size : ${samples.size}")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        WaveformView(
            samples = samples,
            startMs = startMs,
            endMs = endMs,
            durationMs = durationMs,
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val position = (offset.x / size.width) * durationMs
                            startMs = position.toLong()
                            Log.d("AudioCropper", "Start position set to $startMs ms")
                        },
                        onDrag = { change, _ ->
                            val position = (change.position.x / size.width) * durationMs
                            endMs = position.toLong()
                            Log.d("AudioCropper", "End position updated to $endMs ms")
                        }
                    )
                }
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = {
            cropAudio(inputFile, outputFile, startMs, endMs)
            Log.d("AudioCropper", "Audio cropped from $startMs ms to $endMs ms")
        }) {
            Text("Crop Audio")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
            playAudio(outputFile)
            Log.d("AudioCropper", "Playing cropped audio")
        }) {
            Text("Play Cropped Audio")
        }
    }
}

@Composable
fun WaveformView(samples: IntArray, startMs: Long, endMs: Long, durationMs: Long, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val maxSample = samples.maxOrNull()?.let { abs(it) } ?: 1
        val centerY = canvasHeight / 2

        val grayPaint = Paint().apply { color = Color.Gray }
        val bluePaint = Paint().apply { color = Color.Blue }
        val selectedPaint = Paint().apply { color = Color.Green }

        drawIntoCanvas { canvas ->
            canvas.drawLine(Offset(0f, centerY), Offset(canvasWidth, centerY), grayPaint)

            val scaleX = canvasWidth / samples.size.toFloat()
            val scaleY = canvasHeight / (2 * maxSample.toFloat())

            val startSample = (startMs.toFloat() / durationMs * samples.size).toInt()
            val endSample = (endMs.toFloat() / durationMs * samples.size).toInt()

            samples.forEachIndexed { index, sample ->
                val x = index * scaleX
                val y = (sample / maxSample.toFloat()) * (canvasHeight / 2)
                val paint = if (index in startSample..endSample) selectedPaint else bluePaint
                canvas.drawLine(Offset(x, centerY - y), Offset(x, centerY + y), paint)
            }
        }
    }
}

fun extractAudioSamples(file: File): IntArray {
    val extractor = MediaExtractor()
    val samples = mutableListOf<Int>()
    try {
        extractor.setDataSource(file.absolutePath)
        val format: MediaFormat = extractor.getTrackFormat(0)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return intArrayOf()
        extractor.selectTrack(0)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val bufferInfo = MediaCodec.BufferInfo()
        val inputBuffer = ByteBuffer.allocate(1024 * 4) // Allocate a buffer for reading input samples
        val maxSamplesToProcess = 100000 // Limit the number of samples to process

        var totalSamplesProcessed = 0

        while (extractor.readSampleData(inputBuffer, 0) >= 0 && totalSamplesProcessed < maxSamplesToProcess) {
            val inputBufferIndex = codec.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                val inputCodecBuffer = codec.getInputBuffer(inputBufferIndex)
                inputCodecBuffer?.clear()
                inputCodecBuffer?.put(inputBuffer)
                codec.queueInputBuffer(inputBufferIndex, 0, inputBuffer.position(), extractor.sampleTime, 0)
                extractor.advance()
            }

            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
            if (outputBufferIndex >= 0) {
                val outputCodecBuffer = codec.getOutputBuffer(outputBufferIndex)
                outputCodecBuffer?.let {
                    val shortBuffer = it.asShortBuffer()
                    while (shortBuffer.hasRemaining() && totalSamplesProcessed < maxSamplesToProcess) {
                        val sample = shortBuffer.get().toInt()
                        samples.add(sample)
                        totalSamplesProcessed++
                    }
                }
                codec.releaseOutputBuffer(outputBufferIndex, false)
            }

            inputBuffer.clear()
        }

        codec.stop()
        codec.release()
        extractor.release()

    } catch (e: Exception) {
        Log.e("AudioCropper", "Error extracting audio samples", e)
    } finally {
        extractor.release()
    }

    if (samples.isEmpty()) {
        return intArrayOf()
    }

    
    Log.d("AudioCropper", "First 10 samples: ${samples.take(10)}")


    val maxSample = samples.maxOrNull()?.let { abs(it) } ?: 1
    return samples.map { (it.toFloat() / maxSample * 100).toInt() }.toIntArray()
}


fun getAudioDuration(file: File): Long {
    val mediaPlayer = MediaPlayer()
    return try {
        mediaPlayer.setDataSource(file.absolutePath)
        mediaPlayer.prepare()
        mediaPlayer.duration.toLong()
    } catch (e: Exception) {
        Log.e("AudioCropper", "Error getting audio duration", e)
        0L
    } finally {
        mediaPlayer.release()
    }
}

suspend fun downloadAudioFile(url: String, outputFile: File) {
    withContext(Dispatchers.IO) {
        try {
            Log.d("AudioCropper", "Audio file downloading start")
            val client = OkHttpClient()
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Failed to download file: $response")
                response.body?.let { body ->
                    FileOutputStream(outputFile).use { output ->
                        output.write(body.bytes())
                    }
                }
            }
            Log.d("AudioCropper", "Audio file downloaded to ${outputFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("AudioCropper", "Error downloading audio file", e)
        }
    }
}


@RequiresApi(Build.VERSION_CODES.O)
fun cropAudio(inputFile: File, outputFile: File, startMs: Long, endMs: Long) {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(inputFile.absolutePath)
        val trackIndex = selectAudioTrack(extractor)
        if (trackIndex < 0) {
            Log.e("AudioCropper", "No audio track found in file")
            return
        }
        extractor.selectTrack(trackIndex)

        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return
        val duration = format.getLong(MediaFormat.KEY_DURATION)

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val newTrackIndex = muxer.addTrack(format)
        muxer.start()

        val startTime = startMs * 1000
        val endTime = if (endMs <= duration / 1000) endMs * 1000 else duration

        extractor.seekTo(startTime, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val buffer = ByteBuffer.allocate(1024 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            bufferInfo.offset = 0
            bufferInfo.size = extractor.readSampleData(buffer, 0)
            if (bufferInfo.size < 0) {
                break
            }
            val sampleTime = extractor.sampleTime
            if (sampleTime > endTime) {
                break
            }
            bufferInfo.presentationTimeUs = sampleTime
            bufferInfo.flags = when (extractor.sampleFlags) {
                MediaExtractor.SAMPLE_FLAG_SYNC -> MediaCodec.BUFFER_FLAG_SYNC_FRAME
                MediaExtractor.SAMPLE_FLAG_ENCRYPTED -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME -> MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                else -> 0
            }
            muxer.writeSampleData(newTrackIndex, buffer, bufferInfo)
            extractor.advance()
        }

        muxer.stop()
        muxer.release()
        Log.d("AudioCropper", "Cropped audio saved to ${outputFile.absolutePath}")
    } catch (e: Exception) {
        Log.e("AudioCropper", "Error cropping audio", e)
    } finally {
        extractor.release()
    }
}

fun selectAudioTrack(extractor: MediaExtractor): Int {
    for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("audio/")) {
            return i
        }
    }
    return -1
}


fun playAudio(file: File) {
    try {
        MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            start()
        }
        Log.d("AudioCropper", "Audio playback started")
    } catch (e: Exception) {
        Log.e("AudioCropper", "Error playing audio", e)
    }
}
