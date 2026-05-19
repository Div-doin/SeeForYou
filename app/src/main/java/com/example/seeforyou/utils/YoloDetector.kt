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

    fun detect(bitmap: Bitmap, confThreshold: Float = 0.35f): List<Detection> {
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
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)  // G
            buffer.putFloat((pixel and 0xFF) / 255.0f)           // B
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
        val numDetections = output[0][0].size // 8400

        for (i in 0 until numDetections) {
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

    private fun nms(detections: List<Detection>, iouThreshold: Float = 0.5f): List<Detection> {
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