package com.flexingg.goprohero9.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class TrainedCardSample(
    val id: String,
    val rank: CardRank,
    val suit: CardSuit,
    val cornerTemplate: FloatArray, // 24x24 = 576
    val rankTemplate: FloatArray,   // 16x16 = 256
    val suitTemplate: FloatArray,   // 16x16 = 256
    val rednessScore: Float,
    val createdAt: Long
) {
    val label: String get() = "${rank.symbol}${suit.symbol}"
}

data class ExtractedCornerFeatures(
    val cornerTemplate: FloatArray,
    val rankTemplate: FloatArray,
    val suitTemplate: FloatArray,
    val rednessScore: Float,
    val paperLum: Int
)

/**
 * Persists user-snapped playing card corner samples locally and exposes an in-memory
 * snapshot for real-time few-shot matching inside PlayingCardDetector.
 */
object CardTrainingStore {
    private const val TAG = "CardTrainingStore"
    private const val FILE_NAME = "card_training_v1.json"
    const val CORNER_SIZE = 24
    const val PART_SIZE = 16

    @Volatile
    private var appFilesDir: File? = null

    private val _samplesFlow = MutableStateFlow<List<TrainedCardSample>>(emptyList())
    val samplesFlow: StateFlow<List<TrainedCardSample>> = _samplesFlow.asStateFlow()

    @Volatile
    var samples: List<TrainedCardSample> = emptyList()
        private set

    fun init(context: Context) {
        if (appFilesDir != null) return
        appFilesDir = context.applicationContext.filesDir
        loadFromDisk()
    }

    fun addSampleFromBitmap(
        context: Context,
        cornerBitmap: Bitmap,
        rank: CardRank,
        suit: CardSuit
    ): TrainedCardSample {
        init(context)
        val features = extractFeatures(cornerBitmap)
        val sample = TrainedCardSample(
            id = UUID.randomUUID().toString(),
            rank = rank,
            suit = suit,
            cornerTemplate = features.cornerTemplate,
            rankTemplate = features.rankTemplate,
            suitTemplate = features.suitTemplate,
            rednessScore = features.rednessScore,
            createdAt = System.currentTimeMillis()
        )
        val updated = samples + sample
        samples = updated
        _samplesFlow.value = updated
        saveToDisk(updated)
        return sample
    }

    fun removeSample(context: Context, id: String) {
        init(context)
        val updated = samples.filterNot { it.id == id }
        samples = updated
        _samplesFlow.value = updated
        saveToDisk(updated)
    }

    fun clearAll(context: Context) {
        init(context)
        samples = emptyList()
        _samplesFlow.value = emptyList()
        saveToDisk(emptyList())
    }

    fun extractFeatures(cornerBitmap: Bitmap): ExtractedCornerFeatures {
        val w = cornerBitmap.width.coerceAtLeast(1)
        val h = cornerBitmap.height.coerceAtLeast(1)
        val pixels = IntArray(w * h)
        cornerBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val lums = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            lums[i] = (r * 77 + g * 150 + b * 29) shr 8
        }

        // Paper white reference: 82nd percentile RGB in the corner patch
        val sortedLums = lums.clone().apply { sort() }
        val paperLum = sortedLums[(sortedLums.size * 0.82f).toInt().coerceIn(0, sortedLums.lastIndex)]
            .coerceAtLeast(60)

        var sumPaperR = 0L
        var sumPaperG = 0L
        var sumPaperB = 0L
        var paperCount = 0
        for (i in pixels.indices) {
            if (lums[i] >= paperLum - 12) {
                val c = pixels[i]
                sumPaperR += (c shr 16) and 0xFF
                sumPaperG += (c shr 8) and 0xFF
                sumPaperB += c and 0xFF
                paperCount++
            }
        }
        val paperR = if (paperCount > 0) (sumPaperR / paperCount).toInt() else paperLum
        val paperG = if (paperCount > 0) (sumPaperG / paperCount).toInt() else paperLum
        val paperB = if (paperCount > 0) (sumPaperB / paperCount).toInt() else paperLum

        // Measure paper-relative redness on ink pixels
        val inkThreshold = (paperLum * 0.72f).toInt()
        var redExcessSum = 0f
        var inkCount = 0
        for (i in pixels.indices) {
            if (lums[i] < inkThreshold) {
                val c = pixels[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val rDrop = (paperR - r).coerceAtLeast(0)
                val gDrop = (paperG - g).coerceAtLeast(0)
                val bDrop = (paperB - b).coerceAtLeast(0)
                redExcessSum += ((bDrop + gDrop) * 0.5f - rDrop)
                inkCount++
            }
        }
        val rednessScore = if (inkCount > 0) redExcessSum / inkCount else 0f

        val cornerTemplate = sampleNormalizedPatch(lums, w, h, 0, 0, w, h, paperLum, CORNER_SIZE)

        // Split top half (rank) & bottom half (suit pip) and auto-tighten to ink bounds
        val midY = (h * 0.52f).toInt().coerceIn(1, h - 1)
        val rankBounds = findInkBounds(lums, w, 0, 0, w, midY, inkThreshold)
        val suitBounds = findInkBounds(lums, w, 0, (h * 0.45f).toInt(), w, h, inkThreshold)

        val rankTemplate = sampleNormalizedPatch(
            lums, w, h,
            rankBounds[0], rankBounds[1], rankBounds[2], rankBounds[3],
            paperLum, PART_SIZE
        )
        val suitTemplate = sampleNormalizedPatch(
            lums, w, h,
            suitBounds[0], suitBounds[1], suitBounds[2], suitBounds[3],
            paperLum, PART_SIZE
        )

        return ExtractedCornerFeatures(
            cornerTemplate = cornerTemplate,
            rankTemplate = rankTemplate,
            suitTemplate = suitTemplate,
            rednessScore = rednessScore,
            paperLum = paperLum
        )
    }

    private fun findInkBounds(
        lums: IntArray,
        w: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        inkThreshold: Int
    ): IntArray {
        var minX = x1
        var minY = y1
        var maxX = x0
        var maxY = y0
        var count = 0
        for (y in y0 until y1) {
            val row = y * w
            for (x in x0 until x1) {
                if (lums[row + x] < inkThreshold) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    count++
                }
            }
        }
        return if (count >= 6 && maxX > minX && maxY > minY) {
            intArrayOf(
                (minX - 1).coerceAtLeast(x0),
                (minY - 1).coerceAtLeast(y0),
                (maxX + 2).coerceAtMost(x1),
                (maxY + 2).coerceAtMost(y1)
            )
        } else {
            intArrayOf(x0, y0, x1, y1)
        }
    }

    fun sampleNormalizedPatch(
        lums: IntArray,
        imgW: Int,
        imgH: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        paperLum: Int,
        targetSize: Int
    ): FloatArray {
        val len = targetSize * targetSize
        val patch = FloatArray(len)
        val rw = (x1 - x0).coerceAtLeast(1)
        val rh = (y1 - y0).coerceAtLeast(1)
        var sum = 0f

        for (ty in 0 until targetSize) {
            val sy = (y0 + (ty + 0.5f) * rh / targetSize).toInt().coerceIn(0, imgH - 1)
            val row = sy * imgW
            for (tx in 0 until targetSize) {
                val sx = (x0 + (tx + 0.5f) * rw / targetSize).toInt().coerceIn(0, imgW - 1)
                val ink = ((paperLum - lums[row + sx]).toFloat() / paperLum.coerceAtLeast(1)).coerceIn(0f, 1f)
                patch[ty * targetSize + tx] = ink
                sum += ink
            }
        }

        val mean = sum / len
        var normSq = 0f
        for (i in 0 until len) {
            val v = patch[i] - mean
            patch[i] = v
            normSq += v * v
        }
        val norm = sqrt(normSq)
        if (norm > 1e-4f) {
            for (i in 0 until len) patch[i] /= norm
        }
        return patch
    }

    fun ncc(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    private fun loadFromDisk() {
        val dir = appFilesDir ?: return
        val file = File(dir, FILE_NAME)
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            val loaded = ArrayList<TrainedCardSample>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val rank = CardRank.entries.firstOrNull { it.name == obj.getString("rank") } ?: continue
                val suit = CardSuit.entries.firstOrNull { it.name == obj.getString("suit") } ?: continue
                loaded.add(
                    TrainedCardSample(
                        id = obj.getString("id"),
                        rank = rank,
                        suit = suit,
                        cornerTemplate = jsonToFloatArray(obj.getJSONArray("corner")),
                        rankTemplate = jsonToFloatArray(obj.getJSONArray("rankTpl")),
                        suitTemplate = jsonToFloatArray(obj.getJSONArray("suitTpl")),
                        rednessScore = obj.optDouble("redness", 0.0).toFloat(),
                        createdAt = obj.optLong("createdAt", 0L)
                    )
                )
            }
            samples = loaded
            _samplesFlow.value = loaded
            Log.d(TAG, "Loaded ${loaded.size} trained card samples")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load trained samples: ${e.message}")
        }
    }

    private fun saveToDisk(list: List<TrainedCardSample>) {
        val dir = appFilesDir ?: return
        val file = File(dir, FILE_NAME)
        try {
            val arr = JSONArray()
            for (s in list) {
                val obj = JSONObject().apply {
                    put("id", s.id)
                    put("rank", s.rank.name)
                    put("suit", s.suit.name)
                    put("corner", floatArrayToJson(s.cornerTemplate))
                    put("rankTpl", floatArrayToJson(s.rankTemplate))
                    put("suitTpl", floatArrayToJson(s.suitTemplate))
                    put("redness", s.rednessScore.toDouble())
                    put("createdAt", s.createdAt)
                }
                arr.put(obj)
            }
            file.writeText(arr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save trained samples: ${e.message}")
        }
    }

    private fun floatArrayToJson(arr: FloatArray): JSONArray {
        val j = JSONArray()
        for (v in arr) j.put(v.toDouble())
        return j
    }

    private fun jsonToFloatArray(arr: JSONArray): FloatArray {
        return FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
    }
}
