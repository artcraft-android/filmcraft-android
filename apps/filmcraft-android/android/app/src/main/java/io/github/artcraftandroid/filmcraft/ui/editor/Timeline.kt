package io.github.artcraftandroid.filmcraft.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.filmcraft.core.CaptionInfo
import io.github.artcraftandroid.filmcraft.core.ClipInfo
import io.github.artcraftandroid.filmcraft.core.ClipKind
import io.github.artcraftandroid.filmcraft.core.LaneInfo
import io.github.artcraftandroid.filmcraft.core.LaneKind
import io.github.artcraftandroid.filmcraft.core.TimelineInfo
import io.github.artcraftandroid.filmcraft.ui.Fc
import io.github.artcraftandroid.filmcraft.ui.FcSize
import io.github.artcraftandroid.filmcraft.ui.GlyphIcon
import io.github.artcraftandroid.filmcraft.ui.Glyphs
import io.github.artcraftandroid.filmcraft.ui.Inter
import io.github.artcraftandroid.filmcraft.ui.LucideIcons
import io.github.artcraftandroid.filmcraft.ui.glyph
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A trim in progress: which clip, which edge, how far (ticks). */
private data class TrimDrag(val clip: ULong, val head: Boolean, val delta: Long)

/** A clip being moved: by how far in time and to which lane. */
private data class MoveDrag(val clip: ULong, val dx: Float, val dy: Float)

/** The timeline: a ruler, the lanes and the fixed playhead in the middle. */
@Composable
fun Timeline(ed: EditorController, modifier: Modifier = Modifier, compact: Boolean = false) {
    val t = ed.timeline ?: return
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val haptic = LocalHapticFeedback.current
    val timelineDesc = stringResource(R.string.editor_timeline)
    val addMediaDesc = stringResource(R.string.editor_add_media)
    val missingMediaLabel = stringResource(R.string.editor_missing_media)
    var trim by remember { mutableStateOf<TrimDrag?>(null) }
    var move by remember { mutableStateOf<MoveDrag?>(null) }
    var scrollY by remember { mutableStateOf(0f) }
    val edState by rememberUpdatedState(ed)
    BoxWithConstraints(modifier.background(Fc.Black).testTag("editor_timeline")) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        with(density) {
            val rulerH = (FcSize.Ruler + FcSize.RulerGap).toPx()
            if (ed.pxPerSecond <= 0f) ed.pxPerSecond = TimelineMath.defaultPxPerSecond(w * 15f / 17f)
            val geo = Geometry(t, w, h, rulerH, ed.pxPerSecond, ed.playhead, scrollY, density.density)

            Canvas(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = timelineDesc }
                    .pointerInput(t.revision, w, h) {
                        val touchSlop = viewConfiguration.touchSlop
                        val longPress = viewConfiguration.longPressTimeoutMillis
                        awaitEachGesture {
                            val e = edState
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val g = Geometry(e.timeline ?: return@awaitEachGesture, w, h, rulerH, e.pxPerSecond, e.playhead, scrollY, density.density)
                            if (e.gesture) return@awaitEachGesture
                            // a selected clip's trim handle
                            val handle = g.handleAt(down.position, e.timeline?.selected)
                            if (handle != null) {
                                e.gesture = true
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                var dx = 0f
                                trim = TrimDrag(handle.first.id, handle.second, 0)
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed) break
                                    dx += c.positionChange().x
                                    c.consume()
                                    val delta = TimelineMath.ticksFor(dx, e.pxPerSecond)
                                    trim = TrimDrag(handle.first.id, handle.second, delta)
                                }
                                val done = trim
                                trim = null
                                e.gesture = false
                                if (done != null && done.delta != 0L) e.trim(done.clip, done.head, TimelineMath.snap(abs(done.delta), e.rateNum, e.rateDen) * if (done.delta < 0) -1 else 1)
                                return@awaitEachGesture
                            }
                            val hitClip = g.clipAt(down.position)
                            val start = down.position
                            var decided = false
                            while (!decided) {
                                val remaining = longPress - (android.os.SystemClock.uptimeMillis() - down.uptimeMillis).coerceAtLeast(0)
                                val ev = withTimeoutOrNull(maxOf(remaining, 1L)) { awaitPointerEvent() }
                                if (ev == null) {
                                    // held still: pick the clip up
                                    if (hitClip != null) {
                                        decided = true
                                        e.gesture = true
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        var dx = 0f
                                        var dy = 0f
                                        move = MoveDrag(hitClip.id, 0f, 0f)
                                        while (true) {
                                            val m = awaitPointerEvent()
                                            val c = m.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!c.pressed) break
                                            dx += c.positionChange().x
                                            dy += c.positionChange().y
                                            c.consume()
                                            move = MoveDrag(hitClip.id, dx, dy)
                                        }
                                        val done = move
                                        move = null
                                        e.gesture = false
                                        if (done != null) g.drop(e, hitClip, done.dx, done.dy, start)
                                    } else {
                                        continue
                                    }
                                    break
                                }
                                if (ev.changes.count { it.pressed } >= 2) {
                                    decided = true
                                    // pinch: zoom around the playhead
                                    var prev = span(ev)
                                    while (true) {
                                        val p = awaitPointerEvent()
                                        if (p.changes.count { it.pressed } < 2) break
                                        val s = span(p)
                                        if (prev > 0f && s > 0f) {
                                            e.pxPerSecond = TimelineMath.clampZoom(e.pxPerSecond * s / prev, w, e.duration, e.rateNum, e.rateDen)
                                        }
                                        prev = s
                                        p.changes.forEach { it.consume() }
                                    }
                                    break
                                }
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) {
                                    decided = true
                                    g.tap(e, start)
                                    break
                                }
                                if ((c.position - start).getDistance() > touchSlop) {
                                    decided = true
                                    // scrub: the timeline slides under the playhead
                                    val from = e.playhead
                                    var dx = c.position.x - start.x
                                    e.seek(from - TimelineMath.ticksFor(dx, e.pxPerSecond))
                                    var dyTotal = 0f
                                    while (true) {
                                        val m = awaitPointerEvent()
                                        if (m.changes.count { it.pressed } >= 2) break
                                        val mc = m.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!mc.pressed) break
                                        dx += mc.positionChange().x
                                        dyTotal += mc.positionChange().y
                                        mc.consume()
                                        if (g.total > g.area) scrollY = (scrollY + mc.positionChange().y).coerceIn(-(g.total - g.area), g.total - g.area)
                                        e.seek(from - TimelineMath.ticksFor(dx, e.pxPerSecond))
                                    }
                                    e.settle()
                                }
                            }
                        }
                    },
            ) {
                drawTimeline(geo, ed, measurer, trim, move, missingMediaLabel)
            }
            // the add-media button, at the right end of the main track
            val mainTop = geo.mainTop
            // only while the main track is in full view (a short timeline hides it)
            if (mainTop != null && !compact && mainTop >= geo.rulerH && mainTop + FcSize.MainTrack.toPx() <= h) {
                val slot = 48.dp.toPx()
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(-8.dp.roundToPx(), (mainTop + FcSize.MainTrack.toPx() / 2 - slot / 2).roundToInt()) }
                        .size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Fc.Accent)
                            .clickable { ed.addMedia(PickKind.PhotosAndVideos) }
                            .testTag("add_to_timeline_button")
                            .semantics { contentDescription = addMediaDesc },
                        contentAlignment = Alignment.Center,
                    ) {
                        GlyphIcon(LucideIcons.Plus.glyph(), Color.White, size = 20.dp)
                    }
                }
            }
        }
    }
}

private fun span(ev: androidx.compose.ui.input.pointer.PointerEvent): Float {
    val p = ev.changes.filter { it.pressed }
    if (p.size < 2) return 0f
    return abs(p[0].position.x - p[1].position.x)
}

/** Where everything on the timeline sits, for drawing and for touches. */
private class Geometry(
    val t: TimelineInfo,
    val width: Float,
    val height: Float,
    val rulerH: Float,
    val pxPerSecond: Float,
    val playhead: Long,
    scrollY: Float,
    val dp: Float,
) {
    val centerX = width / 2f
    val area = (height - rulerH).coerceAtLeast(1f)
    val heights = t.lanes.map { if (it.kind == LaneKind.MAIN) 64f * dp else 48f * dp }
    val mainIndex = t.lanes.indexOfFirst { it.kind == LaneKind.MAIN }.coerceAtLeast(0)
    val gap = 2f * dp
    /** The captions get a lane of their own above everything else. */
    val captionH = 28f * dp
    private val captioned = t.captions.isNotEmpty()
    private val allTops = TimelineMath.laneTops((if (captioned) listOf(captionH) else emptyList()) + heights, mainIndex + (if (captioned) 1 else 0), gap, area, scrollY).map { it + rulerH }
    val captionTop: Float? = if (captioned) allTops.firstOrNull() else null
    val tops = if (captioned) allTops.drop(1) else allTops
    val total = heights.sum() + gap * (heights.size - 1).coerceAtLeast(0) + (if (captioned) captionH + gap else 0f)
    val mainTop: Float? = tops.getOrNull(mainIndex)

    fun x(time: Long) = TimelineMath.xOf(time, playhead, centerX, pxPerSecond)
    fun time(x: Float) = TimelineMath.timeAt(x, playhead, centerX, pxPerSecond)

    fun rectOf(laneIndex: Int, c: ClipInfo): Rect {
        val top = tops.getOrElse(laneIndex) { 0f }
        val h = heights.getOrElse(laneIndex) { 48f * dp }
        return Rect(x(c.start), top, x(c.start + c.duration), top + h)
    }

    fun captionRect(c: CaptionInfo): Rect? {
        val top = captionTop ?: return null
        return Rect(x(c.start), top, x(c.start + c.duration), top + captionH)
    }

    fun captionAt(p: Offset): CaptionInfo? = t.captions.firstOrNull { captionRect(it)?.contains(p) == true }

    fun clipAt(p: Offset): ClipInfo? {
        for ((i, lane) in t.lanes.withIndex()) {
            for (c in lane.clips) if (rectOf(i, c).contains(p)) return c
        }
        return null
    }

    /** The trim handle of the selected clip under [p]: the clip and whether it is the head. */
    fun handleAt(p: Offset, selected: ULong?): Pair<ClipInfo, Boolean>? {
        val sel = selected ?: return null
        for ((i, lane) in t.lanes.withIndex()) {
            val c = lane.clips.firstOrNull { it.id == sel } ?: continue
            val r = rectOf(i, c)
            if (p.y < r.top || p.y > r.bottom) return null
            val target = 20f * dp
            if (p.x in (r.left - target)..(r.left + 4f * dp)) return c to true
            if (p.x in (r.right - 4f * dp)..(r.right + target)) return c to false
            return null
        }
        return null
    }

    /** Main-track boundaries: (from, to, time); the first has no `from`, the last no `to`. */
    fun boundaries(): List<Triple<ClipInfo?, ClipInfo?, Long>> {
        val main = t.lanes.getOrNull(mainIndex)?.clips.orEmpty()
        if (main.isEmpty()) return emptyList()
        val out = ArrayList<Triple<ClipInfo?, ClipInfo?, Long>>()
        out += Triple(null, main.first(), main.first().start)
        for (i in 0 until main.size - 1) out += Triple(main[i], main[i + 1], main[i + 1].start)
        out += Triple(main.last(), null, main.last().start + main.last().duration)
        return out
    }

    fun markerRect(from: ClipInfo?, to: ClipInfo?, at: Long): Rect? {
        val top = mainTop ?: return null
        val tr = t.transitions.firstOrNull { it.from == from?.id && it.to == to?.id }
        val cy = top + 32f * dp
        val half = 12f * dp
        val (l, r) = if (tr != null) {
            val a = x(tr.start)
            val b = x(tr.start + tr.duration)
            min(a, x(at) - half) to max(b, x(at) + half)
        } else {
            x(at) - half to x(at) + half
        }
        return Rect(l, cy - 12f * dp, r, cy + 12f * dp)
    }

    fun tap(e: EditorController, p: Offset) {
        for ((from, to, at) in boundaries()) {
            val r = markerRect(from, to, at) ?: continue
            if (r.inflate(4f * dp).contains(p)) {
                val existing = t.transitions.firstOrNull { it.from == from?.id && it.to == to?.id }
                if (existing != null) e.selectTransition(existing) else e.select(null)
                e.submenu = Submenu.None
                e.panel = Panel.Transitions(from?.id, to?.id)
                return
            }
        }
        val cap = captionAt(p)
        if (cap != null) {
            e.selectCaption(cap.id)
            val at = time(p.x).coerceIn(cap.start, cap.start + cap.duration - e.frameTicks)
            e.seek(TimelineMath.snap(at, e.rateNum, e.rateDen), draft = false)
            return
        }
        val c = clipAt(p)
        if (c == null) {
            e.panel = null
            e.select(null)
            return
        }
        e.select(c.id)
        val at = time(p.x).coerceIn(c.start, c.start + c.duration - e.frameTicks)
        e.seek(TimelineMath.snap(at, e.rateNum, e.rateDen), draft = false)
    }

    /** A picked-up clip let go after moving by (dx, dy). */
    fun drop(e: EditorController, c: ClipInfo, dx: Float, dy: Float, start: Offset) {
        val lane = t.lanes.firstOrNull { l -> l.clips.any { it.id == c.id } } ?: return
        if (lane.kind == LaneKind.MAIN) {
            val main = lane.clips.filter { it.id != c.id }
            val finger = time(start.x + dx)
            // where it lands among the others, laid end to end without it
            var cursor = 0L
            val starts = ArrayList<Long>()
            val ends = ArrayList<Long>()
            for (m in main) {
                starts += cursor
                cursor += m.duration
                ends += cursor
            }
            val slot = TimelineMath.slotAt(finger, starts, ends)
            val from = lane.clips.indexOfFirst { it.id == c.id }
            if (slot != from) e.reorder(c.id, slot)
            return
        }
        val newStart = (c.start + TimelineMath.ticksFor(dx, pxPerSecond)).coerceAtLeast(0)
        val laneY = (tops.getOrElse(t.lanes.indexOf(lane)) { 0f }) + dy + heights.getOrElse(t.lanes.indexOf(lane)) { 0f } / 2f
        var target = lane.index
        var best = Float.MAX_VALUE
        for ((i, l) in t.lanes.withIndex()) {
            if (l.kind != lane.kind) continue
            val cy = tops[i] + heights[i] / 2f
            if (abs(cy - laneY) < best) {
                best = abs(cy - laneY)
                target = l.index
            }
        }
        // past the outermost lane of its kind: a new lane
        val outer = t.lanes.filter { it.kind == lane.kind }.maxByOrNull { abs(it.index) }
        if (outer != null) {
            val oi = t.lanes.indexOf(outer)
            val beyond = if (lane.kind == LaneKind.OVERLAY) laneY < tops[oi] - 8f * dp else laneY > tops[oi] + heights[oi] + 8f * dp
            if (beyond) target = if (lane.kind == LaneKind.OVERLAY) outer.index + 1 else outer.index - 1
        }
        e.move(c.id, target, TimelineMath.snap(newStart, e.rateNum, e.rateDen))
    }
}

private fun DrawScope.drawTimeline(g: Geometry, ed: EditorController, measurer: androidx.compose.ui.text.TextMeasurer, trim: TrimDrag?, move: MoveDrag?, missingMediaLabel: String) {
    val dp = g.dp
    @Suppress("UNUSED_VARIABLE") val version = ed.media.version
    val sel = ed.timeline?.selected
    // lanes
    for ((i, lane) in g.t.lanes.withIndex()) {
        val top = g.tops[i]
        if (top > size.height || top + g.heights[i] < g.rulerH) continue
        drawRect(Fc.Lane, Offset(0f, top), Size(size.width, g.heights[i]))
    }
    g.captionTop?.let { drawRect(Fc.Lane, Offset(0f, it), Size(size.width, g.captionH)) }
    clipRect(top = g.rulerH) {
        for (c in g.t.captions) {
            val r = g.captionRect(c) ?: continue
            if (r.right < 0 || r.left > size.width) continue
            drawCaption(c, r, c.id == ed.selectedCaption, dp, measurer)
        }
        for ((i, lane) in g.t.lanes.withIndex()) {
            for (c in lane.clips) {
                var r = g.rectOf(i, c)
                if (trim?.clip == c.id) {
                    val d = (trim.delta.toDouble() / TPS * g.pxPerSecond).toFloat()
                    r = if (trim.head) r.copy(left = min(r.left + d, r.right - 2 * dp)) else r.copy(right = max(r.right + d, r.left + 2 * dp))
                }
                if (move?.clip == c.id) {
                    drawRoundRect(Color.White.copy(alpha = 0.08f), r.topLeft, r.size, CornerRadius(4 * dp))
                    continue
                }
                if (r.right < 0 || r.left > size.width) continue
                drawClip(ed, lane, c, r, c.id == sel, dp, measurer, missingMediaLabel = missingMediaLabel)
            }
        }
        // transitions between main-track clips, and the ends
        for ((from, to, at) in g.boundaries()) {
            val r = g.markerRect(from, to, at) ?: continue
            if (r.right < 0 || r.left > size.width) continue
            if (sel != null && (from?.id == sel || to?.id == sel)) continue
            val tr = g.t.transitions.firstOrNull { it.from == from?.id && it.to == to?.id }
            val selected = tr != null && tr.id == ed.timeline?.selectedTransition
            drawRoundRect(Fc.TransitionMark, r.topLeft, r.size, CornerRadius(4 * dp))
            drawRoundRect(if (selected) Color.White else Fc.TransitionMark, r.topLeft, r.size, CornerRadius(4 * dp), style = Stroke(1 * dp))
            val icon = if (tr != null) Glyphs.Transition else Glyphs.NoTransition
            val s = 16 * dp
            withTransform({ translate(r.center.x - s / 2, r.center.y - s / 2) }) {
                (icon as io.github.artcraftandroid.filmcraft.ui.Glyph.Drawn).draw(this.drawContextScope(s), Color.White)
            }
        }
        // the moving clip's ghost
        if (move != null) {
            for ((i, lane) in g.t.lanes.withIndex()) {
                val c = lane.clips.firstOrNull { it.id == move.clip } ?: continue
                val r = g.rectOf(i, c).translate(move.dx, move.dy)
                drawClip(ed, lane, c, r, true, dp, measurer, alpha = 0.85f, missingMediaLabel = missingMediaLabel)
            }
        }
        // edge shades
        val shade = 16 * dp
        drawRect(Brush.verticalGradient(listOf(Fc.Black, Color.Transparent), startY = g.rulerH, endY = g.rulerH + shade), Offset(0f, g.rulerH), Size(size.width, shade))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Fc.Black), startY = size.height - shade, endY = size.height), Offset(0f, size.height - shade), Size(size.width, shade))
    }
    drawRuler(g, ed, measurer)
    // the playhead (none over an empty timeline)
    if (ed.duration > 0) {
        // from just above the lanes to the timeline's bottom edge (a canvas doesn't clip)
        val top = g.rulerH - 4 * dp
        drawRect(Color.Black.copy(alpha = 0.5f), Offset(g.centerX - 1 * dp, top), Size(3 * dp, size.height - top))
        drawRect(Color.White, Offset(g.centerX - 0.5f * dp, top), Size(1 * dp, size.height - top))
    }
}

private fun DrawScope.drawCaption(c: CaptionInfo, r: Rect, selected: Boolean, dp: Float, measurer: androidx.compose.ui.text.TextMeasurer) {
    val colors = if (selected) Fc.CaptionClipSelected else Fc.CaptionClip
    val corner = CornerRadius(4 * dp)
    drawRoundRect(colors.fill, r.topLeft, r.size, corner)
    clipRect(left = max(r.left, 0f), top = r.top, right = min(r.right, size.width), bottom = r.bottom) {
        val lay = measurer.measure(c.text.replace('\n', ' '), TextStyle(fontFamily = Inter, fontSize = 11.sp, color = colors.content), maxLines = 1)
        drawText(lay, topLeft = Offset(max(r.left, 0f) + 6 * dp, r.top + (r.height - lay.size.height) / 2f))
    }
    if (selected) {
        drawRoundRect(Color.White, r.topLeft + Offset(dp, dp), Size(r.width - 2 * dp, r.height - 2 * dp), corner, style = Stroke(2 * dp))
    } else {
        drawRoundRect(colors.ring, r.topLeft + Offset(0.5f * dp, 0.5f * dp), Size(r.width - dp, r.height - dp), corner, style = Stroke(1 * dp))
    }
}

/** A [DrawScope] sized [s] for a glyph (glyphs draw on their own size). */
private fun DrawScope.drawContextScope(s: Float): DrawScope = object : DrawScope by this {
    override val size: Size get() = Size(s, s)
}

private fun DrawScope.drawClip(
    ed: EditorController,
    lane: LaneInfo,
    c: ClipInfo,
    r: Rect,
    selected: Boolean,
    dp: Float,
    measurer: androidx.compose.ui.text.TextMeasurer,
    missingMediaLabel: String,
    alpha: Float = 1f,
) {
    val colors = when {
        c.kind == ClipKind.MISSING -> Fc.MissingClip
        c.kind == ClipKind.TEXT -> if (selected) Fc.TextClipSelected else Fc.TextClip
        c.kind == ClipKind.AUDIO -> if (c.muted) Fc.MutedClip else Fc.AudioClip
        c.kind == ClipKind.BLANK -> Fc.BlankClip
        c.hasAudio && !c.muted -> Fc.VideoClip
        else -> Fc.SilentVideoClip
    }
    val corner = CornerRadius(4 * dp)
    val path = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(r, corner)) }
    clipPath(path) {
        drawRect(colors.fill.copy(alpha = alpha), r.topLeft, r.size)
        val inset = 1 * dp
        val waveH = if ((c.kind == ClipKind.VIDEO || c.kind == ClipKind.MISSING) && c.hasAudio) 20 * dp else 0f
        // filmstrip
        if (c.kind == ClipKind.VIDEO || c.kind == ClipKind.IMAGE) {
            val stripTop = r.top + inset
            val stripH = r.height - 2 * inset - (if (waveH > 0) waveH + 2 * dp else 0f)
            val aspect = if (c.mediaHeight.toInt() > 0) c.mediaWidth.toFloat() / c.mediaHeight.toFloat() else 16f / 9f
            val tileW = (stripH * aspect).coerceAtLeast(8 * dp)
            val first = floor((max(0f, -r.left) / tileW).toDouble()).toInt()
            var i = first
            while (true) {
                val x = r.left + i * tileW
                if (x > size.width || x > r.right) break
                val secondsIn = (i * tileW / ed.pxPerSecond).toDouble() * c.speed
                val mediaSecond = c.sourceIn.toDouble() / TPS + secondsIn
                val bmp = ed.media.frame(c.path, mediaSecond, stripH.roundToInt().coerceAtLeast(16), c.kind == ClipKind.IMAGE)
                if (bmp != null) {
                    val src = centerCrop(bmp.width, bmp.height, tileW, stripH)
                    drawImage(
                        bmp.asImageBitmapCached(),
                        srcOffset = androidx.compose.ui.unit.IntOffset(src.left.roundToInt(), src.top.roundToInt()),
                        srcSize = androidx.compose.ui.unit.IntSize(src.width.roundToInt().coerceAtLeast(1), src.height.roundToInt().coerceAtLeast(1)),
                        dstOffset = androidx.compose.ui.unit.IntOffset(x.roundToInt(), stripTop.roundToInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(tileW.roundToInt().coerceAtLeast(1), stripH.roundToInt().coerceAtLeast(1)),
                        alpha = alpha,
                    )
                } else {
                    drawRect(Fc.ThumbPlaceholder, Offset(x, stripTop), Size(tileW - 1 * dp, stripH))
                }
                i++
            }
        }
        // waveform lane (videos) or the whole clip (audio)
        if (waveH > 0 || c.kind == ClipKind.AUDIO) {
            val top = if (c.kind == ClipKind.AUDIO) r.top + 18 * dp else r.bottom - waveH - inset
            val bottom = r.bottom - inset
            val wave = when {
                c.muted -> Fc.MutedWave
                c.kind == ClipKind.AUDIO -> Fc.AudioWave
                else -> Fc.VideoWave
            }
            if (c.kind != ClipKind.AUDIO) drawRect(Fc.Black.copy(alpha = 0.05f), Offset(r.left, top), Size(r.width, bottom - top))
            drawWave(ed, c, r.left, r.right, top, bottom, wave, selected, dp)
        }
        // labels
        when (c.kind) {
            ClipKind.TEXT -> {
                val lay = measurer.measure(c.text.replace('\n', ' '), TextStyle(fontFamily = Inter, fontSize = 11.sp, color = colors.content), maxLines = 1)
                drawText(lay, topLeft = Offset(max(r.left, 0f) + 8 * dp, r.top + (r.height - lay.size.height) / 2f))
            }
            ClipKind.AUDIO -> {
                val label = when {
                    c.voiceover -> "● " + c.name
                    // speech made from text shows what it says
                    c.speech && c.text.isNotBlank() -> "“" + c.text.trim().replace('\n', ' ') + "”"
                    else -> "♪ " + c.name
                }
                val lay = measurer.measure(label, TextStyle(fontFamily = Inter, fontSize = 11.sp, color = colors.content), maxLines = 1)
                val x0 = max(r.left, 0f) + 4 * dp
                drawRoundRect(colors.fill.copy(alpha = 0.8f), Offset(x0, r.top + 3 * dp), Size(lay.size.width + 8 * dp, lay.size.height + 4 * dp), CornerRadius(4 * dp))
                drawText(lay, topLeft = Offset(x0 + 4 * dp, r.top + 5 * dp))
            }
            ClipKind.MISSING -> {
                val lay = measurer.measure(missingMediaLabel, TextStyle(fontFamily = Inter, fontSize = 11.sp, color = colors.content), maxLines = 1)
                drawText(lay, topLeft = Offset(max(r.left, 0f) + 8 * dp, r.top + 4 * dp))
            }
            else -> {}
        }
    }
    // ring, and when selected the trim handles and the length badge
    if (selected) {
        val hw = 12 * dp
        drawRect(Color.White, Offset(r.left, r.top), Size(r.width, 2 * dp))
        drawRect(Color.White, Offset(r.left, r.bottom - 2 * dp), Size(r.width, 2 * dp))
        drawHandle(Rect(r.left - hw, r.top, r.left, r.bottom), left = true, dp)
        drawHandle(Rect(r.right, r.top, r.right + hw, r.bottom), left = false, dp)
        if (lane.kind == LaneKind.MAIN || c.kind == ClipKind.TEXT) {
            val label = TimelineMath.badge(c.duration)
            val lay = measurer.measure(label, TextStyle(fontFamily = Inter, fontSize = 13.sp, color = Color(0xFF6BC7FF)))
            val bw = lay.size.width + 8 * dp
            val bx = min(r.right, size.width) - bw - 4 * dp
            if (bx > r.left + 4 * dp) {
                drawRoundRect(Color(0xCC0E1A22), Offset(bx, r.top + 4 * dp), Size(bw, lay.size.height + 2 * dp), CornerRadius(4 * dp))
                drawText(lay, topLeft = Offset(bx + 4 * dp, r.top + 5 * dp))
            }
        }
    } else {
        drawRoundRect(colors.ring, r.topLeft + Offset(0.5f * dp, 0.5f * dp), Size(r.width - dp, r.height - dp), CornerRadius(4 * dp), style = Stroke(1 * dp))
    }
}

private fun DrawScope.drawHandle(r: Rect, left: Boolean, dp: Float) {
    val radius = 4 * dp
    val path = Path().apply {
        addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                r,
                topLeft = CornerRadius(if (left) radius else 0f),
                bottomLeft = CornerRadius(if (left) radius else 0f),
                topRight = CornerRadius(if (left) 0f else radius),
                bottomRight = CornerRadius(if (left) 0f else radius),
            ),
        )
    }
    drawPath(path, Color.White)
    val gh = min(20 * dp, r.height * 0.5f)
    drawRoundRect(Fc.Grip, Offset(r.center.x - 1.5f * dp, r.center.y - gh / 2), Size(3 * dp, gh), CornerRadius(1.5f * dp))
}

private fun DrawScope.drawWave(ed: EditorController, c: ClipInfo, left: Float, right: Float, top: Float, bottom: Float, colors: Fc.WaveColors, selected: Boolean, dp: Float) {
    val peaks = if (c.path.isNotEmpty()) ed.media.wave(c.item.toLong(), c.path, c.mediaDuration) else null
    val fill = if (selected) colors.fillSelected else colors.fill
    val line = if (selected) colors.lineSelected else colors.line
    if (peaks == null || peaks.isEmpty()) {
        drawRect(fill.copy(alpha = 0.4f), Offset(left, bottom - 2 * dp), Size(right - left, 2 * dp))
        return
    }
    val h = bottom - top
    val x0 = max(left, 0f)
    val x1 = min(right, size.width)
    if (x1 <= x0) return
    val step = 2f
    val path = Path()
    val outline = Path()
    path.moveTo(x0, bottom)
    var x = x0
    var firstPoint = true
    val gain = c.volume.coerceIn(0.0, 2.0)
    while (x <= x1) {
        val secs = ((x - left) / ed.pxPerSecond).toDouble() * c.speed + c.sourceIn.toDouble() / TPS
        val i = (secs * MediaCache.WAVE_RATE).toInt().coerceIn(0, peaks.size - 1)
        val v = sqrt((peaks[i] * gain).coerceIn(0.0, 1.0)).toFloat()
        val y = bottom - v * h * WAVE_SCALE
        path.lineTo(x, y)
        if (firstPoint) outline.moveTo(x, y) else outline.lineTo(x, y)
        firstPoint = false
        x += step
    }
    path.lineTo(x1, bottom)
    path.close()
    drawPath(path, fill)
    drawPath(outline, line, style = Stroke(0.66f * dp))
}

/** How much of a waveform lane a full-scale peak fills (after the square root). */
private const val WAVE_SCALE = 0.65f

private fun DrawScope.drawRuler(g: Geometry, ed: EditorController, measurer: androidx.compose.ui.text.TextMeasurer) {
    val dp = g.dp
    val h = 15 * dp
    drawRect(Fc.Black, Offset.Zero, Size(size.width, g.rulerH))
    val labelStyle = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = Fc.RulerLabel, fontFeatureSettings = "tnum")
    val sample = measurer.measure("00:00", labelStyle).size.width.toFloat()
    val step = TimelineMath.rulerStep(g.pxPerSecond, sample * 1.35f, ed.rateNum, ed.rateDen)
    val stepTicks = step.ticks.coerceAtLeast(1)
    val tStart = g.time(0f).coerceAtLeast(0)
    val tEnd = g.time(size.width)
    var t = (tStart / stepTicks) * stepTicks
    while (t <= tEnd) {
        val x = g.x(t)
        val lay = measurer.measure(TimelineMath.rulerLabel(t, step, ed.rateNum, ed.rateDen), labelStyle)
        drawText(lay, topLeft = Offset(x - lay.size.width / 2f, (h - lay.size.height) / 2f))
        val half = g.x(t + stepTicks / 2)
        drawCircle(Fc.RulerTick, 1.2f * dp, Offset(half, h / 2f))
        t += stepTicks
    }
    // the playhead's time, centred on a black box
    val now = timecode(ed.playhead, ed.rateNum, ed.rateDen, Color.White, true)
    val nowLay = measurer.measure(now, TextStyle(fontFamily = Inter, fontSize = 14.sp, fontFeatureSettings = "tnum"))
    val nx = g.centerX - nowLay.size.width / 2f
    val pad = 4 * dp
    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Fc.Black), startX = nx - pad - 6 * dp, endX = nx - pad), Offset(nx - pad - 6 * dp, 0f), Size(6 * dp, h))
    drawRect(Fc.Black, Offset(nx - pad, 0f), Size(nowLay.size.width + 2 * pad, h))
    drawRect(Brush.horizontalGradient(listOf(Fc.Black, Color.Transparent), startX = nx + nowLay.size.width + pad, endX = nx + nowLay.size.width + pad + 6 * dp), Offset(nx + nowLay.size.width + pad, 0f), Size(6 * dp, h))
    drawText(nowLay, topLeft = Offset(nx, (h - nowLay.size.height) / 2f))
    // the total length at the end
    val total = timecode(ed.duration, ed.rateNum, ed.rateDen, Fc.RulerLabel, true)
    val totLay = measurer.measure(total, TextStyle(fontFamily = Inter, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"))
    val tx = size.width - totLay.size.width - pad
    drawRect(Fc.Black, Offset(tx - pad, 0f), Size(totLay.size.width + 2 * pad, h))
    drawText(totLay, topLeft = Offset(tx, (h - totLay.size.height) / 2f))
}

/** "0:04" with the frames after it in smaller type ("0:04 12"). */
fun timecode(ticks: Long, num: Long, den: Long, color: Color, bold: Boolean) = buildAnnotatedString {
    withStyle(SpanStyle(color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)) {
        append(TimelineMath.clock(ticks))
    }
    withStyle(SpanStyle(color = color, fontSize = 10.5.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)) {
        append(" ")
        append(TimelineMath.frameOfSecond(ticks, num, den))
    }
}

private fun centerCrop(w: Int, h: Int, dw: Float, dh: Float): Rect {
    val s = max(dw / w, dh / h)
    val cw = dw / s
    val ch = dh / s
    return Rect((w - cw) / 2f, (h - ch) / 2f, (w + cw) / 2f, (h + ch) / 2f)
}

private val imageBitmaps = java.util.WeakHashMap<android.graphics.Bitmap, androidx.compose.ui.graphics.ImageBitmap>()

private fun android.graphics.Bitmap.asImageBitmapCached(): androidx.compose.ui.graphics.ImageBitmap =
    synchronized(imageBitmaps) { imageBitmaps.getOrPut(this) { this.asImageBitmap() } }
