package com.flexingg.goprohero9.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class CardSuit(val symbol: String, val label: String, val isRed: Boolean) {
    SPADES("♠", "Spades", false),
    HEARTS("♥", "Hearts", true),
    CLUBS("♣", "Clubs", false),
    DIAMONDS("♦", "Diamonds", true)
}

enum class CardRank(val symbol: String) {
    ACE("A"),
    TWO("2"),
    THREE("3"),
    FOUR("4"),
    FIVE("5"),
    SIX("6"),
    SEVEN("7"),
    EIGHT("8"),
    NINE("9"),
    TEN("10"),
    JACK("J"),
    QUEEN("Q"),
    KING("K")
}

data class CardDetection(
    val boundingBox: RectF,
    val rank: CardRank,
    val suit: CardSuit,
    val confidence: Float,
    val cornerBox: RectF? = null
) {
    val label: String get() = "${rank.symbol}${suit.symbol}"
}

/**
 * Hybrid On-Device ML Playing Card Detector:
 * 1. Google ML Kit Text Recognition v2 (deep-learning OCR) locates & reads card corner ranks (A..K)
 *    with exact bounding boxes across lighting, angles, and card fonts.
 * 2. Full-resolution suit pip crop right below (or above if upside-down) the rank determines
 *    Red (♥/♦) vs Black (♠/♣) and classifies suit shape via 16x16 NCC + geometric features.
 * 3. Seeded white-card flood fill from the verified corner gives the exact card bounding box.
 */
object PlayingCardDetector {
    private const val ANALYSIS_WIDTH = 360
    private const val TEMPLATE_SIZE = 16
    private const val TEMPLATE_LEN = TEMPLATE_SIZE * TEMPLATE_SIZE

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val suitTemplates: Map<CardSuit, FloatArray> by lazy { buildSuitTemplates() }

    fun findTextureView(view: View?): TextureView? {
        if (view == null) return null
        if (view is TextureView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findTextureView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    suspend fun detect(sourceBitmap: Bitmap): List<CardDetection> {
        val srcW = sourceBitmap.width
        val srcH = sourceBitmap.height
        if (srcW <= 0 || srcH <= 0) return emptyList()

        val visionText = recognizeText(sourceBitmap) ?: return emptyList()

        // Collect all candidate rank elements from ML Kit
        val rankCandidates = ArrayList<Pair<CardRank, Rect>>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val box = element.boundingBox ?: continue
                    val rank = parseCardRank(element.text) ?: continue
                    if (box.width() < 6 || box.height() < 8) continue
                    if (box.height() > srcH * 0.35f) continue
                    rankCandidates.add(Pair(rank, box))
                }
            }
        }

        if (rankCandidates.isEmpty()) return emptyList()

        // Prepare fast 360px luminance/chroma map for seeded card-box flood fill
        val scale = ANALYSIS_WIDTH.toFloat() / srcW.toFloat()
        val smallW = ANALYSIS_WIDTH
        val smallH = (srcH * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(sourceBitmap, smallW, smallH, false)
        val smallPixels = IntArray(smallW * smallH)
        scaled.getPixels(smallPixels, 0, smallW, 0, 0, smallW, smallH)
        if (scaled != sourceBitmap) scaled.recycle()

        val smallLum = IntArray(smallW * smallH)
        val smallChroma = IntArray(smallW * smallH)
        for (i in smallPixels.indices) {
            val c = smallPixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            smallLum[i] = (r * 77 + g * 150 + b * 29) shr 8
            smallChroma[i] = max(r, max(g, b)) - min(r, min(g, b))
        }

        val detections = ArrayList<CardDetection>()

        for ((rank, rankBox) in rankCandidates) {
            // 1. Check that the rank sits on bright card paper (and get local white reference)
            val paperLum = samplePaperLuminanceAround(sourceBitmap, rankBox) ?: continue
            if (paperLum < 95) continue

            // 2. Check suit pip region below the rank (normal orientation) or above (upside-down)
            val belowPipRect = Rect(
                (rankBox.left - rankBox.width() * 0.45f).toInt().coerceIn(0, srcW - 1),
                (rankBox.bottom + 1).coerceIn(0, srcH - 1),
                (rankBox.right + rankBox.width() * 0.45f).toInt().coerceIn(1, srcW),
                (rankBox.bottom + rankBox.height() * 1.45f).toInt().coerceIn(1, srcH)
            )
            val abovePipRect = Rect(
                (rankBox.left - rankBox.width() * 0.45f).toInt().coerceIn(0, srcW - 1),
                (rankBox.top - rankBox.height() * 1.45f).toInt().coerceIn(0, srcH - 1),
                (rankBox.right + rankBox.width() * 0.45f).toInt().coerceIn(1, srcW),
                (rankBox.top - 1).coerceIn(1, srcH)
            )

            val rankRedRatio = measureRedRatio(sourceBitmap, rankBox, paperLum)
            val belowMatch = classifySuitInRegion(sourceBitmap, belowPipRect, paperLum, rankRedRatio, upsideDown = false)
            val aboveMatch = if (belowMatch == null) {
                classifySuitInRegion(sourceBitmap, abovePipRect, paperLum, rankRedRatio, upsideDown = true)
            } else null

            val (suit, suitConf, pipNormRect, isUpsideDown) = belowMatch ?: aboveMatch ?: continue

            // 3. Seed-fill the white card rectangle starting from the paper next to rankBox
            val cardNormBox = seedFillCardBox(
                smallLum = smallLum,
                smallChroma = smallChroma,
                w = smallW,
                h = smallH,
                rankBox = rankBox,
                srcW = srcW,
                srcH = srcH,
                paperLum = paperLum,
                isUpsideDown = isUpsideDown
            )

            val cornerNorm = RectF(
                min(rankBox.left / srcW.toFloat(), pipNormRect.left),
                min(rankBox.top / srcH.toFloat(), pipNormRect.top),
                max(rankBox.right / srcW.toFloat(), pipNormRect.right),
                max(rankBox.bottom / srcH.toFloat(), pipNormRect.bottom)
            )

            val conf = (0.55f + suitConf * 0.40f).coerceIn(0.60f, 0.98f)
            detections.add(
                CardDetection(
                    boundingBox = cardNormBox,
                    rank = rank,
                    suit = suit,
                    confidence = conf,
                    cornerBox = cornerNorm
                )
            )
        }

        // 4. Fallback: if user has trained card samples, scan bright card-paper regions
        //    for any cards missed by ML Kit OCR.
        val trained = CardTrainingStore.samples
        if (trained.isNotEmpty()) {
            val trainedHits = detectWithTrainedTemplates(
                sourceBitmap = sourceBitmap,
                smallLum = smallLum,
                smallChroma = smallChroma,
                w = smallW,
                h = smallH,
                trained = trained,
                existingDetections = detections
            )
            detections.addAll(trainedHits)
        }

        return nonMaxSuppression(detections, iouThreshold = 0.35f)
    }

    private suspend fun recognizeText(bitmap: Bitmap) =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(image)
                .addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
        }

    private fun parseCardRank(raw: String): CardRank? {
        val t = raw.trim().uppercase()
        return when (t) {
            "A", "4A" -> CardRank.ACE
            "2" -> CardRank.TWO
            "3" -> CardRank.THREE
            "4" -> CardRank.FOUR
            "5" -> CardRank.FIVE
            "6" -> CardRank.SIX
            "7" -> CardRank.SEVEN
            "8" -> CardRank.EIGHT
            "9" -> CardRank.NINE
            "10", "IO", "1O" -> CardRank.TEN
            "J" -> CardRank.JACK
            "Q" -> CardRank.QUEEN
            "K" -> CardRank.KING
            else -> null
        }
    }

    private fun samplePaperLuminanceAround(bitmap: Bitmap, box: Rect): Int? {
        val w = bitmap.width
        val h = bitmap.height
        val pad = max(4, box.width() / 2)
        val left = (box.left - pad).coerceIn(0, w - 1)
        val top = (box.top - pad).coerceIn(0, h - 1)
        val right = (box.right + pad).coerceIn(left + 1, w)
        val bottom = (box.bottom + pad).coerceIn(top + 1, h)

        val rw = right - left
        val rh = bottom - top
        val px = IntArray(rw * rh)
        bitmap.getPixels(px, 0, rw, left, top, rw, rh)

        // Take 75th percentile luminance as paper white reference
        val lums = IntArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            lums[i] = (r * 77 + g * 150 + b * 29) shr 8
        }
        lums.sort()
        return lums[(lums.size * 0.78f).toInt().coerceIn(0, lums.lastIndex)]
    }

    private fun measureRedRatio(bitmap: Bitmap, box: Rect, paperLum: Int): Float {
        val l = box.left.coerceIn(0, bitmap.width - 1)
        val t = box.top.coerceIn(0, bitmap.height - 1)
        val r = box.right.coerceIn(l + 1, bitmap.width)
        val b = box.bottom.coerceIn(t + 1, bitmap.height)
        val rw = r - l
        val rh = b - t
        val px = IntArray(rw * rh)
        bitmap.getPixels(px, 0, rw, l, t, rw, rh)

        val inkLumThresh = (paperLum * 0.75f).toInt()
        var inkCount = 0
        var redCount = 0
        for (c in px) {
            val cr = (c shr 16) and 0xFF
            val cg = (c shr 8) and 0xFF
            val cb = c and 0xFF
            val y = (cr * 77 + cg * 150 + cb * 29) shr 8
            val redness = cr - max(cg, cb)
            if (y < inkLumThresh || redness > 20) {
                inkCount++
                if (redness > 18) redCount++
            }
        }
        return if (inkCount > 0) redCount.toFloat() / inkCount.toFloat() else 0f
    }

    private data class SuitMatchResult(
        val suit: CardSuit,
        val confidence: Float,
        val pipNormBox: RectF,
        val isUpsideDown: Boolean
    )

    private fun classifySuitInRegion(
        bitmap: Bitmap,
        pipRegion: Rect,
        paperLum: Int,
        rankRedRatio: Float,
        upsideDown: Boolean
    ): SuitMatchResult? {
        val rw = pipRegion.width()
        val rh = pipRegion.height()
        if (rw < 6 || rh < 6) return null

        val px = IntArray(rw * rh)
        bitmap.getPixels(px, 0, rw, pipRegion.left, pipRegion.top, rw, rh)

        val lum = IntArray(rw * rh)
        val hist = IntArray(256)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val y = (r * 77 + g * 150 + b * 29) shr 8
            lum[i] = y
            hist[y]++
        }

        val otsu = computeOtsuThreshold(hist, rw * rh)
        val inkThresh = min(otsu, (paperLum * 0.78f).toInt()).coerceIn(60, 170)

        val mask = BooleanArray(rw * rh)
        var inkCount = 0
        var redCount = 0
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val redness = r - max(g, b)
            if (lum[i] < inkThresh || redness > 22) {
                mask[i] = true
                inkCount++
                if (redness > 18) redCount++
            }
        }

        val regionArea = rw * rh
        if (inkCount < max(8, (regionArea * 0.04f).toInt()) || inkCount > regionArea * 0.80f) {
            return null
        }

        // Find the largest connected ink blob in the pip region
        val visited = BooleanArray(rw * rh)
        val q = IntArray(rw * rh)
        var bestRect: Rect? = null
        var bestCount = 0

        for (i in mask.indices) {
            if (!mask[i] || visited[i]) continue
            var head = 0
            var tail = 0
            q[tail++] = i
            visited[i] = true
            var minX = rw
            var maxX = 0
            var minY = rh
            var maxY = 0
            var cnt = 0
            while (head < tail) {
                val p = q[head++]
                val x = p % rw
                val y = p / rw
                cnt++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in 0 until rw && ny in 0 until rh) {
                            val ni = ny * rw + nx
                            if (mask[ni] && !visited[ni]) {
                                visited[ni] = true
                                q[tail++] = ni
                            }
                        }
                    }
                }
            }
            if (cnt > bestCount) {
                bestCount = cnt
                bestRect = Rect(minX, minY, maxX + 1, maxY + 1)
            }
        }

        val pipBox = bestRect ?: return null
        if (pipBox.width() < 4 || pipBox.height() < 4) return null

        val pipRedRatio = redCount.toFloat() / inkCount.toFloat()
        val isRed = (rankRedRatio * 0.5f + pipRedRatio * 0.5f) > 0.22f

        val patch = extractNormalizedPatch(mask, rw, pipBox, flip180 = upsideDown)
        val (suit, score) = matchSuit(patch, isRed)

        val normPip = RectF(
            (pipRegion.left + pipBox.left) / bitmap.width.toFloat(),
            (pipRegion.top + pipBox.top) / bitmap.height.toFloat(),
            (pipRegion.left + pipBox.right) / bitmap.width.toFloat(),
            (pipRegion.top + pipBox.bottom) / bitmap.height.toFloat()
        )
        return SuitMatchResult(suit, score, normPip, upsideDown)
    }

    private fun seedFillCardBox(
        smallLum: IntArray,
        smallChroma: IntArray,
        w: Int,
        h: Int,
        rankBox: Rect,
        srcW: Int,
        srcH: Int,
        paperLum: Int,
        isUpsideDown: Boolean
    ): RectF {
        val sx = ((rankBox.centerX() / srcW.toFloat()) * w).toInt().coerceIn(0, w - 1)
        val sy = ((rankBox.centerY() / srcH.toFloat()) * h).toInt().coerceIn(0, h - 1)

        val whiteThresh = (paperLum * 0.72f).toInt().coerceIn(85, 165)

        // Find nearest bright white seed pixel in a small window around rank center
        var seedIdx = -1
        for (dy in -4..4) {
            for (dx in -4..4) {
                val nx = (sx + dx).coerceIn(0, w - 1)
                val ny = (sy + dy).coerceIn(0, h - 1)
                val idx = ny * w + nx
                if (smallLum[idx] >= whiteThresh && smallChroma[idx] < 72) {
                    seedIdx = idx
                    break
                }
            }
            if (seedIdx >= 0) break
        }

        if (seedIdx >= 0) {
            val visited = BooleanArray(w * h)
            val q = IntArray(w * h)
            var head = 0
            var tail = 0
            q[tail++] = seedIdx
            visited[seedIdx] = true

            var minX = w
            var maxX = 0
            var minY = h
            var maxY = 0
            var count = 0
            val maxPixels = (w * h * 0.42f).toInt()

            while (head < tail && count < maxPixels) {
                val idx = q[head++]
                val x = idx % w
                val y = idx / w
                count++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                if (x > 0) {
                    val n = idx - 1
                    if (!visited[n] && smallLum[n] >= whiteThresh && smallChroma[n] < 72) {
                        visited[n] = true
                        q[tail++] = n
                    }
                }
                if (x + 1 < w) {
                    val n = idx + 1
                    if (!visited[n] && smallLum[n] >= whiteThresh && smallChroma[n] < 72) {
                        visited[n] = true
                        q[tail++] = n
                    }
                }
                if (y > 0) {
                    val n = idx - w
                    if (!visited[n] && smallLum[n] >= whiteThresh && smallChroma[n] < 72) {
                        visited[n] = true
                        q[tail++] = n
                    }
                }
                if (y + 1 < h) {
                    val n = idx + w
                    if (!visited[n] && smallLum[n] >= whiteThresh && smallChroma[n] < 72) {
                        visited[n] = true
                        q[tail++] = n
                    }
                }
            }

            if (count > 120 && (maxX - minX) > 14 && (maxY - minY) > 18) {
                return RectF(
                    minX / w.toFloat(),
                    minY / h.toFloat(),
                    (maxX + 1) / w.toFloat(),
                    (maxY + 1) / h.toFloat()
                )
            }
        }

        // Fallback: geometric card estimate anchored to corner rank
        val rw = rankBox.width() / srcW.toFloat()
        val rh = rankBox.height() / srcH.toFloat()
        val cardW = (rw * 4.5f).coerceIn(0.08f, 0.45f)
        val cardH = (rh * 6.5f).coerceIn(0.12f, 0.65f)
        return if (!isUpsideDown) {
            val left = (rankBox.left / srcW.toFloat() - rw * 0.25f).coerceIn(0f, 1f)
            val top = (rankBox.top / srcH.toFloat() - rh * 0.25f).coerceIn(0f, 1f)
            RectF(left, top, (left + cardW).coerceAtMost(1f), (top + cardH).coerceAtMost(1f))
        } else {
            val right = (rankBox.right / srcW.toFloat() + rw * 0.25f).coerceIn(0f, 1f)
            val bottom = (rankBox.bottom / srcH.toFloat() + rh * 0.25f).coerceIn(0f, 1f)
            RectF((right - cardW).coerceAtLeast(0f), (bottom - cardH).coerceAtLeast(0f), right, bottom)
        }
    }

    private fun computeOtsuThreshold(hist: IntArray, total: Int): Int {
        if (total <= 0) return 128
        var sum = 0f
        for (t in 0..255) sum += t * hist[t]
        var sumB = 0f
        var wB = 0
        var maxVar = -1f
        var threshold = 128
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toFloat() * wF.toFloat() * (mB - mF) * (mB - mF)
            if (between > maxVar) {
                maxVar = between
                threshold = t
            }
        }
        return threshold
    }

    private fun extractNormalizedPatch(
        mask: BooleanArray,
        maskW: Int,
        rect: Rect,
        flip180: Boolean = false
    ): FloatArray {
        val patch = FloatArray(TEMPLATE_LEN)
        val srcW = rect.width().coerceAtLeast(1)
        val srcH = rect.height().coerceAtLeast(1)

        val scale = min(
            (TEMPLATE_SIZE - 2).toFloat() / srcW.toFloat(),
            (TEMPLATE_SIZE - 2).toFloat() / srcH.toFloat()
        )
        val drawnW = srcW * scale
        val drawnH = srcH * scale
        val offX = (TEMPLATE_SIZE - drawnW) * 0.5f
        val offY = (TEMPLATE_SIZE - drawnH) * 0.5f

        for (ty in 0 until TEMPLATE_SIZE) {
            for (tx in 0 until TEMPLATE_SIZE) {
                val sx = ((tx - offX) / scale).toInt()
                val sy = ((ty - offY) / scale).toInt()
                if (sx in 0 until srcW && sy in 0 until srcH) {
                    val rx = if (flip180) (srcW - 1 - sx) else sx
                    val ry = if (flip180) (srcH - 1 - sy) else sy
                    val idx = (rect.top + ry) * maskW + (rect.left + rx)
                    patch[ty * TEMPLATE_SIZE + tx] = if (mask[idx]) 1f else 0f
                }
            }
        }
        return patch
    }

    private fun matchSuit(patch: FloatArray, isRed: Boolean): Pair<CardSuit, Float> {
        val topWidth = rowSpanWidth(patch, 2..4)
        val midWidth = rowSpanWidth(patch, 6..9)
        val botWidth = rowSpanWidth(patch, 11..13)
        val topCenterInk = (patch[2 * TEMPLATE_SIZE + 7] + patch[2 * TEMPLATE_SIZE + 8] +
            patch[3 * TEMPLATE_SIZE + 7] + patch[3 * TEMPLATE_SIZE + 8]) / 4f

        val candidates = if (isRed) {
            listOf(CardSuit.HEARTS, CardSuit.DIAMONDS)
        } else {
            listOf(CardSuit.SPADES, CardSuit.CLUBS)
        }

        // Prepare zero-mean unit-norm version of patch for comparing against user-trained suit templates
        val patchNorm = FloatArray(TEMPLATE_LEN)
        var sum = 0f
        for (v in patch) sum += v
        val mean = sum / TEMPLATE_LEN
        var normSq = 0f
        for (i in patch.indices) {
            val d = patch[i] - mean
            patchNorm[i] = d
            normSq += d * d
        }
        val norm = sqrt(normSq)
        if (norm > 1e-4f) {
            for (i in patchNorm.indices) patchNorm[i] /= norm
        }

        val trainedSamples = CardTrainingStore.samples
        var bestSuit = candidates.first()
        var bestCombined = -2f
        for (suit in candidates) {
            val tmpl = suitTemplates[suit] ?: continue
            val synthNcc = normalizedCrossCorrelation(patch, tmpl)
            val trainedNcc = trainedSamples
                .filter { it.suit == suit }
                .maxOfOrNull { CardTrainingStore.ncc(patchNorm, it.suitTemplate) }

            val baseNcc = if (trainedNcc != null) {
                trainedNcc * 0.72f + synthNcc * 0.28f
            } else {
                synthNcc
            }

            val geomBonus = when (suit) {
                CardSuit.SPADES -> if (topWidth < midWidth * 0.72f) 0.16f else -0.06f
                CardSuit.CLUBS -> if (topWidth >= midWidth * 0.68f) 0.12f else -0.04f
                CardSuit.HEARTS -> if (topWidth > botWidth * 1.15f && topCenterInk < 0.7f) 0.16f else -0.04f
                CardSuit.DIAMONDS -> if (midWidth >= topWidth * 1.15f && midWidth >= botWidth * 1.15f) 0.16f else -0.04f
            }
            val score = baseNcc + geomBonus
            if (score > bestCombined) {
                bestCombined = score
                bestSuit = suit
            }
        }
        val conf = ((bestCombined + 1f) * 0.5f).coerceIn(0f, 1f)
        return Pair(bestSuit, conf)
    }

    private fun rowSpanWidth(patch: FloatArray, rows: IntRange): Float {
        var totalSpan = 0f
        var count = 0
        for (y in rows) {
            var minX = TEMPLATE_SIZE
            var maxX = -1
            for (x in 0 until TEMPLATE_SIZE) {
                if (patch[y * TEMPLATE_SIZE + x] > 0.5f) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                }
            }
            if (maxX >= minX) totalSpan += (maxX - minX + 1)
            count++
        }
        return if (count > 0) totalSpan / count else 0f
    }

    private fun normalizedCrossCorrelation(a: FloatArray, b: FloatArray): Float {
        var meanA = 0f
        var meanB = 0f
        for (i in 0 until TEMPLATE_LEN) {
            meanA += a[i]
            meanB += b[i]
        }
        meanA /= TEMPLATE_LEN
        meanB /= TEMPLATE_LEN

        var num = 0f
        var denA = 0f
        var denB = 0f
        for (i in 0 until TEMPLATE_LEN) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            num += da * db
            denA += da * da
            denB += db * db
        }
        val denom = sqrt(denA * denB)
        return if (denom > 1e-5f) num / denom else 0f
    }

    private fun buildSuitTemplates(): Map<CardSuit, FloatArray> {
        val map = HashMap<CardSuit, FloatArray>()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        val heartPath = Path().apply {
            moveTo(8f, 14f)
            cubicTo(2f, 10f, 1f, 5f, 4.5f, 3f)
            cubicTo(6.5f, 2f, 8f, 4f, 8f, 5.5f)
            cubicTo(8f, 4f, 9.5f, 2f, 11.5f, 3f)
            cubicTo(15f, 5f, 14f, 10f, 8f, 14f)
            close()
        }
        map[CardSuit.HEARTS] = pathToNormalizedArray(heartPath, paint)

        val diamondPath = Path().apply {
            moveTo(8f, 1.5f)
            lineTo(13.5f, 8f)
            lineTo(8f, 14.5f)
            lineTo(2.5f, 8f)
            close()
        }
        map[CardSuit.DIAMONDS] = pathToNormalizedArray(diamondPath, paint)

        val spadePath = Path().apply {
            moveTo(8f, 1.5f)
            cubicTo(14f, 6f, 14.5f, 11f, 10.5f, 11.5f)
            cubicTo(9f, 11.5f, 8.4f, 10.5f, 8.4f, 10.5f)
            lineTo(9.8f, 14.5f)
            lineTo(6.2f, 14.5f)
            lineTo(7.6f, 10.5f)
            cubicTo(7.6f, 10.5f, 7f, 11.5f, 5.5f, 11.5f)
            cubicTo(1.5f, 11f, 2f, 6f, 8f, 1.5f)
            close()
        }
        map[CardSuit.SPADES] = pathToNormalizedArray(spadePath, paint)

        val clubPath = Path().apply {
            addCircle(8f, 4.8f, 2.8f, Path.Direction.CW)
            addCircle(4.8f, 8.5f, 2.8f, Path.Direction.CW)
            addCircle(11.2f, 8.5f, 2.8f, Path.Direction.CW)
            moveTo(7.1f, 9f)
            lineTo(8.9f, 9f)
            lineTo(9.8f, 14.5f)
            lineTo(6.2f, 14.5f)
            close()
        }
        map[CardSuit.CLUBS] = pathToNormalizedArray(clubPath, paint)

        return map
    }

    private fun pathToNormalizedArray(path: Path, paint: Paint): FloatArray {
        val bmp = Bitmap.createBitmap(TEMPLATE_SIZE, TEMPLATE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        canvas.drawPath(path, paint)
        val arr = bitmapToNormalizedArray(bmp)
        bmp.recycle()
        return arr
    }

    private fun bitmapToNormalizedArray(bmp: Bitmap): FloatArray {
        val arr = FloatArray(TEMPLATE_LEN)
        val px = IntArray(TEMPLATE_LEN)
        bmp.getPixels(px, 0, TEMPLATE_SIZE, 0, 0, TEMPLATE_SIZE, TEMPLATE_SIZE)
        for (i in 0 until TEMPLATE_LEN) {
            arr[i] = ((px[i] shr 16) and 0xFF) / 255f
        }
        return arr
    }

    private fun nonMaxSuppression(detections: List<CardDetection>, iouThreshold: Float): List<CardDetection> {
        if (detections.size <= 1) return detections
        val sorted = detections.sortedByDescending { it.confidence }
        val kept = ArrayList<CardDetection>()
        for (det in sorted) {
            var overlap = false
            for (k in kept) {
                if (computeIoU(det.boundingBox, k.boundingBox) > iouThreshold) {
                    overlap = true
                    break
                }
            }
            if (!overlap) kept.add(det)
        }
        return kept
    }

    private fun detectWithTrainedTemplates(
        sourceBitmap: Bitmap,
        smallLum: IntArray,
        smallChroma: IntArray,
        w: Int,
        h: Int,
        trained: List<TrainedCardSample>,
        existingDetections: List<CardDetection>
    ): List<CardDetection> {
        val visited = BooleanArray(w * h)
        val q = IntArray(w * h)
        val minArea = (w * h * 0.012f).toInt().coerceAtLeast(35)
        val maxArea = (w * h * 0.36f).toInt()
        val srcW = sourceBitmap.width
        val srcH = sourceBitmap.height

        val results = ArrayList<CardDetection>()

        for (i in smallLum.indices) {
            if (visited[i] || smallLum[i] < 102 || smallChroma[i] >= 80) continue

            var head = 0
            var tail = 0
            q[tail++] = i
            visited[i] = true
            var minX = w
            var maxX = 0
            var minY = h
            var maxY = 0
            var count = 0

            while (head < tail) {
                val idx = q[head++]
                val x = idx % w
                val y = idx / w
                count++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                if (x > 0 && !visited[idx - 1] && smallLum[idx - 1] >= 102 && smallChroma[idx - 1] < 80) {
                    visited[idx - 1] = true; q[tail++] = idx - 1
                }
                if (x + 1 < w && !visited[idx + 1] && smallLum[idx + 1] >= 102 && smallChroma[idx + 1] < 80) {
                    visited[idx + 1] = true; q[tail++] = idx + 1
                }
                if (y > 0 && !visited[idx - w] && smallLum[idx - w] >= 102 && smallChroma[idx - w] < 80) {
                    visited[idx - w] = true; q[tail++] = idx - w
                }
                if (y + 1 < h && !visited[idx + w] && smallLum[idx + w] >= 102 && smallChroma[idx + w] < 80) {
                    visited[idx + w] = true; q[tail++] = idx + w
                }
            }

            if (count < minArea || count > maxArea) continue

            val cardNorm = RectF(
                minX / w.toFloat(),
                minY / h.toFloat(),
                (maxX + 1) / w.toFloat(),
                (maxY + 1) / h.toFloat()
            )
            if (existingDetections.any { computeIoU(it.boundingBox, cardNorm) > 0.25f }) continue

            val cardW = (cardNorm.width() * srcW).toInt()
            val cardH = (cardNorm.height() * srcH).toInt()
            if (cardW < 24 || cardH < 28) continue

            val cardLeft = (cardNorm.left * srcW).toInt().coerceIn(0, srcW - 1)
            val cardTop = (cardNorm.top * srcH).toInt().coerceIn(0, srcH - 1)

            // Search top-left corner at 2 scales
            val cornerScales = floatArrayOf(0.20f, 0.27f)
            var bestSample: TrainedCardSample? = null
            var bestScore = 0.58f
            var bestCornerRect: Rect? = null

            for (cwFrac in cornerScales) {
                val cw = (cardW * cwFrac).toInt().coerceIn(14, srcW / 3)
                val ch = (cw * 1.45f).toInt().coerceIn(20, srcH / 3)
                val dxSteps = intArrayOf(2, (cardW * 0.04f).toInt(), (cardW * 0.08f).toInt())
                val dySteps = intArrayOf(2, (cardH * 0.03f).toInt(), (cardH * 0.06f).toInt())

                for (dy in dySteps) {
                    for (dx in dxSteps) {
                        val cl = (cardLeft + dx).coerceIn(0, srcW - cw)
                        val ct = (cardTop + dy).coerceIn(0, srcH - ch)
                        val crop = Bitmap.createBitmap(sourceBitmap, cl, ct, cw, ch)
                        val feat = try {
                            CardTrainingStore.extractFeatures(crop)
                        } finally {
                            crop.recycle()
                        }

                        val isRed = feat.rednessScore > 14f
                        for (s in trained) {
                            // Respect color polarity when redness signal is clear
                            if (feat.rednessScore > 20f && !s.suit.isRed) continue
                            if (feat.rednessScore < 4f && s.suit.isRed) continue

                            val score = CardTrainingStore.ncc(feat.cornerTemplate, s.cornerTemplate) * 0.55f +
                                CardTrainingStore.ncc(feat.rankTemplate, s.rankTemplate) * 0.25f +
                                CardTrainingStore.ncc(feat.suitTemplate, s.suitTemplate) * 0.20f
                            if (score > bestScore) {
                                bestScore = score
                                bestSample = s
                                bestCornerRect = Rect(cl, ct, cl + cw, ct + ch)
                            }
                        }
                    }
                }
            }

            if (bestSample != null && bestCornerRect != null) {
                val cornerNorm = RectF(
                    bestCornerRect.left / srcW.toFloat(),
                    bestCornerRect.top / srcH.toFloat(),
                    bestCornerRect.right / srcW.toFloat(),
                    bestCornerRect.bottom / srcH.toFloat()
                )
                results.add(
                    CardDetection(
                        boundingBox = cardNorm,
                        rank = bestSample.rank,
                        suit = bestSample.suit,
                        confidence = bestScore.coerceIn(0.60f, 0.99f),
                        cornerBox = cornerNorm
                    )
                )
            }
        }

        return results
    }

    fun computeIoU(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        if (interRight <= interLeft || interBottom <= interTop) return 0f
        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val unionArea = areaA + areaB - interArea
        return if (unionArea > 0f) interArea / unionArea else 0f
    }
}

/**
 * Smooths bounding boxes across live video frames to prevent visual jitter.
 */
class CardDetectionTracker {
    private var previous: List<CardDetection> = emptyList()

    fun update(current: List<CardDetection>): List<CardDetection> {
        if (current.isEmpty()) {
            previous = emptyList()
            return emptyList()
        }
        val smoothed = current.map { det ->
            val match = previous.maxByOrNull { PlayingCardDetector.computeIoU(det.boundingBox, it.boundingBox) }
            if (match != null && PlayingCardDetector.computeIoU(det.boundingBox, match.boundingBox) > 0.35f) {
                val alpha = 0.55f
                val box = RectF(
                    match.boundingBox.left * (1f - alpha) + det.boundingBox.left * alpha,
                    match.boundingBox.top * (1f - alpha) + det.boundingBox.top * alpha,
                    match.boundingBox.right * (1f - alpha) + det.boundingBox.right * alpha,
                    match.boundingBox.bottom * (1f - alpha) + det.boundingBox.bottom * alpha
                )
                det.copy(boundingBox = box)
            } else {
                det
            }
        }
        previous = smoothed
        return smoothed
    }

    fun reset() {
        previous = emptyList()
    }
}
