package com.owindev.gcs.core.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherExtensionTest {

    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private lateinit var mainThreadWork: suspend () -> String

    @BeforeEach
    fun setUp() {
        mainThreadWork = { withContext(Dispatchers.Main) { DONE } }
    }

    @Test
    fun `GIVEN the extension is registered WHEN running work on Dispatchers Main THEN it completes`() = runTest {
        val expected = DONE

        val deferred = async { mainThreadWork() }
        val result = deferred.await()

        result shouldBeEqualTo expected
    }

    private companion object {
        const val DONE = "done"
    }
}
