package com.example.audiovideotrimmer

import kotlin.math.abs

/** One imported audio/video file. Kept as plain data so the model can be unit-tested. */
data class MediaSource(
    val id: Int,
    val uriString: String,
    val name: String,
    val durationMs: Long,
    val hasVideo: Boolean
)

/** A piece of a source file. The timeline is simply an ordered list of segments. */
data class Segment(val sourceId: Int, val startMs: Long, val endMs: Long) {
    val length: Long get() = endMs - startMs
}

enum class MarkResult { ADDED, DUPLICATE, TOO_CLOSE_TO_EDGE }

/**
 * Non-destructive edit model.
 *
 * Nothing is written to disk while editing. Every operation (cut, copy, delete,
 * paste, trim, insert) only rearranges the list of [Segment]s. The final file
 * is produced once, when the user saves or splits.
 */
class EditorProject {

    private val sourceList = mutableListOf<MediaSource>()
    private val markList = mutableListOf<Long>()
    private val undoStack = ArrayDeque<List<Segment>>()
    private var nextSourceId = 1

    val sources: List<MediaSource> get() = sourceList
    val marks: List<Long> get() = markList

    var segments: List<Segment> = emptyList()
        private set
    var clipboard: List<Segment> = emptyList()
        private set
    var selectionStartMs: Long = 0L
        private set
    var selectionEndMs: Long = 0L
        private set

    val isEmpty: Boolean get() = segments.isEmpty()
    val totalMs: Long get() = segments.sumOf { it.length }
    val clipboardMs: Long get() = clipboard.sumOf { it.length }
    val isVideo: Boolean get() = sourceList.firstOrNull()?.hasVideo == true
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val isEdited: Boolean get() = undoStack.isNotEmpty() || sourceList.size > 1
    val selectionIsEverything: Boolean
        get() = selectionStartMs <= 0L && selectionEndMs >= totalMs

    fun source(id: Int): MediaSource = sourceList.first { it.id == id }

    // ---------------------------------------------------------------- project

    fun newProject(uriString: String, name: String, durationMs: Long, hasVideo: Boolean): MediaSource {
        sourceList.clear()
        markList.clear()
        undoStack.clear()
        clipboard = emptyList()
        nextSourceId = 1
        val src = addSource(uriString, name, durationMs, hasVideo)
        segments = listOf(Segment(src.id, 0L, durationMs))
        selectAll()
        return src
    }

    fun addSource(uriString: String, name: String, durationMs: Long, hasVideo: Boolean): MediaSource {
        val src = MediaSource(nextSourceId++, uriString, name, durationMs, hasVideo)
        sourceList.add(src)
        return src
    }

    /** Forgets everything (used when a source file was deleted). */
    fun clear() {
        sourceList.clear()
        markList.clear()
        undoStack.clear()
        clipboard = emptyList()
        segments = emptyList()
        selectionStartMs = 0L
        selectionEndMs = 0L
    }

    // -------------------------------------------------------------- selection

    fun selectAll() {
        selectionStartMs = 0L
        selectionEndMs = totalMs
    }

    fun setSelectionStart(ms: Long) {
        val total = totalMs
        val v = ms.coerceIn(0L, maxOf(0L, total - MIN_SELECTION_MS))
        selectionStartMs = v
        if (selectionEndMs < v + MIN_SELECTION_MS) selectionEndMs = total
    }

    fun setSelectionEnd(ms: Long) {
        val total = totalMs
        val v = ms.coerceIn(minOf(total, MIN_SELECTION_MS), total)
        selectionEndMs = v
        if (selectionStartMs > v - MIN_SELECTION_MS) selectionStartMs = 0L
    }

    // ------------------------------------------------------------------ edits

    /** Copies the selection to the clipboard. Returns its length in ms. */
    fun copySelection(): Long {
        clipboard = slice(selectionStartMs, selectionEndMs)
        return clipboardMs
    }

    /** Removes the selection. Refuses to remove everything. */
    fun deleteSelection(): Boolean {
        if (selectionIsEverything) return false
        val left = slice(0L, selectionStartMs)
        val right = slice(selectionEndMs, totalMs)
        commit(left + right)
        return true
    }

    /** Copy + delete. The removed part stays on the clipboard so it can be pasted elsewhere. */
    fun cutSelection(): Boolean {
        if (selectionIsEverything) return false
        copySelection()
        return deleteSelection()
    }

    /** Keeps only the selection (classic "trim"). */
    fun keepOnlySelection(): Boolean {
        if (selectionIsEverything) return false
        commit(slice(selectionStartMs, selectionEndMs))
        return true
    }

    /** Removes everything before [posMs]. */
    fun trimBefore(posMs: Long): Boolean {
        val p = posMs.coerceIn(0L, totalMs)
        if (p <= 0L || p >= totalMs) return false
        commit(slice(p, totalMs))
        return true
    }

    /** Removes everything after [posMs]. */
    fun trimAfter(posMs: Long): Boolean {
        val p = posMs.coerceIn(0L, totalMs)
        if (p <= 0L || p >= totalMs) return false
        commit(slice(0L, p))
        return true
    }

    /** Inserts the clipboard at [posMs]. */
    fun pasteAt(posMs: Long): Boolean {
        if (clipboard.isEmpty()) return false
        insertSegments(posMs, clipboard)
        return true
    }

    /** Inserts a whole source file at [posMs]. */
    fun insertSourceAt(posMs: Long, source: MediaSource) {
        insertSegments(posMs, listOf(Segment(source.id, 0L, source.durationMs)))
    }

    /**
     * Removes several timeline ranges in ONE undoable step (used by silence removal).
     * Returns the number of milliseconds removed (0 = nothing changed).
     */
    fun removeRanges(ranges: List<Pair<Long, Long>>): Long {
        val sorted = ranges.filter { it.second > it.first }.sortedBy { it.first }
        if (sorted.isEmpty()) return 0L
        val total = totalMs
        val keep = ArrayList<Segment>()
        var cursor = 0L
        for ((start, end) in sorted) {
            if (start > cursor) keep.addAll(slice(cursor, minOf(start, total)))
            cursor = maxOf(cursor, end)
        }
        if (cursor < total) keep.addAll(slice(cursor, total))
        val kept = keep.sumOf { it.length }
        if (keep.isEmpty() || kept >= total) return 0L
        commit(keep)
        return total - kept
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        segments = undoStack.removeLast()
        markList.clear()
        selectAll()
        return true
    }

    private fun insertSegments(posMs: Long, items: List<Segment>) {
        val p = posMs.coerceIn(0L, totalMs)
        val left = slice(0L, p)
        val right = slice(p, totalMs)
        commit(left + items + right)
    }

    private fun commit(newSegments: List<Segment>) {
        undoStack.addLast(segments)
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        segments = normalize(newSegments)
        markList.clear()
        selectAll()
    }

    // ------------------------------------------------------------------ marks

    fun addMark(posMs: Long): MarkResult {
        val total = totalMs
        if (posMs < MIN_PART_MS || posMs > total - MIN_PART_MS) return MarkResult.TOO_CLOSE_TO_EDGE
        if (markList.any { abs(it - posMs) < MIN_PART_MS }) return MarkResult.DUPLICATE
        markList.add(posMs)
        markList.sort()
        return MarkResult.ADDED
    }

    /** Adds a mark every [intervalMs]; returns how many were added. */
    fun addMarksEvery(intervalMs: Long): Int {
        if (intervalMs < MIN_PART_MS) return 0
        var count = 0
        var t = intervalMs
        while (t <= totalMs - MIN_PART_MS) {
            if (addMark(t) == MarkResult.ADDED) count++
            t += intervalMs
        }
        return count
    }

    fun removeMark(index: Int) {
        if (index in markList.indices) markList.removeAt(index)
    }

    fun clearMarks() = markList.clear()

    /** One list of segments per output file when splitting at the marks. */
    fun splitParts(): List<List<Segment>> {
        val points = listOf(0L) + markList + listOf(totalMs)
        return points.zipWithNext().map { (a, b) -> slice(a, b) }
    }

    // ---------------------------------------------------------------- helpers

    /** Segments that cover the timeline range [fromMs, toMs). */
    fun slice(fromMs: Long, toMs: Long): List<Segment> {
        val out = ArrayList<Segment>()
        var cursor = 0L
        for (s in segments) {
            val segStart = cursor
            val segEnd = cursor + s.length
            val a = maxOf(fromMs, segStart)
            val b = minOf(toMs, segEnd)
            if (b - a > 0L) {
                out.add(Segment(s.sourceId, s.startMs + (a - segStart), s.startMs + (b - segStart)))
            }
            cursor = segEnd
            if (cursor >= toMs) break
        }
        return out
    }

    /** Maps a timeline position to (segment index, offset inside that segment). */
    fun locate(posMs: Long): Pair<Int, Long> {
        if (segments.isEmpty()) return 0 to 0L
        var remaining = posMs.coerceIn(0L, totalMs)
        for ((i, s) in segments.withIndex()) {
            if (remaining < s.length || i == segments.lastIndex) {
                return i to remaining.coerceAtMost(s.length)
            }
            remaining -= s.length
        }
        return 0 to 0L
    }

    fun timelineStartOf(index: Int): Long = segments.take(index).sumOf { it.length }

    private fun normalize(list: List<Segment>): List<Segment> {
        val out = ArrayList<Segment>()
        for (s in list) {
            if (s.length <= 0L) continue
            val last = out.lastOrNull()
            if (last != null && last.sourceId == s.sourceId && last.endMs == s.startMs) {
                out[out.size - 1] = Segment(last.sourceId, last.startMs, s.endMs)
            } else {
                out.add(s)
            }
        }
        return out
    }

    companion object {
        const val MIN_SELECTION_MS = 100L
        const val MIN_PART_MS = 200L
        const val MAX_UNDO = 50
    }
}
