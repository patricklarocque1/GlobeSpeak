package com.globespeak.engine.backend

import org.junit.Assert.assertEquals
import org.junit.Test

class NllbLangTest {
    @Test fun map_en_fr_basic() {
        assertEquals("eng_Latn", NllbLang.toNllb("en"))
        assertEquals("fra_Latn", NllbLang.toNllb("fr"))
    }
}
