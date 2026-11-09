package com.dshm.bot.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderDiscoveryTest {
    @Test
    fun `request keeps provider baseURL and api together`() {
        val request = discoveryRequest(
            baseUrl = "https://api.longcat.chat/v1",
            api = "openai-completions",
            apiKey = "",
            route = "longcat",
        )
        assertEquals("longcat", request.optString("provider"))
        assertEquals("https://api.longcat.chat/v1", request.optString("baseURL"))
        assertEquals("openai-completions", request.optString("api"))
        assertFalse(request.has("apiKey"))
    }

    @Test
    fun `request sends only a freshly entered key`() {
        val request = discoveryRequest(
            baseUrl = "https://api.example.com/v1",
            api = "openai-completions",
            apiKey = " sk-new ",
            route = "custom",
        )
        assertEquals("sk-new", request.optString("apiKey"))
    }

    @Test
    fun `bare endpoint gets a v1 retry candidate`() {
        assertEquals(
            listOf("https://api.example.com", "https://api.example.com/v1"),
            discoveryBaseUrlCandidates("https://api.example.com/", "openai-completions"),
        )
    }

    @Test
    fun `endpoint with path and anthropic get one candidate`() {
        assertEquals(
            listOf("https://api.example.com/openai/v1"),
            discoveryBaseUrlCandidates("https://api.example.com/openai/v1/", "openai-completions"),
        )
        assertEquals(
            listOf("https://api.example.com"),
            discoveryBaseUrlCandidates("https://api.example.com/v1/", "anthropic-messages"),
        )
    }

    @Test
    fun `base url validation rejects blanks and suffixes`() {
        assertTrue(isValidProviderBaseUrl("https://api.example.com/v1"))
        assertFalse(isValidProviderBaseUrl("api.example.com/v1"))
        assertFalse(isValidProviderBaseUrl("https://api.example.com/v1?x=1"))
        assertFalse(isValidProviderBaseUrl("https://api.example.com/v1#x"))
        assertFalse(isValidProviderBaseUrl("https://api.example.com/v 1"))
    }

    @Test
    fun `not found is recognized from engine error`() {
        assertTrue(
            isDiscoveryNotFound(
                DshRpcException(
                    code = "llm/model-discovery-rejected",
                    message = "https://api.example.com/models answered 404",
                    details = null,
                ),
            ),
        )
        assertFalse(isDiscoveryNotFound(IllegalStateException("answered 404")))
    }
}
