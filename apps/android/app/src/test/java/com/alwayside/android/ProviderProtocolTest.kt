package com.alwayside.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException

class ProviderProtocolTest {
    @Test fun onlyCredentialFreeHttpsV1EndpointsAreAllowed() {
        assertEquals("https://api.openai.com/v1/responses", ProviderProtocol.endpoint("https://api.openai.com/v1/", "responses").toString())
        assertEquals("https://example.com/proxy/v1/audio/transcriptions", ProviderProtocol.endpoint("https://example.com/proxy/v1", "audio/transcriptions").toString())
        listOf(
            "http://example.com/v1", "https://key@example.com/v1", "https://example.com/v1?key=secret",
            "https://example.com/v1#secret", "https://example.com/v2", "https://example.com/a/../v1",
            "https://example.com/%76%31", "https://example.com:0/v1", "https://example.com:99999/v1",
        ).forEach { address -> assertThrows(ProviderException::class.java) { ProviderProtocol.endpoint(address, "responses") } }
    }

    @Test fun sseAcceptsBomCrLfCommentsMultilineAndIgnoresUnterminatedEvent() {
        val stream = stream("\uFEFF: ping\r\nevent: response.output_text.delta\r\ndata: first\r\ndata: second\r\n\r\ndata: truncated")
        assertEquals(ServerEvent("response.output_text.delta", "first\nsecond"), stream.next())
        assertNull(stream.next())
        assertEquals(ServerEvent("message", "ok"), stream("data: ok\r\r").next())
    }

    @Test fun sseBoundsMemoryAndHonorsCancellation() {
        assertThrows(ProviderException::class.java) { EventStream("data: oversized\n\n".byteInputStream(), RequestCancellation(), Long.MAX_VALUE, maxLine = 8).next() }
        assertThrows(ProviderException::class.java) { EventStream("data: a\ndata: b\n\n".byteInputStream(), RequestCancellation(), Long.MAX_VALUE, maxEvent = 3).next() }
        val cancellation = RequestCancellation()
        val stream = EventStream("data: ok\n\n".byteInputStream(), cancellation, Long.MAX_VALUE)
        cancellation.cancel()
        assertThrows(CancellationException::class.java) { stream.next() }
    }

    @Test fun cancelClosesAttachedConnectionAndRejectsFurtherWork() {
        val connection = FakeConnection()
        val cancellation = RequestCancellation()
        cancellation.attach(connection)
        cancellation.cancel()
        assertTrue(connection.closed)
        assertThrows(CancellationException::class.java) { cancellation.throwIfCancelled() }
        assertThrows(CancellationException::class.java) { cancellation.attach(FakeConnection()) }
    }

    private fun stream(text: String) = EventStream(text.byteInputStream(), RequestCancellation(), Long.MAX_VALUE)

    private class FakeConnection : HttpURLConnection(URL("https://example.com/v1")) {
        var closed = false
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun connect() = Unit
    }
}
