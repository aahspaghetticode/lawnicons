/*
 * Copyright 2026 Lawnchair Launcher
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair.lawnicons.helper

import java.awt.BasicStroke
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.FlatteningPathIterator
import java.awt.geom.Path2D
import java.awt.geom.PathIterator
import java.awt.geom.Point2D
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import org.dom4j.Element

/**
 * Sizes each icon as large as it can be without anything being cut off by the smallest
 * adaptive icon mask, a circle.
 *
 * Adaptive icons are drawn on a 108dp canvas of which only the center 72dp is ever visible.
 * [ADAPTIVE_INSET] maps the foreground drawable onto exactly that 72dp area, so a circular
 * mask is the circle inscribed in the vector's viewport. [scaleToFit] measures how far the
 * visible artwork reaches from the viewport center (fills, and strokes including their width,
 * caps and joins) and wraps the artwork in a group scaled about the center so its farthest
 * point lands on that circle. Artwork that already reaches past the circle is scaled down,
 * so the mask never cuts anything.
 *
 * [SvgFilesProcessor] runs this on every generated vector right before createAdaptive.
 */
object IconFitScaler {
    private const val ADAPTIVE_CANVAS_DP = 108.0
    private const val VISIBLE_AREA_DP = 72.0

    /** Inset that maps the foreground onto the visible area: (108 - 72) / 2 / 108, about 16.67%. */
    val ADAPTIVE_INSET: String = percentRoundedUp((ADAPTIVE_CANVAS_DP - VISIBLE_AREA_DP) / 2 / ADAPTIVE_CANVAS_DP)

    /** Radius of the circle in work units. Large so Java2D stroking keeps full precision. */
    private const val WORK_RADIUS = 1024.0

    /** Largest gap, in work units, allowed between a flattened curve and the real one. */
    private const val FLATNESS = 0.01
    private const val FLATTEN_LIMIT = 16

    /** Segments shorter than this, in work units, are below what Android's stroker draws. */
    private const val TEENY_SEGMENT = 1e-4

    private const val SCALE_DECIMALS = 4

    /** Fits the entire viewport inside the circle; used only if an icon can't be measured. */
    private val FALLBACK_SCALE = floorScale(1 / sqrt(2.0))

    private var fittedCount = 0
    private var smallestScale = Double.MAX_VALUE
    private var largestScale = 0.0
    private val unmeasured = mutableListOf<String>()

    /**
     * Calculates the fit for the vector drawable at [xmlPath] and applies it to the file in place.
     * Returns the scale that was applied.
     */
    fun scaleToFit(xmlPath: String): Double {
        val document = XmlUtil.getDocument(xmlPath)
        val vector = document.rootElement
        val viewportWidth = vector.doubleAttribute("viewportWidth") ?: 0.0
        val viewportHeight = vector.doubleAttribute("viewportHeight") ?: 0.0
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            unmeasured += "$xmlPath (no viewport, left as is)"
            return 1.0
        }
        val scale = try {
            calculateScale(vector, viewportWidth, viewportHeight)
        } catch (e: IllegalArgumentException) {
            unmeasured += "$xmlPath (${e.message})"
            FALLBACK_SCALE
        }
        fittedCount++
        smallestScale = min(smallestScale, scale)
        largestScale = max(largestScale, scale)

        if (scale != 1.0) {
            wrapInScaledGroup(vector, scale, viewportWidth / 2, viewportHeight / 2)
            XmlUtil.writeDocumentToFile(document, xmlPath)
        }
        return scale
    }

    /**
     * Largest scale about the viewport center at which every visible part of [vector] stays
     * inside the circle inscribed in its viewport. Rounded down so it can never overshoot.
     */
    fun calculateScale(vector: Element, viewportWidth: Double, viewportHeight: Double): Double {
        val reach = farthestVisiblePoint(vector, viewportWidth, viewportHeight) / WORK_RADIUS
        require(reach.isFinite()) { "artwork has no finite size" }
        if (reach <= 0) return 1.0
        return floorScale(1 / reach)
    }

    fun summary(): String = buildString {
        if (fittedCount == 0) return@buildString
        append("Fitted $fittedCount icons to a circular mask (scale ")
        append("${format(smallestScale)} to ${format(largestScale)}, inset $ADAPTIVE_INSET)")
        if (unmeasured.isNotEmpty()) {
            append("\nCould not measure ${unmeasured.size} icon(s); each was fitted by its whole viewport:")
            unmeasured.forEach { append("\n  $it") }
        }
    }

    private fun farthestVisiblePoint(vector: Element, viewportWidth: Double, viewportHeight: Double): Double {
        // Work space: viewport center at the origin and the visible circle at WORK_RADIUS. The
        // drawable is always drawn into a square, so each axis is normalized on its own.
        val toWork = AffineTransform(
            2 * WORK_RADIUS / viewportWidth,
            0.0,
            0.0,
            2 * WORK_RADIUS / viewportHeight,
            -WORK_RADIUS,
            -WORK_RADIUS,
        )
        // Android draws stroke widths with the smaller of the two viewport scales.
        val strokeToWork = 2 * WORK_RADIUS / max(viewportWidth, viewportHeight)

        var farthest = 0.0
        fun visit(group: Element, matrix: AffineTransform) {
            for (child in group.elements()) {
                when (child.name) {
                    "group" -> visit(child, AffineTransform(matrix).apply { concatenate(groupMatrix(child)) })
                    "path" -> farthest = max(farthest, farthestPathPoint(child, matrix, toWork, strokeToWork))
                    // A clip-path can only hide artwork, so leaving it out never causes cutting.
                }
            }
        }
        visit(vector, AffineTransform())
        return farthest
    }

    private fun farthestPathPoint(
        path: Element,
        matrix: AffineTransform,
        toWork: AffineTransform,
        strokeToWork: Double,
    ): Double {
        val pathData = path.attributeValue("pathData") ?: return 0.0
        val filled = isVisible(path.attributeValue("fillColor"), path.doubleAttribute("fillAlpha"))
        val strokeWidth = (path.doubleAttribute("strokeWidth") ?: 0.0) * matrixScale(matrix) * strokeToWork
        val stroked = strokeWidth > 0 &&
            isVisible(path.attributeValue("strokeColor"), path.doubleAttribute("strokeAlpha"))
        if (!filled && !stroked) return 0.0

        // Like Android, transform the path first and then stroke it at its scaled width.
        val centerline = PathDataParser(pathData).parse()
        centerline.transform(AffineTransform(toWork).apply { concatenate(matrix) })
        val centerlineReach = farthestPoint(centerline)
        if (!stroked) return centerlineReach
        val strokeReach = farthestStrokePoint(path, centerline, centerlineReach, strokeWidth)
        return if (filled) max(centerlineReach, strokeReach) else strokeReach
    }

    private fun farthestStrokePoint(path: Element, centerline: Path2D, centerlineReach: Double, width: Double): Double {
        val cap = path.attributeValue("strokeLineCap") ?: "butt"
        val join = path.attributeValue("strokeLineJoin") ?: "miter"
        val halfWidth = width / 2

        // With round caps and joins the stroke is every point within half its width of the
        // centerline, so this is exact. It's the standard Lawnicons style, and it also covers
        // zero-length "dot" segments.
        if (cap == "round" && join == "round") return centerlineReach + halfWidth

        val miterLimit = max(1.0, path.doubleAttribute("strokeMiterLimit") ?: 4.0)
        val stroke = BasicStroke(width.toFloat(), awtCap(cap), awtJoin(join), miterLimit.toFloat())
        // The extra FLATNESS covers the gap between the polyline and the real centerline.
        var farthest = farthestPoint(stroke.createStrokedShape(polyline(centerline))) + FLATNESS

        // Android draws round and square caps on zero-length segments; Java2D may not.
        val capReach = when (cap) {
            "round" -> halfWidth
            "square" -> halfWidth * sqrt(2.0)
            else -> 0.0
        }
        if (capReach > 0) {
            for (dot in zeroLengthSubpaths(centerline)) {
                farthest = max(farthest, hypot(dot.x, dot.y) + capReach)
            }
        }
        return farthest
    }

    /**
     * Flattened copy of [path] for Java2D to stroke. Java2D's offset curves can overshoot on tight
     * bends, and it draws joins on vanishingly short segments (like a closing point that only
     * differs from the start by rounding) that Android's stroker skips. Either would shrink the
     * icon for nothing, so curves become lines and those segments are dropped.
     */
    private fun polyline(path: Path2D): Path2D {
        val result = Path2D.Double()
        val iterator = FlatteningPathIterator(path.getPathIterator(null), FLATNESS, FLATTEN_LIMIT)
        val coords = DoubleArray(6)
        var startX = 0.0
        var startY = 0.0
        var lastX = 0.0
        var lastY = 0.0
        while (!iterator.isDone) {
            when (iterator.currentSegment(coords)) {
                PathIterator.SEG_MOVETO -> {
                    startX = coords[0]
                    startY = coords[1]
                    lastX = startX
                    lastY = startY
                    result.moveTo(startX, startY)
                }

                PathIterator.SEG_CLOSE -> {
                    result.closePath()
                    lastX = startX
                    lastY = startY
                }

                else -> if (hypot(coords[0] - lastX, coords[1] - lastY) > TEENY_SEGMENT) {
                    val atStart = hypot(coords[0] - startX, coords[1] - startY) <= TEENY_SEGMENT
                    lastX = if (atStart) startX else coords[0]
                    lastY = if (atStart) startY else coords[1]
                    result.lineTo(lastX, lastY)
                }
            }
            iterator.next()
        }
        return result
    }

    /** Farthest distance from the origin reached by [shape], with curves flattened. */
    private fun farthestPoint(shape: Shape): Double {
        val iterator = FlatteningPathIterator(shape.getPathIterator(null), FLATNESS, FLATTEN_LIMIT)
        val coords = DoubleArray(6)
        var farthest = 0.0
        var hasPoints = false
        while (!iterator.isDone) {
            if (iterator.currentSegment(coords) != PathIterator.SEG_CLOSE) {
                farthest = max(farthest, hypot(coords[0], coords[1]))
                hasPoints = true
            }
            iterator.next()
        }
        // Flattened points lie on the curve, which can bulge past them by up to FLATNESS.
        return if (hasPoints) farthest + FLATNESS else 0.0
    }

    /** Points where a subpath draws something but never leaves its starting point. */
    private fun zeroLengthSubpaths(path: Path2D): List<Point2D> {
        val dots = mutableListOf<Point2D>()
        val iterator = path.getPathIterator(null)
        val coords = DoubleArray(6)
        var startX = 0.0
        var startY = 0.0
        var draws = false
        var moves = false
        fun finishSubpath() {
            if (draws && !moves) dots += Point2D.Double(startX, startY)
            draws = false
            moves = false
        }
        while (!iterator.isDone) {
            when (val type = iterator.currentSegment(coords)) {
                PathIterator.SEG_MOVETO -> {
                    finishSubpath()
                    startX = coords[0]
                    startY = coords[1]
                }

                PathIterator.SEG_CLOSE -> draws = true

                else -> {
                    draws = true
                    val pointCount = when (type) {
                        PathIterator.SEG_LINETO -> 1
                        PathIterator.SEG_QUADTO -> 2
                        else -> 3
                    }
                    for (i in 0 until pointCount) {
                        if (hypot(coords[2 * i] - startX, coords[2 * i + 1] - startY) > FLATNESS) moves = true
                    }
                }
            }
            iterator.next()
        }
        finishSubpath()
        return dots
    }

    /** Wraps everything in [vector] in a group scaled by [scale] about the given pivot. */
    private fun wrapInScaledGroup(vector: Element, scale: Double, pivotX: Double, pivotY: Double) {
        val artwork = vector.elements().toList()
        val group = vector.addElement("group")
            .addAttribute("android:pivotX", format(pivotX))
            .addAttribute("android:pivotY", format(pivotY))
            .addAttribute("android:scaleX", format(scale))
            .addAttribute("android:scaleY", format(scale))
        artwork.forEach {
            it.detach()
            group.add(it)
        }
    }

    /** Group transform in the same order Android's VectorDrawable applies it. */
    private fun groupMatrix(group: Element): AffineTransform {
        val pivotX = group.doubleAttribute("pivotX") ?: 0.0
        val pivotY = group.doubleAttribute("pivotY") ?: 0.0
        return AffineTransform().apply {
            translate(
                (group.doubleAttribute("translateX") ?: 0.0) + pivotX,
                (group.doubleAttribute("translateY") ?: 0.0) + pivotY,
            )
            rotate(Math.toRadians(group.doubleAttribute("rotation") ?: 0.0))
            scale(group.doubleAttribute("scaleX") ?: 1.0, group.doubleAttribute("scaleY") ?: 1.0)
            translate(-pivotX, -pivotY)
        }
    }

    /** How Android scales stroke widths under a group transform (VectorDrawable.getMatrixScale). */
    private fun matrixScale(matrix: AffineTransform): Double {
        val vectors = doubleArrayOf(0.0, 1.0, 1.0, 0.0)
        matrix.deltaTransform(vectors, 0, vectors, 0, 2)
        val scaleX = hypot(vectors[0], vectors[1])
        val scaleY = hypot(vectors[2], vectors[3])
        val crossProduct = vectors[0] * vectors[3] - vectors[1] * vectors[2]
        val maxScale = max(scaleX, scaleY)
        return if (maxScale > 0) abs(crossProduct) / maxScale else 0.0
    }

    private fun isVisible(color: String?, alpha: Double?): Boolean {
        if (color.isNullOrBlank() || (alpha ?: 1.0) <= 0) return false
        // Resource and theme colors such as @color/primaryForeground are always drawn.
        if (!color.startsWith("#")) return true
        val hex = color.substring(1)
        return when (hex.length) {
            4 -> hex[0] != '0'
            8 -> !hex.startsWith("00")
            else -> true
        }
    }

    private fun awtCap(cap: String) = when (cap) {
        "round" -> BasicStroke.CAP_ROUND
        "square" -> BasicStroke.CAP_SQUARE
        else -> BasicStroke.CAP_BUTT
    }

    private fun awtJoin(join: String) = when (join) {
        "round" -> BasicStroke.JOIN_ROUND
        "bevel" -> BasicStroke.JOIN_BEVEL
        else -> BasicStroke.JOIN_MITER
    }

    /** Rounded down, so the written scale can never push artwork past the circle. */
    private fun floorScale(value: Double): Double = BigDecimal.valueOf(value)
        .setScale(SCALE_DECIMALS, RoundingMode.FLOOR)
        .toDouble()

    /** Rounded up, so the inset can never make the foreground larger than the visible area. */
    private fun percentRoundedUp(fraction: Double): String = BigDecimal.valueOf(fraction * 100)
        .setScale(SCALE_DECIMALS, RoundingMode.CEILING)
        .stripTrailingZeros()
        .toPlainString() + "%"

    private fun format(value: Double): String = BigDecimal.valueOf(value)
        .setScale(SCALE_DECIMALS, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()

    private fun Element.doubleAttribute(name: String): Double? = attributeValue(name)?.trim()?.toDoubleOrNull()
}

/** Parses vector drawable path data into a [Path2D], following android.util.PathParser. */
private class PathDataParser(private val data: String) {
    private val path = Path2D.Double()
    private var index = 0
    private var x = 0.0
    private var y = 0.0
    private var startX = 0.0
    private var startY = 0.0
    private var controlX = 0.0
    private var controlY = 0.0

    fun parse(): Path2D.Double {
        var previous = ' '
        while (true) {
            skipSeparators()
            if (index >= data.length) break
            val next = data[index]
            val command = if (next.isLetter()) {
                index++
                next
            } else {
                // Extra coordinates repeat the previous command; after a move they are lines.
                when (previous) {
                    'M' -> 'L'
                    'm' -> 'l'
                    ' ', 'Z', 'z' -> throw IllegalArgumentException("unexpected '$next' at $index in path data")
                    else -> previous
                }
            }
            execute(command, previous)
            previous = command
        }
        return path
    }

    private fun execute(command: Char, previous: Char) {
        val type = command.uppercaseChar()
        // Android's Path lets drawing start without a move, from (0, 0).
        if (type != 'M' && path.currentPoint == null) path.moveTo(x, y)
        val originX = if (command.isLowerCase()) x else 0.0
        val originY = if (command.isLowerCase()) y else 0.0
        when (type) {
            'M' -> {
                x = originX + number()
                y = originY + number()
                path.moveTo(x, y)
                startX = x
                startY = y
            }

            'L' -> {
                x = originX + number()
                y = originY + number()
                path.lineTo(x, y)
            }

            'H' -> {
                x = originX + number()
                path.lineTo(x, y)
            }

            'V' -> {
                y = originY + number()
                path.lineTo(x, y)
            }

            'C', 'S' -> {
                val reflectable = previous.uppercaseChar() == 'C' || previous.uppercaseChar() == 'S'
                var x1 = x
                var y1 = y
                if (type == 'C') {
                    x1 = originX + number()
                    y1 = originY + number()
                } else if (reflectable) {
                    x1 = 2 * x - controlX
                    y1 = 2 * y - controlY
                }
                controlX = originX + number()
                controlY = originY + number()
                x = originX + number()
                y = originY + number()
                path.curveTo(x1, y1, controlX, controlY, x, y)
            }

            'Q', 'T' -> {
                val reflect = type == 'T'
                val reflectable = previous.uppercaseChar() == 'Q' || previous.uppercaseChar() == 'T'
                if (!reflect) {
                    controlX = originX + number()
                    controlY = originY + number()
                } else if (reflectable) {
                    controlX = 2 * x - controlX
                    controlY = 2 * y - controlY
                } else {
                    controlX = x
                    controlY = y
                }
                x = originX + number()
                y = originY + number()
                path.quadTo(controlX, controlY, x, y)
            }

            'A' -> {
                val radiusX = number()
                val radiusY = number()
                val rotation = number()
                val largeArc = flag()
                val sweep = flag()
                arcTo(radiusX, radiusY, rotation, largeArc, sweep, originX + number(), originY + number())
            }

            'Z' -> {
                path.closePath()
                x = startX
                y = startY
                // Android moves the pen back to the subpath start after closing it.
                path.moveTo(x, y)
            }

            else -> throw IllegalArgumentException("unknown command '$command' in path data")
        }
    }

    /** Elliptical arc as cubic Béziers, using the SVG endpoint-to-center conversion. */
    private fun arcTo(
        radiusX: Double,
        radiusY: Double,
        rotation: Double,
        largeArc: Boolean,
        sweep: Boolean,
        endX: Double,
        endY: Double,
    ) {
        val fromX = x
        val fromY = y
        x = endX
        y = endY
        if (fromX == endX && fromY == endY) return
        var rx = abs(radiusX)
        var ry = abs(radiusY)
        if (rx == 0.0 || ry == 0.0) {
            path.lineTo(endX, endY)
            return
        }

        val phi = Math.toRadians(rotation)
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)
        val halfDx = (fromX - endX) / 2
        val halfDy = (fromY - endY) / 2
        val x1 = cosPhi * halfDx + sinPhi * halfDy
        val y1 = -sinPhi * halfDx + cosPhi * halfDy

        // Radii too small to reach the end point are scaled up just enough.
        val lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)
        if (lambda > 1) {
            rx *= sqrt(lambda)
            ry *= sqrt(lambda)
        }

        val numerator = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
        val denominator = rx * rx * y1 * y1 + ry * ry * x1 * x1
        val sign = if (largeArc == sweep) -1 else 1
        val coefficient = sign * sqrt(max(0.0, numerator / denominator))
        val centerPrimeX = coefficient * rx * y1 / ry
        val centerPrimeY = -coefficient * ry * x1 / rx
        val centerX = cosPhi * centerPrimeX - sinPhi * centerPrimeY + (fromX + endX) / 2
        val centerY = sinPhi * centerPrimeX + cosPhi * centerPrimeY + (fromY + endY) / 2

        val startAngle = atan2((y1 - centerPrimeY) / ry, (x1 - centerPrimeX) / rx)
        val endAngle = atan2((-y1 - centerPrimeY) / ry, (-x1 - centerPrimeX) / rx)
        var sweepAngle = endAngle - startAngle
        if (sweep && sweepAngle < 0) sweepAngle += 2 * PI
        if (!sweep && sweepAngle > 0) sweepAngle -= 2 * PI

        val segments = max(1, ceil(abs(sweepAngle) / (PI / 2) - 1e-9).toInt())
        val step = sweepAngle / segments
        val handle = 4.0 / 3.0 * tan(step / 4)
        fun pointX(u: Double, v: Double) = centerX + rx * u * cosPhi - ry * v * sinPhi
        fun pointY(u: Double, v: Double) = centerY + rx * u * sinPhi + ry * v * cosPhi

        var angle = startAngle
        for (segment in 1..segments) {
            val next = angle + step
            val cos1 = cos(angle)
            val sin1 = sin(angle)
            val cos2 = cos(next)
            val sin2 = sin(next)
            val last = segment == segments
            path.curveTo(
                pointX(cos1 - handle * sin1, sin1 + handle * cos1),
                pointY(cos1 - handle * sin1, sin1 + handle * cos1),
                pointX(cos2 + handle * sin2, sin2 - handle * cos2),
                pointY(cos2 + handle * sin2, sin2 - handle * cos2),
                if (last) endX else pointX(cos2, sin2),
                if (last) endY else pointY(cos2, sin2),
            )
            angle = next
        }
    }

    private fun skipSeparators() {
        while (index < data.length && (data[index].isWhitespace() || data[index] == ',')) index++
    }

    private fun number(): Double {
        skipSeparators()
        val start = index
        if (index < data.length && (data[index] == '-' || data[index] == '+')) index++
        var hasDigits = false
        var hasDot = false
        while (index < data.length) {
            val char = data[index]
            if (char.isDigit()) {
                hasDigits = true
            } else if (char == '.' && !hasDot) {
                hasDot = true
            } else {
                break
            }
            index++
        }
        if (hasDigits && index < data.length && (data[index] == 'e' || data[index] == 'E')) {
            var end = index + 1
            if (end < data.length && (data[end] == '-' || data[end] == '+')) end++
            if (end < data.length && data[end].isDigit()) {
                while (end < data.length && data[end].isDigit()) end++
                index = end
            }
        }
        require(hasDigits) { "expected a number at $start in path data" }
        return data.substring(start, index).toDouble()
    }

    /** Arc flags are single digits that may be written without separators, as in "a1 1 0 011 1". */
    private fun flag(): Boolean {
        skipSeparators()
        require(index < data.length && (data[index] == '0' || data[index] == '1')) {
            "expected an arc flag at $index in path data"
        }
        return data[index++] == '1'
    }
}

