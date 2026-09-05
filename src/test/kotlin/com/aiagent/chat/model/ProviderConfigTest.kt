package com.aiagent.chat.model

import org.junit.Assert.*
import org.junit.Test

class ProviderConfigTest {

    // --- AuthHeaderType ---

    @Test
    fun `BEARER auth type serializes as 'bearer'`() {
        val json = kotlinx.serialization.json.Json.encodeToString(AuthHeaderType.serializer(), AuthHeaderType.BEARER)
        assertTrue(json.contains("bearer"))
    }

    @Test
    fun `X_API_KEY auth type serializes as 'x-api-key'`() {
        val json = kotlinx.serialization.json.Json.encodeToString(AuthHeaderType.serializer(), AuthHeaderType.X_API_KEY)
        assertTrue(json.contains("x-api-key"))
    }

    @Test
    fun `AuthHeaderType deserializes from 'bearer'`() {
        val result = kotlinx.serialization.json.Json.decodeFromString(AuthHeaderType.serializer(), "\"bearer\"")
        assertEquals(AuthHeaderType.BEARER, result)
    }

    @Test
    fun `AuthHeaderType deserializes from 'x-api-key'`() {
        val result = kotlinx.serialization.json.Json.decodeFromString(AuthHeaderType.serializer(), "\"x-api-key\"")
        assertEquals(AuthHeaderType.X_API_KEY, result)
    }

    // --- ModelSize ---

    @Test
    fun `ModelSize displayName returns lowercase`() {
        assertEquals("small", ModelSize.SMALL.displayName)
        assertEquals("medium", ModelSize.MEDIUM.displayName)
        assertEquals("large", ModelSize.LARGE.displayName)
        assertEquals("xl", ModelSize.XL.displayName)
    }

    @Test
    fun `ModelSize serializes with lowercase serial name`() {
        val json = kotlinx.serialization.json.Json.encodeToString(ModelSize.serializer(), ModelSize.SMALL)
        assertTrue(json.contains("small"))
        val jsonXl = kotlinx.serialization.json.Json.encodeToString(ModelSize.serializer(), ModelSize.XL)
        assertTrue(jsonXl.contains("xl"))
    }

    // --- ModelCost ---

    @Test
    fun `ModelCost displayName returns human-readable string`() {
        assertEquals("free", ModelCost.FREE.displayName)
        assertEquals("low-cost", ModelCost.LOW_COST.displayName)
        assertEquals("medium-cost", ModelCost.MEDIUM_COST.displayName)
        assertEquals("high-cost", ModelCost.HIGH_COST.displayName)
    }

    @Test
    fun `ModelCost serializes with serial name`() {
        val json = kotlinx.serialization.json.Json.encodeToString(ModelCost.serializer(), ModelCost.FREE)
        assertTrue(json.contains("free"))
        val jsonHigh = kotlinx.serialization.json.Json.encodeToString(ModelCost.serializer(), ModelCost.HIGH_COST)
        assertTrue(jsonHigh.contains("high_cost"))
    }

    // --- ModelInfo ---

    @Test
    fun `ModelInfo displayName returns id when providerName is blank`() {
        val info = ModelInfo("gpt-4", "p1", "", sizeTag = ModelSize.LARGE, costTag = ModelCost.HIGH_COST)
        assertEquals("gpt-4", info.displayName)
    }

    @Test
    fun `ModelInfo displayName returns id with providerName when not blank`() {
        val info = ModelInfo("gpt-4", "p1", "OpenAI", sizeTag = ModelSize.LARGE, costTag = ModelCost.HIGH_COST)
        assertEquals("OpenAI/gpt-4", info.displayName)
    }

    @Test
    fun `ModelInfo has default values`() {
        val info = ModelInfo("test-model", "p1")
        assertEquals("", info.providerName)
        assertEquals(ModelSize.MEDIUM, info.sizeTag)
        assertEquals(ModelCost.LOW_COST, info.costTag)
    }

    // --- ProviderConfig.toApiHeaders() ---

    @Test
    fun `toApiHeaders returns Bearer header for BEARER auth type`() {
        val provider = ProviderConfig(
            id = "p1", name = "Test", baseUrl = "http://localhost",
            apiKey = "my-key", authHeaderType = AuthHeaderType.BEARER
        )
        val headers = provider.toApiHeaders()
        assertEquals(1, headers.size)
        assertEquals("Bearer my-key", headers["Authorization"])
        assertNull(headers["x-api-key"])
    }

    @Test
    fun `toApiHeaders returns x-api-key header for X_API_KEY auth type`() {
        val provider = ProviderConfig(
            id = "p1", name = "Test", baseUrl = "http://localhost",
            apiKey = "my-key", authHeaderType = AuthHeaderType.X_API_KEY
        )
        val headers = provider.toApiHeaders()
        assertEquals(1, headers.size)
        assertEquals("my-key", headers["x-api-key"])
        assertNull(headers["Authorization"])
    }

    @Test
    fun `ProviderConfig has default authHeaderType BEARER`() {
        val provider = ProviderConfig("p1", "Test", "http://localhost", "key")
        assertEquals(AuthHeaderType.BEARER, provider.authHeaderType)
    }

    @Test
    fun `ProviderConfig has default empty models list`() {
        val provider = ProviderConfig("p1", "Test", "http://localhost", "key")
        assertTrue(provider.models.isEmpty())
    }

    // --- ProviderConfig serialization ---

    @Test
    fun `ProviderConfig serializes and deserializes correctly`() {
        val provider = ProviderConfig(
            id = "p1", name = "TestProvider", baseUrl = "http://localhost:8080",
            apiKey = "secret-key", authHeaderType = AuthHeaderType.X_API_KEY,
            models = listOf(
                ModelInfo("model-a", "p1", "TestProvider", sizeTag = ModelSize.SMALL, costTag = ModelCost.FREE),
                ModelInfo("model-b", "p1", "TestProvider", sizeTag = ModelSize.LARGE, costTag = ModelCost.HIGH_COST)
            )
        )
        val json = kotlinx.serialization.json.Json.encodeToString(ProviderConfig.serializer(), provider)
        val decoded = kotlinx.serialization.json.Json.decodeFromString(ProviderConfig.serializer(), json)
        assertEquals(provider.id, decoded.id)
        assertEquals(provider.name, decoded.name)
        assertEquals(provider.baseUrl, decoded.baseUrl)
        assertEquals(provider.apiKey, decoded.apiKey)
        assertEquals(provider.authHeaderType, decoded.authHeaderType)
        assertEquals(2, decoded.models.size)
        assertEquals("model-a", decoded.models[0].id)
        assertEquals(ModelSize.SMALL, decoded.models[0].sizeTag)
        assertEquals(ModelCost.HIGH_COST, decoded.models[1].costTag)
    }

    @Test
    fun `ProviderConfig list serializes and deserializes correctly`() {
        val providers = listOf(
            ProviderConfig("p1", "Provider1", "http://a", "key1"),
            ProviderConfig("p2", "Provider2", "http://b", "key2", AuthHeaderType.X_API_KEY)
        )
        val serializer = kotlinx.serialization.builtins.ListSerializer(ProviderConfig.serializer())
        val json = kotlinx.serialization.json.Json.encodeToString(serializer, providers)
        val decoded = kotlinx.serialization.json.Json.decodeFromString(serializer, json)
        assertEquals(2, decoded.size)
        assertEquals("p1", decoded[0].id)
        assertEquals("p2", decoded[1].id)
        assertEquals(AuthHeaderType.X_API_KEY, decoded[1].authHeaderType)
    }

    // --- TaskComplexity ---

    @Test
    fun `TaskComplexity has all four levels`() {
        assertEquals(4, TaskComplexity.entries.size)
        assertTrue(TaskComplexity.entries.contains(TaskComplexity.SIMPLE))
        assertTrue(TaskComplexity.entries.contains(TaskComplexity.MEDIUM))
        assertTrue(TaskComplexity.entries.contains(TaskComplexity.COMPLEX))
        assertTrue(TaskComplexity.entries.contains(TaskComplexity.XL_TASK))
    }
}
