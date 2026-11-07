package com.example.audiovideotrimmer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorProjectTest {

    private fun newProject(durationMs: Long = 10_000L): EditorProject =
        EditorProject().also { it.newProject("content://a", "a.mp3", durationMs, false) }

    private fun EditorProject.select(startMs: Long, endMs: Long) {
        setSelectionStart(startMs)
        setSelectionEnd(endMs)
    }

    @Test
    fun deleteRemovesSelection() {
        val p = newProject()
        p.select(2_000, 4_000)
        assertTrue(p.deleteSelection())
        assertEquals(8_000L, p.totalMs)
        assertEquals(listOf(Segment(1, 0, 2_000), Segment(1, 4_000, 10_000)), p.segments)
    }

    @Test
    fun cannotDeleteEverything() {
        val p = newProject()
        assertFalse(p.deleteSelection())
        assertEquals(10_000L, p.totalMs)
    }

    @Test
    fun copyThenPasteElsewhere() {
        val p = newProject()
        p.select(0, 2_000)
        p.copySelection()
        assertTrue(p.pasteAt(5_000))
        assertEquals(12_000L, p.totalMs)
        assertEquals(
            listOf(Segment(1, 0, 5_000), Segment(1, 0, 2_000), Segment(1, 5_000, 10_000)),
            p.segments
        )
    }

    @Test
    fun cutThenPasteAtSamePlaceRestoresSingleSegment() {
        val p = newProject()
        p.select(2_000, 4_000)
        assertTrue(p.cutSelection())
        assertEquals(8_000L, p.totalMs)
        assertTrue(p.pasteAt(2_000))
        assertEquals(listOf(Segment(1, 0, 10_000)), p.segments)
    }

    @Test
    fun keepOnlySelectionTrims() {
        val p = newProject()
        p.select(1_000, 3_000)
        assertTrue(p.keepOnlySelection())
        assertEquals(listOf(Segment(1, 1_000, 3_000)), p.segments)
        assertEquals(2_000L, p.totalMs)
    }

    @Test
    fun insertAnotherFileInTheMiddle() {
        val p = newProject()
        val other = p.addSource("content://b", "b.mp3", 3_000L, false)
        p.insertSourceAt(4_000, other)
        assertEquals(13_000L, p.totalMs)
        assertEquals(
            listOf(Segment(1, 0, 4_000), Segment(2, 0, 3_000), Segment(1, 4_000, 10_000)),
            p.segments
        )
    }

    @Test
    fun undoRestoresPreviousState() {
        val p = newProject()
        p.select(2_000, 4_000)
        p.deleteSelection()
        assertTrue(p.undo())
        assertEquals(listOf(Segment(1, 0, 10_000)), p.segments)
        assertFalse(p.undo())
    }

    @Test
    fun marksAreSortedAndValidated() {
        val p = newProject()
        assertEquals(MarkResult.ADDED, p.addMark(7_000))
        assertEquals(MarkResult.ADDED, p.addMark(3_000))
        assertEquals(listOf(3_000L, 7_000L), p.marks)
        assertEquals(MarkResult.DUPLICATE, p.addMark(3_050))
        assertEquals(MarkResult.TOO_CLOSE_TO_EDGE, p.addMark(50))
        assertEquals(MarkResult.TOO_CLOSE_TO_EDGE, p.addMark(9_950))
    }

    @Test
    fun splitProducesOnePartPerMarkPlusOne() {
        val p = newProject()
        p.addMark(3_000)
        p.addMark(7_000)
        assertEquals(
            listOf(
                listOf(Segment(1, 0, 3_000)),
                listOf(Segment(1, 3_000, 7_000)),
                listOf(Segment(1, 7_000, 10_000))
            ),
            p.splitParts()
        )
    }

    @Test
    fun splitAfterEditUsesTimelineTime() {
        val p = newProject()
        p.select(2_000, 4_000)
        p.deleteSelection()               // timeline: 0-2s | 4-10s of the source
        p.addMark(5_000)                  // 5 s on the edited timeline = 7 s in the source
        val parts = p.splitParts()
        assertEquals(2, parts.size)
        assertEquals(listOf(Segment(1, 0, 2_000), Segment(1, 4_000, 7_000)), parts[0])
        assertEquals(listOf(Segment(1, 7_000, 10_000)), parts[1])
    }

    @Test
    fun locateMapsTimelineToSegment() {
        val p = newProject()
        p.select(2_000, 4_000)
        p.deleteSelection()
        assertEquals(0 to 1_500L, p.locate(1_500))
        assertEquals(1 to 500L, p.locate(2_500))
    }

    @Test
    fun timeFormatting() {
        assertEquals("01:30.5", TimeFormat.format(90_500))
        assertEquals("1:02:03.4", TimeFormat.format(3_723_400))
        assertEquals("1 minute 30.5 seconds", TimeFormat.spoken(90_500))
        assertEquals("1 minute 1 second", TimeFormat.spoken(61_000))
        assertEquals("0 seconds", TimeFormat.spoken(0))
    }

    @Test
    fun timeParsing() {
        assertEquals(90_500L, TimeFormat.parse("1:30.5"))
        assertEquals(90_000L, TimeFormat.parse("90"))
        assertEquals(5_400_000L + 30_000L, TimeFormat.parse("1:30:30"))
        assertNull(TimeFormat.parse("abc"))
        assertNull(TimeFormat.parse(""))
    }

    @Test
    fun removeRangesDeletesSeveralPartsInOneUndoStep() {
        val p = newProject()
        val removed = p.removeRanges(listOf(6_000L to 7_000L, 1_000L to 2_000L))
        assertEquals(2_000L, removed)
        assertEquals(8_000L, p.totalMs)
        assertEquals(
            listOf(Segment(1, 0, 1_000), Segment(1, 2_000, 6_000), Segment(1, 7_000, 10_000)),
            p.segments
        )
        assertTrue(p.undo())
        assertEquals(listOf(Segment(1, 0, 10_000)), p.segments)
    }

    @Test
    fun removeRangesNeverEmptiesTheProject() {
        val p = newProject()
        assertEquals(0L, p.removeRanges(listOf(0L to 10_000L)))
        assertEquals(10_000L, p.totalMs)
        assertEquals(0L, p.removeRanges(emptyList()))
    }

    @Test
    fun clockFormat() {
        assertEquals("01:05", TimeFormat.clock(65_400))
        assertEquals("1:01:01", TimeFormat.clock(3_661_000))
    }

    @Test
    fun trimBeforeAndAfter() {
        val p = newProject()
        assertTrue(p.trimBefore(2_000))
        assertEquals(listOf(Segment(1, 2_000, 10_000)), p.segments)
        assertTrue(p.trimAfter(5_000))
        assertEquals(listOf(Segment(1, 2_000, 7_000)), p.segments)
        assertFalse(p.trimBefore(0))
        assertFalse(p.trimAfter(5_000))
    }

    @Test
    fun marksEveryInterval() {
        val p = newProject(65_000L)
        assertEquals(6, p.addMarksEvery(10_000L))
        assertEquals(listOf(10_000L, 20_000L, 30_000L, 40_000L, 50_000L, 60_000L), p.marks)
        assertEquals(7, p.splitParts().size)
    }
}
