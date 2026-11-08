package com.alwayside.android

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubtitleArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    // The boundary itself uses Android JSON in production; tests inject a lossless map transport.
    private class MapTransport {
        val maps = mutableMapOf<String, Map<String, Any?>>()
        var fail = false
        fun encode(map: Map<String, Any?>): String {
            check(!fail) { "Simulated encoding failure" }
            val key = "metadata-${maps.size}"
            maps[key] = map
            return key
        }
        fun decode(text: String): Map<String, Any?> = requireNotNull(maps[text])
    }

    private fun complete(method: SubtitleMethod = SubtitleMethod.WHISPER) = SubtitleBundle(
        "a".repeat(64), method, 4000, "b".repeat(64),
        listOf(SubtitleCue(1, 100, 1000, "First line"), SubtitleCue(2, 2500, 3900, "Second line")),
        mapOf(1 to "第一行", 2 to "第二行"), 2, 2, true, "gpt-4.1-mini", 1L)

    @Test fun draftAndFailedReplacementKeepThePublishedBilingualVersion() {
        val folder = temporary.newFolder()
        val transport = MapTransport()
        val archive = SubtitleArchive(folder, transport::encode, transport::decode)
        val original = complete()
        archive.publish("recording", original)
        val draft = original.copy(translations = mapOf(1 to "新的译文"), updatedAt = 2)
        archive.saveDraft("recording", draft)
        assertEquals(original, archive.load("recording", original.method))
        assertEquals(draft, archive.loadDraft("recording", original.method))
        assertThrows(IllegalArgumentException::class.java) { archive.publish("recording", draft) }
        transport.fail = true
        assertThrows(IllegalStateException::class.java) { archive.publish("recording", original.copy(updatedAt = 3)) }
        transport.fail = false
        val reopened = SubtitleArchive(folder, transport::encode, transport::decode)
        assertEquals(original, reopened.load("recording", original.method))
        assertEquals(draft, reopened.loadDraft("recording", original.method))
        val replacement = original.copy(translations = mapOf(1 to "新一", 2 to "新二"), updatedAt = 4)
        reopened.publish("recording", replacement)
        assertEquals(replacement, reopened.load("recording", original.method))
        assertNull(reopened.loadDraft("recording", original.method))
    }

    @Test fun recordingsAreIsolatedAndIdsCannotEscapeTheArchive() {
        val folder = temporary.newFolder()
        val transport = MapTransport()
        val archive = SubtitleArchive(folder, transport::encode, transport::decode)
        val hostileId = "../../recordings/private.wav"
        archive.publish(hostileId, complete())
        archive.publish("other", complete())
        assertEquals(2, folder.listFiles()!!.size)
        assertTrue(folder.listFiles()!!.all { it.name.matches(Regex("[a-f0-9]{64}")) })
        archive.delete(hostileId, SubtitleMethod.WHISPER)
        assertNull(archive.load(hostileId, SubtitleMethod.WHISPER))
        archive.publish(hostileId, complete())
        archive.delete(hostileId)
        assertNull(archive.load(hostileId, SubtitleMethod.WHISPER))
        assertNotNull(archive.load("other", SubtitleMethod.WHISPER))
        archive.clear()
        assertTrue(folder.listFiles()!!.isEmpty())
    }

    @Test fun corruptPublicationAndOversizedDraftFailExplicitly() {
        val folder = temporary.newFolder()
        val transport = MapTransport()
        val archive = SubtitleArchive(folder, transport::encode, transport::decode)
        archive.publish("recording", complete())
        val method = folder.listFiles()!!.single().resolve(SubtitleMethod.WHISPER.id)
        val current = File(method, "current").readText()
        File(method, "$current/zh-CN.srt").writeText("corrupt")
        assertThrows(IllegalArgumentException::class.java) { archive.load("recording", SubtitleMethod.WHISPER) }
        File(method, "draft.json").outputStream().use { output ->
            val buffer = ByteArray(1024 * 1024)
            repeat(17) { output.write(buffer) }
        }
        assertThrows(IllegalArgumentException::class.java) { archive.loadDraft("recording", SubtitleMethod.WHISPER) }
    }

    @Test fun codecRejectsUnsafeMetadataAndMismatchedOrInvalidCueTimelines() {
        val good = complete()
        assertEquals(good, SubtitleArchiveCodec.fromMap(SubtitleArchiveCodec.toMap(good)))
        val longModel = good.copy(translationModel = "m".repeat(200))
        assertEquals(longModel, SubtitleArchiveCodec.fromMap(SubtitleArchiveCodec.toMap(longModel)))
        val invalid = listOf(
            good.copy(requestSignature = "secret-token"),
            good.copy(translationModel = "/private/model"),
            good.copy(translationModel = "m".repeat(201)),
            good.copy(translations = mapOf(1 to "一", 3 to "三")),
            good.copy(cues = listOf(SubtitleCue(1, 100, 4001, "Beyond recording"))),
            good.copy(cues = listOf(SubtitleCue(1, 100, 100, "Empty interval"))),
            good.copy(cues = listOf(SubtitleCue(1, 100, 500, "One"), SubtitleCue(1, 700, 900, "Duplicate"))),
            good.copy(completedParts = 1),
        )
        invalid.forEach { bundle ->
            assertThrows(IllegalArgumentException::class.java) { SubtitleArchiveCodec.toMap(bundle) }
        }
        val malformed = SubtitleArchiveCodec.toMap(good).toMutableMap()
        malformed["durationMs"] = 4000.5
        assertThrows(IllegalArgumentException::class.java) { SubtitleArchiveCodec.fromMap(malformed) }
    }
}
