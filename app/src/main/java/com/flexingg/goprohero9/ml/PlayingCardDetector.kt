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
 * Stage 1: Adaptive bright-card connected-component segmentation to locate card bounding boxes.
 * Stage 2: Corner index extraction + ink color prior (red vs black) + 16x16 Normalized Cross-Correlation
 *          against synthesized rank & vector suit templates.
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
        if (sourceBitmap.width <= 0 || sourceBitmap.height <= 0) return emptyList()

        val scale = ANALYSIS_WIDTH.toFloat() / sourceBitmap.width.toFloat()
        val w = ANALYSIS_WIDTH
        val h = (sourceBitmap.height * scale).toInt().coerceAtLeast(1)

        val scaled = Bitmap.createScaledBitmap(sourceBitmap, w, h, false)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled != sourceBitmap) {
            scaled.recycle()
        }

        // Compute luminance & white-card mask
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
        val whiteThreshold = (meanLum + 32).coerceIn(135, 195)

        val mask = BooleanArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val chroma = maxC - minC
            if (lum[i] >= whiteThreshold && chroma < 58) {
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

                // 4-neighbor flood fill
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
            if (aspect < 0.35f || aspect > 2.4f) continue

            val fillRatio = count / (bw * bh)
            if (fillRatio < 0.42f) continue

            val cardRect = Rect(minX, minY, maxX + 1, maxY + 1)
            val classified = classifyCardCorner(pixels, lum, w, h, cardRect)
            if (classified != null) {
                val normBox = RectF(
                    minX / w.toFloat(),
                    minY / h.toFloat(),
                    (maxX + 1) / w.toFloat(),
                    (maxY + 1) / h.toFloat()
                )
                rawDetections.add(
                    classified.copy(boundingBox = normBox)
                )
            }
        }

        return nonMaxSuppression(rawDetections, iouThreshold = 0.45f)
    }

    private fun classifyCardCorner(
        pixels: IntArray,
        lum: IntArray,
        imgW: Int,
        imgH: Int,
        cardRect: Rect
    ): CardDetection? {
        val cw = cardRect.width()
        val ch = cardRect.height()

        // Inspect top-left corner index region (first ~32% width, ~42% height of card)
        val cornerLeft = (cardRect.left + 1).coerceIn(0, imgW - 1)
        val cornerTop = (cardRect.top + 1).coerceIn(0, imgH - 1)
        val cornerRight = (cardRect.left + (cw * 0.36f).toInt()).coerceIn(cornerLeft + 4, imgW)
        val cornerBottom = (cardRect.top + (ch * 0.44f).toInt()).coerceIn(cornerTop + 4, imgH)

        val rw = cornerRight - cornerLeft
        val rh = cornerBottom - cornerTop
        if (rw < 6 || rh < 8) return null

        // Local white reference inside corner
        var maxLocalLum = 0
        for (y in cornerTop until cornerBottom) {
            val rowOff = y * imgW
            for (x in cornerLeft until cornerRight) {
                val yVal = lum[rowOff + x]
                if (yVal > maxLocalLum) maxLocalLum = yVal
            }
        }
        val inkLumThresh = (maxLocalLum * 0.68f).toInt().coerceIn(75, 155)

        val inkMask = BooleanArray(rw * rh)
        var redScoreCount = 0
        var totalInkCount = 0

        for (y in 0 until rh) {
            val rowOff = (cornerTop + y) * imgW
            for (x in 0 until rw) {
                val idx = rowOff + cornerLeft + x
                val c = pixels[idx]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val redness = r - max(g, b)
                val isInk = lum[idx] < inkLumThresh || redness > 28
                if (isInk) {
                    inkMask[y * rw + x] = true
                    totalInkCount++
                    if (redness > 24) redScoreCount++
                }
            }
        }

        if (totalInkCount < 8) return null
        val isRed = redScoreCount.toFloat() / totalInkCount.toFloat() > 0.25f

        // Segment connected ink blobs in corner
        val visited = BooleanArray(rw * rh)
        val q = IntArray(rw * rh)
        val blobs = ArrayList<Rect>()

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

            if (bCount >= 4) {
                blobs.add(Rect(bMinX, bMinY, bMaxX + 1, bMaxY + 1))
            }
        }

        if (blobs.isEmpty()) return null

        // Sort blobs top-to-bottom
        blobs.sortBy { it.top }

        // Topmost glyph(s) form Rank; if "10" is split into two side-by-side blobs on the top row, merge them
        val topBlob = blobs[0]
        val rankRect = Rect(topBlob)
        var suitBlobIndex = 1
        if (blobs.size >= 2) {
            val second = blobs[1]
            val sameRow = abs(second.top - topBlob.top) <= max(3, topBlob.height() / 2)
            val horizontallyClose = abs(second.left - topBlob.right) <= max(4, topBlob.width())
            if (sameRow && horizontallyClose) {
                rankRect.union(second)
                suitBlobIndex = 2
            }
        }

        val rankPatch = extractNormalizedPatch(inkMask, rw, rankRect)
        val (bestRank, rankScore) = matchRank(rankPatch)

        val suitMatch: Pair<CardSuit, Float> = if (suitBlobIndex < blobs.size) {
            val suitRect = blobs[suitBlobIndex]
            val suitPatch = extractNormalizedPatch(inkMask, rw, suitRect)
            matchSuit(suitPatch, isRed)
        } else {
            // Fallback to color prior if suit pip wasn't separately segmented
            Pair(if (isRed) CardSuit.HEARTS else CardSuit.SPADES, 0.52f)
        }

        val combinedConfidence = ((rankScore * 0.6f + suitMatch.second * 0.4f)
            .coerceIn(0.35f, 0.99f))

        val normCorner = RectF(
            (cornerLeft + rankRect.left) / imgW.toFloat(),
            (cornerTop + rankRect.top) / imgH.toFloat(),
            (cornerLeft + max(rankRect.right, if (suitBlobIndex < blobs.size) blobs[suitBlobIndex].right else rankRect.right)) / imgW.toFloat(),
            (cornerTop + if (suitBlobIndex < blobs.size) blobs[suitBlobIndex].bottom else rankRect.bottom) / imgH.toFloat()
        )

        return CardDetection(
            boundingBox = RectF(),
            rank = bestRank,
            suit = suitMatch.first,
            confidence = combinedConfidence,
            cornerBox = normCorner
        )
    }

    private fun extractNormalizedPatch(mask: BooleanArray, maskW: Int, rect: Rect): FloatArray {
        val patch = FloatArray(TEMPLATE_LEN)
        val srcW = rect.width().coerceAtLeast(1)
        val srcH = rect.height().coerceAtLeast(1)

        // Preserve aspect ratio centered inside 16x16
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
        val candidates = if (isRed) {
            listOf(CardSuit.HEARTS, CardSuit.DIAMONDS)
        } else {
            listOf(CardSuit.SPADES, CardSuit.CLUBS)
        }
        var bestSuit = candidates.first()
        var bestScore = -1f
        for (suit in candidates) {
            val tmpl = suitTemplates[suit] ?: continue
            val ncc = normalizedCrossCorrelation(patch, tmpl)
            if (ncc > bestScore) {
                bestScore = ncc
                bestSuit = suit
            }
        }
        val conf = ((bestScore + 1f) * 0.5f).coerceIn(0f, 1f)
        return Pair(bestSuit, conf)
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

        // Hearts vector path
        val heartPath = Path().apply {
            moveTo(8f, 14f)
            cubicTo(2f, 10f, 1f, 5f, 4.5f, 3f)
            cubicTo(6.5f, 2f, 8f, 4f, 8f, 5.5f)
            cubicTo(8f, 4f, 9.5f, 2f, 11.5f, 3f)
            cubicTo(15f, 5f, 14f, 10f, 8f, 14f)
            close()
        }
        map[CardSuit.HEARTS] = pathToNormalizedArray(heartPath, paint)

        // Diamonds vector path
        val diamondPath = Path().apply {
            moveTo(8f, 1.5f)
            lineTo(13.5f, 8f)
            lineTo(8f, 14.5f)
            lineTo(2.5f, 8f)
            close()
        }
        map[CardSuit.DIAMONDS] = pathToNormalizedArray(diamondPath, paint)

        // Spades vector path
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

        // Clubs vector path
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
