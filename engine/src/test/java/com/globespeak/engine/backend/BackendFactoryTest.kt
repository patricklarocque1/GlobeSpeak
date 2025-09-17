package com.globespeak.engine.backend

import org.junit.Assert.assertEquals
import org.junit.Test

class BackendFactoryTest {
    private class Cap(private val ok: Boolean) : DeviceCapability(android.app.Application()) {
        override fun supportsAdvanced(): Boolean = ok
    }
    private class Models(private val ok: Boolean) : ModelLocator(android.app.Application()) {
        override fun hasNllbModel(): Boolean = ok
    }

    @Test fun standardSelected_usesMlkit() {
        val t = BackendFactory.selectType("standard", Cap(true), Models(true))
        assertEquals("mlkit", t)
    }

    @Test fun advancedSelected_butNoCap_fallsbackMlkit() {
        val t = BackendFactory.selectType("advanced", Cap(false), Models(true))
        assertEquals("mlkit", t)
    }

    @Test fun advancedSelected_andCapAndModel_usesNllb() {
        val t = BackendFactory.selectType("advanced", Cap(true), Models(true))
        assertEquals("nllb", t)
    }
}
