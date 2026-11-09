package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class ConversationUsageTest {
    @Test fun onlyTheLastConsumerReleasesProtectionAndExceptionsReleaseTemporaryUse() {
        val a = Any(); val b = Any()
        ConversationUsage.update(a, "usage-test")
        ConversationUsage.update(b, "usage-test")
        ConversationUsage.update(a, null)
        assertTrue(ConversationUsage.active("usage-test"))
        ConversationUsage.update(b, null)
        assertFalse(ConversationUsage.active("usage-test"))
        assertThrows(IllegalStateException::class.java) {
            ConversationUsage.hold("usage-throw") { error("interrupted") }
        }
        assertFalse(ConversationUsage.active("usage-throw"))
    }
}
