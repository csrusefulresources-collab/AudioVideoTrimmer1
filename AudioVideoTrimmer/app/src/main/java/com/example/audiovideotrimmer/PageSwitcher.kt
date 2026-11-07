package com.example.audiovideotrimmer

import android.view.View
import androidx.core.widget.NestedScrollView

/** A screen that is divided into sections; the section buttons live in the bottom section bar. */
interface SectionScreen {
    val sectionTitles: List<String>
    val selectedSection: Int
    fun selectSection(index: Int)

    /** Puts the screen reader focus on the title of this screen. */
    fun focusStart()
}

/** Shows exactly one "page" (a group of views) at a time, so the other sections are not reachable. */
class PageSwitcher(
    private val pages: List<View>,
    private val scroll: NestedScrollView?,
    initial: Int = 0,
    private val onChange: (Int) -> Unit = {}
) {
    var selected = initial.coerceIn(0, pages.size - 1)
        private set

    init {
        show(selected)
    }

    fun select(index: Int) {
        if (index in pages.indices) show(index)
    }

    private fun show(index: Int) {
        selected = index
        pages.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
        scroll?.scrollTo(0, 0)
        onChange(index)
    }
}
