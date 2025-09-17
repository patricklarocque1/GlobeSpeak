package com.globespeak.mobile.ui.bench

import org.junit.Assert.assertTrue
import org.junit.Test

class BenchFormattingTest {
    @Test fun formats_success_row() {
        val row = formatResultRow("Standard", 123, "hola", null)
        assertTrue(row.contains("Standard"))
        assertTrue(row.contains("123ms"))
        assertTrue(row.contains("hola"))
    }

    @Test fun formats_error_row() {
        val row = formatResultRow("Advanced", -1, null, "OOM")
        assertTrue(row.contains("Advanced"))
        assertTrue(row.contains("error"))
        assertTrue(row.contains("OOM"))
    }
}

