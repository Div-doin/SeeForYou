package com.example.seeforyou.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class Detection(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF
)

class YoloDetector(context: Context) {

    private var interpreter: Interpreter
    private val inputSize = 640

    // Motion detection state
    private var previousBitmap: Bitmap? = null
    private var forceFrameCount = 0
    private val forceEveryNFrames = 5       // force inference every 5 skipped frames
    private val motionThreshold = 15        // pixel difference threshold (0-255)
    private val motionSampleStep = 16       // sample every 16th pixel for speed

    init {
        val options = Interpreter.Options().apply {
            numThreads = 4
        }
        interpreter = Interpreter(loadModelFile(context), options)
    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd("yolov8n_float32.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }

    /**
     * Detects objects with adaptive motion-based frame skipping.
     * Returns null if frame was skipped (use last result).
     * Returns empty list if no objects found.
     * Returns detections if objects found.
     */
    fun detectWithMotion(bitmap: Bitmap, confThreshold: Float = 0.25f): List<Detection>? {
        forceFrameCount++

        val shouldRunInference = when {
            previousBitmap == null -> true                        // first frame — always run
            forceFrameCount >= forceEveryNFrames -> true          // force every N skipped frames
            hasMotion(bitmap, previousBitmap!!) -> true           // scene changed
            else -> false                                          // skip this frame
        }

        if (!shouldRunInference) {
            return null // signal to caller: reuse last result
        }

        forceFrameCount = 0
        previousBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)

        return detect(bitmap, confThreshold)
    }

    /**
     * Checks if there is meaningful motion between two frames.
     * Uses pixel sampling (not every pixel) for speed.
     */
    private fun hasMotion(current: Bitmap, previous: Bitmap): Boolean {
        // Scale both to small size for fast comparison
        val w = minOf(current.width, previous.width)
        val h = minOf(current.height, previous.height)

        var diffSum = 0
        var sampleCount = 0

        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val cp = current.getPixel(x, y)
                val pp = previous.getPixel(x, y)

                val rDiff = Math.abs(((cp shr 16) and 0xFF) - ((pp shr 16) and 0xFF))
                val gDiff = Math.abs(((cp shr 8) and 0xFF) - ((pp shr 8) and 0xFF))
                val bDiff = Math.abs((cp and 0xFF) - (pp and 0xFF))

                val pixelDiff = (rDiff + gDiff + bDiff) / 3
                if (pixelDiff > motionThreshold) diffSum++
                sampleCount++

                x += motionSampleStep
            }
            y += motionSampleStep
        }

        if (sampleCount == 0) return true
        val motionRatio = diffSum.toFloat() / sampleCount.toFloat()
        return motionRatio > 0.05f // more than 5% of sampled pixels changed
    }

    fun detect(bitmap: Bitmap, confThreshold: Float = 0.25f): List<Detection> {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val inputBuffer = bitmapToByteBuffer(resized)

        // YOLOv8 output shape: [1, 84, 8400]
        val outputArray = Array(1) { Array(84) { FloatArray(8400) } }
        interpreter.run(inputBuffer, outputArray)

        return parseOutput(outputArray, bitmap.width, bitmap.height, confThreshold)
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            buffer.putFloat((pixel and 0xFF) / 255.0f)
        }
        return buffer
    }

    private fun parseOutput(
        output: Array<Array<FloatArray>>,
        imgWidth: Int,
        imgHeight: Int,
        confThreshold: Float
    ): List<Detection> {
        val detections = mutableListOf<Detection>()

        for (i in 0 until 8400) {
            var maxConf = confThreshold
            var maxClassIdx = -1

            for (c in 4 until 84) {
                val conf = output[0][c][i]
                if (conf > maxConf) {
                    maxConf = conf
                    maxClassIdx = c - 4
                }
            }

            if (maxClassIdx == -1) continue
            if (maxClassIdx >= COCO_LABELS.size) continue

            val cx = output[0][0][i]
            val cy = output[0][1][i]
            val w  = output[0][2][i]
            val h  = output[0][3][i]

            val left   = ((cx - w / 2) * imgWidth).coerceIn(0f, imgWidth.toFloat())
            val top    = ((cy - h / 2) * imgHeight).coerceIn(0f, imgHeight.toFloat())
            val right  = ((cx + w / 2) * imgWidth).coerceIn(0f, imgWidth.toFloat())
            val bottom = ((cy + h / 2) * imgHeight).coerceIn(0f, imgHeight.toFloat())

            detections.add(
                Detection(
                    label = COCO_LABELS[maxClassIdx],
                    confidence = maxConf,
                    boundingBox = RectF(left, top, right, bottom)
                )
            )
        }

        return nms(detections)
    }

    private fun nms(detections: List<Detection>, iouThreshold: Float = 0.45f): List<Detection> {
        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val result = mutableListOf<Detection>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            result.add(best)
            sorted.removeAll { iou(best.boundingBox, it.boundingBox) > iouThreshold }
        }

        return result.take(10)
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft   = maxOf(a.left, b.left)
        val interTop    = maxOf(a.top, b.top)
        val interRight  = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        val interArea   = maxOf(0f, interRight - interLeft) * maxOf(0f, interBottom - interTop)
        val unionArea   = (a.width() * a.height()) + (b.width() * b.height()) - interArea
        return if (unionArea <= 0f) 0f else interArea / unionArea
    }

    fun close() {
        interpreter.close()
        previousBitmap = null
    }

    companion object {
        val COCO_LABELS = listOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
            "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
            "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
            "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
            "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup",
            "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
            "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
            "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
            "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
            "toothbrush"
        )
    }
}