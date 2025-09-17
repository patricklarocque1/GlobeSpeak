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

class BackendFactoryInfoTest {
    private class Cap(private val ok: Boolean) : DeviceCapability(android.app.Application()) {
        override fun supportsAdvanced(): Boolean = ok
    }
    private class Models(private val ok: Boolean) : ModelLocator(android.app.Application()) {
        override fun hasNllbModel(): Boolean = ok
    }

    @org.junit.Test
    fun advancedSelected_modelMissing_fallbackInfo() {
        val (active, reason) = run {
            val pair = BackendFactory.run {
                val res = javaClass.getDeclaredMethod(
                    "resolveActive",
                    String::class.java,
                    DeviceCapability::class.java,
                    ModelLocator::class.java
                )
                res.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                res.invoke(BackendFactory, "advanced", Cap(true), Models(false)) as Pair<String, String?>
            }
            pair
        }
        org.junit.Assert.assertEquals("standard", active)
        org.junit.Assert.assertEquals("model missing", reason)
    }

    @org.junit.Test
    fun standardSelected_activeStandard_noReason() {
        val (active, reason) = run {
            val pair = BackendFactory.run {
                val res = javaClass.getDeclaredMethod(
                    "resolveActive",
                    String::class.java,
                    DeviceCapability::class.java,
                    ModelLocator::class.java
                )
                res.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                res.invoke(BackendFactory, "standard", Cap(true), Models(true)) as Pair<String, String?>
            }
            pair
        }
        org.junit.Assert.assertEquals("standard", active)
        org.junit.Assert.assertNull(reason)
    }
}
