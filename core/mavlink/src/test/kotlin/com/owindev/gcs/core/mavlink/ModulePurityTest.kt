package com.owindev.gcs.core.mavlink

import org.amshove.kluent.invoking
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Smoke test: this module must stay pure Kotlin, so no Android class can be on its classpath. */
@Tag("REQ-001")
class ModulePurityTest {

    private lateinit var classLoader: ClassLoader

    @BeforeEach
    fun setUp() {
        classLoader = javaClass.classLoader
    }

    @Test
    fun `GIVEN the module classpath WHEN loading an Android framework class THEN it is not found`() {
        val androidClass = "android.os.Build"

        val result = invoking { classLoader.loadClass(androidClass) }

        result shouldThrow ClassNotFoundException::class
    }
}
