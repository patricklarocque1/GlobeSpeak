package com.globespeak.mobile.ui.bench

import org.junit.Assert.assertTrue
import org.junit.Test

class BenchFormattingTest {
    @Test fun formats_success_row() {
        val res = BenchResult("Standard", listOf(100, 120, 110), "hola", null)
        val row = formatResultRow(res)
        assertTrue(row.contains("Standard"))
        assertTrue(row.contains("avg=110"))
        assertTrue(row.contains("hola"))
    }

    @Test fun formats_error_row() {
        val res = BenchResult("Advanced", emptyList(), null, "OOM")
        val row = formatResultRow(res)
        assertTrue(row.contains("Advanced"))
        assertTrue(row.contains("error"))
        assertTrue(row.contains("OOM"))
    }
}
