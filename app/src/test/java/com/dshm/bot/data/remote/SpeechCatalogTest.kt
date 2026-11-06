package com.dshm.bot.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechCatalogTest {

    @Test
    fun `parses provider readiness and progress`() {
        val catalog = SpeechCatalog.fromJson(
            JSONObject(
                """
                {
                  "selection": {"providerId": "sensevoice", "language": "zh-CN"},
                  "maxAudioBytes": 1000044,
                  "maxDurationSeconds": 30,
                  "providers": [
                    {
                      "id": "sensevoice",
                      "name": "SenseVoice",
                      "location": "host-local",
                      "languages": ["zh-CN", "en"],
                      "preparation": {
                        "phase": "downloading",
                        "completedBytes": 50,
                        "totalBytes": 100
                      }
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )!!

        assertEquals("sensevoice", catalog.selected?.id)
        assertEquals("zh-CN", catalog.language)
        assertEquals(1, catalog.providers.size)
        assertEquals(0.5f, catalog.providers.first().fraction!!, 0.001f)
        assertFalse(catalog.providers.first().usable)
        assertTrue(catalog.providers.first().busy)
        assertEquals(30, catalog.maxSeconds)
    }

    @Test
    fun `missing and empty catalogs are null`() {
        assertNull(SpeechCatalog.fromJson(null))
        assertNull(SpeechCatalog.fromJson(JSONObject("""{"providers":[]}""")))
    }

    @Test
    fun `standby provider is usable`() {
        val catalog = SpeechCatalog.fromJson(
            JSONObject(
                """{"providers":[{"id":"sensevoice-local","name":"SenseVoiceSmall","preparation":{"phase":"standby"}}]}""",
            ),
        )!!
        assertTrue(catalog.providers.first().usable)
        assertEquals("sensevoice-local", catalog.usableProvider?.id)
    }

    @Test
    fun `ready provider is usable`() {
        val catalog = SpeechCatalog.fromJson(
            JSONObject(
                """{"providers":[{"id":"sensevoice","name":"SenseVoice","phase":"ready"}]}""",
            ),
        )!!
        assertTrue(catalog.providers.first().usable)
        assertEquals("sensevoice", catalog.usableProvider?.id)
    }
}
