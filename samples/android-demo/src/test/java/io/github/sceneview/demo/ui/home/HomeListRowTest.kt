package io.github.sceneview.demo.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** The corner and column arithmetic behind the home list's grouped rows. */
class HomeListRowTest {

    @Test
    fun a_lone_row_is_rounded_all_round() {
        assertEquals(RowCorners(true, true, true, true), rowCorners(index = 0, count = 1, columns = 1))
    }

    @Test
    fun a_single_column_group_rounds_only_its_ends() {
        assertEquals(RowCorners(true, true, false, false), rowCorners(0, 3, 1))
        assertEquals(RowCorners(false, false, false, false), rowCorners(1, 3, 1))
        assertEquals(RowCorners(false, false, true, true), rowCorners(2, 3, 1))
    }

    @Test
    fun a_two_column_group_with_a_short_last_line_ends_in_a_step() {
        // 0 1
        // 2
        assertEquals(RowCorners(true, false, false, false), rowCorners(0, 3, 2))
        // Top-end is outer; nothing under it, so bottom-end is outer too — the step.
        assertEquals(RowCorners(false, true, true, false), rowCorners(1, 3, 2))
        assertEquals(RowCorners(false, false, true, true), rowCorners(2, 3, 2))
    }

    @Test
    fun a_full_two_by_two_group_has_four_outer_corners() {
        assertEquals(RowCorners(true, false, false, false), rowCorners(0, 4, 2))
        assertEquals(RowCorners(false, true, false, false), rowCorners(1, 4, 2))
        assertEquals(RowCorners(false, false, false, true), rowCorners(2, 4, 2))
        assertEquals(RowCorners(false, false, true, false), rowCorners(3, 4, 2))
    }

    @Test
    fun a_phone_gets_one_column_and_a_wide_tablet_several() {
        assertEquals(1, homeListColumns(360))
        assertEquals(1, homeListColumns(411))
        assertEquals(1, homeListColumns(600))
        assertEquals(2, homeListColumns(840))
        assertEquals(3, homeListColumns(1280))
    }
}
