package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class ConversationUsageTest {
    @Test fun cancelledQueuedWorkDoesNotLeakProtectionAndRunningWorkKeepsItUntilExit() {
        val queued = mutableListOf<Runnable>()
        ConversationUsage.execute(java.util.concurrent.Executor { queued.add(it) }, "queued-usage") { error("must not run") }
        assertTrue(ConversationUsage.active("queued-usage"))
        ConversationUsage.cancelQueued(queued)
        assertFalse(ConversationUsage.active("queued-usage"))
        queued.single().run()
        ConversationUsage.execute(java.util.concurrent.Executor { it.run() }, "running-usage") {
            assertTrue(ConversationUsage.active("running-usage"))
        }
        assertFalse(ConversationUsage.active("running-usage"))
    }
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
