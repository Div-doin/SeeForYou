package com.example.seeforyou.utils
import com.example.seeforyou.localization.PhraseBook

/** Speech policy independent of Android, so noisy camera sequences can be tested. */
class QuietScenePolicy(private val phrases: PhraseBook = PhraseBook()) {
    data class ObjectInView(val label: String, val confidence: Float, val centerX: Float, val height: Float)
    private data class Track(var item: ObjectInView, var frames: Int, var seenAt: Long,
        var announcedHeight: Float = 0f)
    private val tracks = mutableMapOf<String, Track>()
    private var frameAt = Long.MIN_VALUE
    private var lastAlertAt: Long? = null
    private val obstacleLabels = setOf("person", "car", "truck", "bus", "motorcycle", "bicycle",
        "chair", "dining table", "couch", "bench")

    @Synchronized fun observe(objects: List<ObjectInView>, now: Long) {
        frameAt = now
        tracks.entries.removeAll { now - it.value.seenAt >= 5000 }
        val seen = objects.filter { it.confidence >= 0.65f && it.height > 0 && it.height <= 1 && it.centerX in 0f..1f }
            .groupBy { it.label }.mapValues { (_, items) -> items.maxBy { it.confidence } }
        tracks.forEach { (label, track) -> if (label !in seen) track.frames = 0 }
        seen.forEach { (label, item) ->
            val old = tracks[label]
            if (old == null) tracks[label] = Track(item, 1, now)
            else {
                val stablePosition = kotlin.math.abs(old.item.centerX - item.centerX) < 0.15f
                val stableSize = kotlin.math.abs(old.item.height - item.height) <= maxOf(0.05f, old.item.height * 0.2f)
                old.frames = if (stablePosition && stableSize && now - old.seenAt < 2000) old.frames + 1 else 1
                old.item = item
                old.seenAt = now
            }
        }
    }

    @Synchronized fun description(now: Long): String {
        if (frameAt == Long.MIN_VALUE || now - frameAt > 3000) return phrases.text("scene_not_ready")
        val items = tracks.values.filter { it.frames >= 3 && it.seenAt == frameAt }
            .sortedByDescending { it.item.confidence }.take(3).sortedBy { it.item.centerX }
        if (items.isEmpty()) return phrases.text("scene_unknown")
        return phrases.text("scene_intro", items.joinToString(", ") {
            val position = when {
                it.item.centerX < 0.35f -> phrases.text("left")
                it.item.centerX > 0.65f -> phrases.text("right")
                else -> phrases.text("center")
            }
            phrases.text("object_position", phrases.objectName(it.item.label), position)
        })
    }

    @Synchronized fun navigationAlert(now: Long): String? {
        if (frameAt == Long.MIN_VALUE || now - frameAt > 3000) return null
        if (lastAlertAt?.let { now - it < 6000 } == true) return null
        val track = tracks.values.filter {
            val item = it.item
            it.frames >= 3 && it.seenAt == frameAt && item.label in obstacleLabels &&
                item.centerX in 0.35f..0.65f && item.height >= 0.40f &&
                (it.announcedHeight == 0f || item.height >= it.announcedHeight * 1.35f)
        }.maxByOrNull { it.item.height } ?: return null
        track.announcedHeight = track.item.height
        lastAlertAt = now
        return phrases.text("obstacle_alert", phrases.objectName(track.item.label))
    }

    @Synchronized fun reset() {
        tracks.clear()
        frameAt = Long.MIN_VALUE
        lastAlertAt = null
    }
}
