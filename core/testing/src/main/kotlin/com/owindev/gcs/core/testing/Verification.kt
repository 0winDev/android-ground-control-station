package com.owindev.gcs.core.testing

import io.mockk.MockKVerificationScope
import io.mockk.coVerify
import io.mockk.verify

/** Verifies that every call in [verifyBlock] happened exactly once. */
fun verifyOnce(verifyBlock: MockKVerificationScope.() -> Unit) {
    verify(exactly = 1, verifyBlock = verifyBlock)
}

/** Verifies that no call in [verifyBlock] happened. */
fun verifyNever(verifyBlock: MockKVerificationScope.() -> Unit) {
    verify(exactly = 0, verifyBlock = verifyBlock)
}

/** Suspend version of [verifyOnce]. */
fun coVerifyOnce(verifyBlock: suspend MockKVerificationScope.() -> Unit) {
    coVerify(exactly = 1, verifyBlock = verifyBlock)
}

/** Suspend version of [verifyNever]. */
fun coVerifyNever(verifyBlock: suspend MockKVerificationScope.() -> Unit) {
    coVerify(exactly = 0, verifyBlock = verifyBlock)
}
