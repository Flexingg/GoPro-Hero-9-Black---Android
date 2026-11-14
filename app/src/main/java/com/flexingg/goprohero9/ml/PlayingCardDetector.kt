package com.flexingg.goprohero9.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
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
 * On-device playing card locator and rank/suit classifier.
 *
 * Stage 1: Fast downsampled (360px) adaptive white-card segmentation to locate card bounding boxes.
 * Stage 2: Full-resolution corner index crop + Otsu bimodal thresholding + red/black color prior +
 *          16x16 Normalized Cross-Correlation + geometric suit shape features.
 */
object PlayingCardDetector {
    private const val ANALYSIS_WIDTH = 360
    private const val TEMPLATE_SIZE = 16
    private const val TEMPLATE_LEN = TEMPLATE_SIZE * TEMPLATE_SIZE

    private val rankTemplates: Map<CardRank, FloatArray> by lazy { buildRankTemplates() }
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

    fun detect(sourceBitmap: Bitmap): List<CardDetection> {
        val srcW = sourceBitmap.width
        val srcH = sourceBitmap.height
        if (srcW <= 0 || srcH <= 0) return emptyList()

        val scale = ANALYSIS_WIDTH.toFloat() / srcW.toFloat()
        val w = ANALYSIS_WIDTH
        val h = (srcH * scale).toInt().coerceAtLeast(1)

        val scaled = Bitmap.createScaledBitmap(sourceBitmap, w, h, false)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled != sourceBitmap) {
            scaled.recycle()
        }

        // Compute luminance & adaptive white-card mask (resistant to dark wood tables & lamp glare)
        val lum = IntArray(w * h)
        var lumSum = 0L
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val y = (r * 77 + g * 150 + b * 29) shr 8
            lum[i] = y
            lumSum += y
        }

        val meanLum = (lumSum / pixels.size).toInt()
        val whiteThreshold = (meanLum + 18).coerceIn(112, 162)

        val mask = BooleanArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val chroma = maxC - minC
            if (lum[i] >= whiteThreshold && chroma < 68) {
                mask[i] = true
            }
        }

        // Connected components on white-card mask
        val visited = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val frameArea = (w * h).toFloat()
        val minCardArea = frameArea * 0.010f
        val maxCardArea = frameArea * 0.45f

        val rawDetections = ArrayList<CardDetection>()

        for (startIdx in mask.indices) {
            if (!mask[startIdx] || visited[startIdx]) continue

            var head = 0
            var tail = 0
            queue[tail++] = startIdx
            visited[startIdx] = true

            var minX = w
            var maxX = 0
            var minY = h
            var maxY = 0
            var count = 0

            while (head < tail) {
                val idx = queue[head++]
                val x = idx % w
                val y = idx / w
                count++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                if (x > 0) {
                    val n = idx - 1
                    if (mask[n] && !visited[n]) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
                if (x + 1 < w) {
                    val n = idx + 1
                    if (mask[n] && !visited[n]) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
                if (y > 0) {
                    val n = idx - w
                    if (mask[n] && !visited[n]) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
                if (y + 1 < h) {
                    val n = idx + w
                    if (mask[n] && !visited[n]) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
            }

            if (count < minCardArea || count > maxCardArea) continue

            val bw = (maxX - minX + 1).toFloat()
            val bh = (maxY - minY + 1).toFloat()
            if (bw < 18f || bh < 22f) continue

            val aspect = bw / bh
            if (aspect < 0.40f || aspect > 2.2f) continue

            // Reject irregular glare blobs that don't fill a rectangular card footprint
            val fillRatio = count / (bw * bh)
            if (fillRatio < 0.56f) continue

            val normLeft = minX / w.toFloat()
            val normTop = minY / h.toFloat()
            val normRight = (maxX + 1) / w.toFloat()
            val normBottom = (maxY + 1) / h.toFloat()

            // Map back to full-resolution sourceBitmap for crisp corner index reading
            val fullCardRect = Rect(
                (normLeft * srcW).toInt().coerceIn(0, srcW - 1),
                (normTop * srcH).toInt().coerceIn(0, srcH - 1),
                (normRight * srcW).toInt().coerceIn(1, srcW),
                (normBottom * srcH).toInt().coerceIn(1, srcH)
            )

            val classified = classifyFullResCorner(sourceBitmap, fullCardRect)
            if (classified != null && classified.confidence >= 0.62f) {
                rawDetections.add(
                    classified.copy(boundingBox = RectF(normLeft, normTop, normRight, normBottom))
                )
            }
        }

        return nonMaxSuppression(rawDetections, iouThreshold = 0.45f)
    }

    private fun classifyFullResCorner(
        sourceBitmap: Bitmap,
        cardRect: Rect
    ): CardDetection? {
        val srcW = sourceBitmap.width
        val srcH = sourceBitmap.height
        val cw = cardRect.width()
        val ch = cardRect.height()
        if (cw < 24 || ch < 30) return null

        // Crop top-left corner index region (skip outer 2px card border)
        val padX = (cw * 0.02f).toInt().coerceAtLeast(1)
        val padY = (ch * 0.02f).toInt().coerceAtLeast(1)
        val cornerLeft = (cardRect.left + padX).coerceIn(0, srcW - 1)
        val cornerTop = (cardRect.top + padY).coerceIn(0, srcH - 1)
        val cornerRight = (cardRect.left + (cw * 0.34f).toInt()).coerceIn(cornerLeft + 8, srcW)
        val cornerBottom = (cardRect.top + (ch * 0.42f).toInt()).coerceIn(cornerTop + 10, srcH)

        val rw = cornerRight - cornerLeft
        val rh = cornerBottom - cornerTop
        if (rw < 8 || rh < 10) return null

        val cornerPixels = IntArray(rw * rh)
        sourceBitmap.getPixels(cornerPixels, 0, rw, cornerLeft, cornerTop, rw, rh)

        val cornerLum = IntArray(rw * rh)
        val hist = IntArray(256)
        for (i in cornerPixels.indices) {
            val c = cornerPixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val y = (r * 77 + g * 150 + b * 29) shr 8
            cornerLum[i] = y
            hist[y]++
        }

        // Compute Otsu's bimodal threshold separating white card paper from dark/red ink
        val otsuThresh = computeOtsuThreshold(hist, rw * rh).coerceIn(70, 175)

        val inkMask = BooleanArray(rw * rh)
        var redScoreCount = 0
        var totalInkCount = 0

        for (i in cornerPixels.indices) {
            val c = cornerPixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val redness = r - max(g, b)
            val isInk = cornerLum[i] < otsuThresh || redness > 24
            if (isInk) {
                inkMask[i] = true
                totalInkCount++
                if (redness > 20) redScoreCount++
            }
        }

        val cornerArea = rw * rh
        // Require a sensible ink density (reject blank glare or solid dark shadows)
        if (totalInkCount < 14 || totalInkCount > cornerArea * 0.65f) return null
        val isRed = redScoreCount.toFloat() / totalInkCount.toFloat() > 0.22f

        // Segment connected ink blobs
        val visited = BooleanArray(rw * rh)
        val q = IntArray(rw * rh)
        val blobs = ArrayList<Rect>()
        val minBlobPixels = (cornerArea * 0.008f).toInt().coerceAtLeast(6)

        for (i in inkMask.indices) {
            if (!inkMask[i] || visited[i]) continue
            var head = 0
            var tail = 0
            q[tail++] = i
            visited[i] = true

            var bMinX = rw
            var bMaxX = 0
            var bMinY = rh
            var bMaxY = 0
            var bCount = 0

            while (head < tail) {
                val p = q[head++]
                val px = p % rw
                val py = p / rw
                bCount++
                if (px < bMinX) bMinX = px
                if (px > bMaxX) bMaxX = px
                if (py < bMinY) bMinY = py
                if (py > bMaxY) bMaxY = py

                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = px + dx
                        val ny = py + dy
                        if (nx in 0 until rw && ny in 0 until rh) {
                            val ni = ny * rw + nx
                            if (inkMask[ni] && !visited[ni]) {
                                visited[ni] = true
                                q[tail++] = ni
                            }
                        }
                    }
                }
            }

            val bw = bMaxX - bMinX + 1
            val bh = bMaxY - bMinY + 1
            // Filter out tiny specks and ultra-thin edge lines
            if (bCount >= minBlobPixels && bw >= 3 && bh >= 4) {
                blobs.add(Rect(bMinX, bMinY, bMaxX + 1, bMaxY + 1))
            }
        }

        // Require at least 2 distinct blobs (Rank + Suit pip) to reject table glare
        if (blobs.size < 2) return null

        blobs.sortBy { it.top }

        // Topmost glyph(s) form Rank; merge "1" and "0" if side-by-side on the top row
        val topBlob = blobs[0]
        val rankRect = Rect(topBlob)
        var suitBlobIndex = 1
        if (blobs.size >= 3) {
            val second = blobs[1]
            val sameRow = abs(second.top - topBlob.top) <= max(4, topBlob.height() / 2)
            val horizontallyClose = abs(second.left - topBlob.right) <= max(6, topBlob.width())
            if (sameRow && horizontallyClose) {
                rankRect.union(second)
                suitBlobIndex = 2
            }
        }

        if (suitBlobIndex >= blobs.size) return null

        // Pick the suit pip directly below the rank (aligned horizontally)
        val rankCenterX = rankRect.centerX()
        val suitRect = blobs.subList(suitBlobIndex, blobs.size)
            .filter { it.top >= rankRect.top + rankRect.height() / 3 }
            .minByOrNull { abs(it.centerX() - rankCenterX) + (it.top - rankRect.bottom).coerceAtLeast(0) }
            ?: return null

        val rankPatch = extractNormalizedPatch(inkMask, rw, rankRect)
        val (bestRank, rankScore) = matchRank(rankPatch)

        val suitPatch = extractNormalizedPatch(inkMask, rw, suitRect)
        val (bestSuit, suitScore) = matchSuit(suitPatch, isRed)

        val combinedConfidence = (rankScore * 0.55f + suitScore * 0.45f).coerceIn(0f, 0.99f)

        val normCorner = RectF(
            (cornerLeft + min(rankRect.left, suitRect.left)) / srcW.toFloat(),
            (cornerTop + rankRect.top) / srcH.toFloat(),
            (cornerLeft + max(rankRect.right, suitRect.right)) / srcW.toFloat(),
            (cornerTop + suitRect.bottom) / srcH.toFloat()
        )

        return CardDetection(
            boundingBox = RectF(),
            rank = bestRank,
            suit = bestSuit,
            confidence = combinedConfidence,
            cornerBox = normCorner
        )
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

    private fun extractNormalizedPatch(mask: BooleanArray, maskW: Int, rect: Rect): FloatArray {
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
                    val idx = (rect.top + sy) * maskW + (rect.left + sx)
                    patch[ty * TEMPLATE_SIZE + tx] = if (mask[idx]) 1f else 0f
                }
            }
        }
        return patch
    }

    private fun matchRank(patch: FloatArray): Pair<CardRank, Float> {
        var bestRank = CardRank.ACE
        var bestScore = -1f
        for ((rank, tmpl) in rankTemplates) {
            val ncc = normalizedCrossCorrelation(patch, tmpl)
            if (ncc > bestScore) {
                bestScore = ncc
                bestRank = rank
            }
        }
        val conf = ((bestScore + 1f) * 0.5f).coerceIn(0f, 1f)
        return Pair(bestRank, conf)
    }

    private fun matchSuit(patch: FloatArray, isRed: Boolean): Pair<CardSuit, Float> {
        // Compute geometric shape cues on the 16x16 suit patch
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

        var bestSuit = candidates.first()
        var bestCombined = -2f
        for (suit in candidates) {
            val tmpl = suitTemplates[suit] ?: continue
            val ncc = normalizedCrossCorrelation(patch, tmpl)
            val geomBonus = when (suit) {
                CardSuit.SPADES -> if (topWidth < midWidth * 0.72f) 0.14f else -0.06f
                CardSuit.CLUBS -> if (topWidth >= midWidth * 0.68f) 0.10f else -0.04f
                CardSuit.HEARTS -> if (topWidth > botWidth * 1.15f && topCenterInk < 0.7f) 0.14f else -0.04f
                CardSuit.DIAMONDS -> if (midWidth >= topWidth * 1.15f && midWidth >= botWidth * 1.15f) 0.14f else -0.04f
            }
            val score = ncc + geomBonus
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
            if (maxX >= minX) {
                totalSpan += (maxX - minX + 1)
            }
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

    private fun buildRankTemplates(): Map<CardRank, FloatArray> {
        val map = HashMap<CardRank, FloatArray>()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 14f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        for (rank in CardRank.entries) {
            val bmp = Bitmap.createBitmap(TEMPLATE_SIZE, TEMPLATE_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.BLACK)
            val bounds = Rect()
            paint.getTextBounds(rank.symbol, 0, rank.symbol.length, bounds)
            val x = TEMPLATE_SIZE / 2f
            val y = TEMPLATE_SIZE / 2f - bounds.exactCenterY()
            canvas.drawText(rank.symbol, x, y, paint)
            map[rank] = bitmapToNormalizedArray(bmp)
            bmp.recycle()
        }
        return map
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
