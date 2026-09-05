package com.aiagent.chat.model

import org.junit.Assert.*
import org.junit.Test

class ProviderManagerTest {

    @Test
    fun `addProviderOffline adds provider to list`() {
        val pm = ProviderManager()
        val provider = ProviderConfig(
            id = "p1",
            name = "TestProvider",
            baseUrl = "http://localhost:8080",
            apiKey = "test-key",
            authHeaderType = AuthHeaderType.BEARER
        )
        pm.addProviderOffline(provider)
        assertEquals(1, pm.providers.size)
        assertEquals("p1", pm.providers[0].id)
    }

    @Test
    fun `addProviderOffline populates allModels from provider models`() {
        val pm = ProviderManager()
        val models = listOf(
            ModelInfo("model-a", "p1", "TestProvider", ModelSize.SMALL, ModelCost.FREE),
            ModelInfo("model-b", "p1", "TestProvider", ModelSize.LARGE, ModelCost.HIGH_COST)
        )
        val provider = ProviderConfig("p1", "TestProvider", "http://localhost:8080", "key", AuthHeaderType.BEARER, models = models)
        pm.addProviderOffline(provider)
        assertEquals(2, pm.allModels.size)
    }

    @Test
    fun `removeProvider removes provider and its models`() {
        val pm = ProviderManager()
        val models = listOf(ModelInfo("model-a", "p1", "TestProvider", ModelSize.SMALL, ModelCost.FREE))
        val provider = ProviderConfig("p1", "TestProvider", "http://localhost:8080", "key", AuthHeaderType.BEARER, models = models)
        pm.addProviderOffline(provider)
        assertEquals(1, pm.providers.size)
        assertEquals(1, pm.allModels.size)

        pm.removeProvider("p1")
        assertEquals(0, pm.providers.size)
        assertEquals(0, pm.allModels.size)
    }

    @Test
    fun `updateProvider replaces existing provider config`() {
        val pm = ProviderManager()
        val provider = ProviderConfig("p1", "TestProvider", "http://localhost:8080", "key", AuthHeaderType.BEARER)
        pm.addProviderOffline(provider)

        val updated = provider.copy(apiKey = "new-key")
        pm.updateProvider(updated)
        assertEquals("new-key", pm.providers[0].apiKey)
    }

    @Test
    fun `getProvider returns provider by id`() {
        val pm = ProviderManager()
        val provider = ProviderConfig("p1", "TestProvider", "http://localhost:8080", "key", AuthHeaderType.BEARER)
        pm.addProviderOffline(provider)

        val found = pm.getProvider("p1")
        assertNotNull(found)
        assertEquals("TestProvider", found!!.name)

        assertNull(pm.getProvider("nonexistent"))
    }

    @Test
    fun `findProviderForModel returns correct provider`() {
        val pm = ProviderManager()
        val models = listOf(ModelInfo("model-a", "p1", "TestProvider", ModelSize.SMALL, ModelCost.FREE))
        val provider = ProviderConfig("p1", "TestProvider", "http://localhost:8080", "key", AuthHeaderType.BEARER, models = models)
        pm.addProviderOffline(provider)

        val found = pm.findProviderForModel("model-a")
        assertNotNull(found)
        assertEquals("p1", found!!.id)

        assertNull(pm.findProviderForModel("nonexistent-model"))
    }

    @Test
    fun `findModel returns ModelInfo by id`() {
        val pm = ProviderManager()
        val models = listOf(
            ModelInfo("model-a", "p1", "TestProvider", ModelSize.SMALL, ModelCost.FREE),
            ModelInfo("model-b", "p1", "TestProvider", ModelSize.LARGE, ModelCost.HIGH_COST)
        )
        val provider = ProviderConfig("p1", "TestProvider", "http://localhost:8080", "key", AuthHeaderType.BEARER, models = models)
        pm.addProviderOffline(provider)

        val found = pm.findModel("model-b")
        assertNotNull(found)
        assertEquals(ModelSize.LARGE, found!!.sizeTag)

        assertNull(pm.findModel("nonexistent"))
    }

    @Test
    fun `clear removes all providers and models`() {
        val pm = ProviderManager()
        pm.addProviderOffline(ProviderConfig("p1", "P1", "http://localhost", "key", AuthHeaderType.BEARER,
            models = listOf(ModelInfo("m1", "p1", "P1", ModelSize.SMALL, ModelCost.FREE))))
        pm.addProviderOffline(ProviderConfig("p2", "P2", "http://localhost", "key", AuthHeaderType.BEARER,
            models = listOf(ModelInfo("m2", "p2", "P2", ModelSize.LARGE, ModelCost.HIGH_COST))))

        assertEquals(2, pm.providers.size)
        assertEquals(2, pm.allModels.size)

        pm.clear()
        assertEquals(0, pm.providers.size)
        assertEquals(0, pm.allModels.size)
    }

    @Test
    fun `toSystemPromptSection returns empty string when no models`() {
        val pm = ProviderManager()
        assertEquals("", pm.toSystemPromptSection())
    }

    @Test
    fun `toSystemPromptSection includes model metadata`() {
        val pm = ProviderManager()
        val models = listOf(
            ModelInfo("gpt-4o-mini", "p1", "OpenAI", ModelSize.SMALL, ModelCost.LOW_COST),
            ModelInfo("o1-preview", "p1", "OpenAI", ModelSize.XL, ModelCost.HIGH_COST)
        )
        pm.addProviderOffline(ProviderConfig("p1", "OpenAI", "http://localhost", "key", AuthHeaderType.BEARER, models = models))

        val section = pm.toSystemPromptSection()
        assertTrue(section.contains("<available_models>"))
        assertTrue(section.contains("</available_models>"))
        assertTrue(section.contains("gpt-4o-mini"))
        assertTrue(section.contains("o1-preview"))
        assertTrue(section.contains("size=small"))
        assertTrue(section.contains("size=xl"))
        assertTrue(section.contains("cost=low-cost"))
        assertTrue(section.contains("cost=high-cost"))
        assertTrue(section.contains("SIMPLE tasks"))
        assertTrue(section.contains("XL tasks"))
    }

    @Test
    fun `toSystemPromptSection groups models by provider`() {
        val pm = ProviderManager()
        pm.addProviderOffline(ProviderConfig("p1", "OpenAI", "http://openai", "key", AuthHeaderType.BEARER,
            models = listOf(ModelInfo("gpt-4o", "p1", "OpenAI", ModelSize.LARGE, ModelCost.HIGH_COST))))
        pm.addProviderOffline(ProviderConfig("p2", "Anthropic", "http://anthropic", "key", AuthHeaderType.X_API_KEY,
            models = listOf(ModelInfo("claude-3-opus", "p2", "Anthropic", ModelSize.XL, ModelCost.HIGH_COST))))

        val section = pm.toSystemPromptSection()
        assertTrue(section.contains("Provider: OpenAI"))
        assertTrue(section.contains("Provider: Anthropic"))
        assertTrue(section.contains("gpt-4o"))
        assertTrue(section.contains("claude-3-opus"))
    }

    @Test
    fun `multiple providers contribute to allModels`() {
        val pm = ProviderManager()
        pm.addProviderOffline(ProviderConfig("p1", "P1", "http://p1", "key", AuthHeaderType.BEARER,
            models = listOf(ModelInfo("m1", "p1", "P1", ModelSize.SMALL, ModelCost.FREE))))
        pm.addProviderOffline(ProviderConfig("p2", "P2", "http://p2", "key", AuthHeaderType.X_API_KEY,
            models = listOf(ModelInfo("m2", "p2", "P2", ModelSize.LARGE, ModelCost.HIGH_COST),
                   ModelInfo("m3", "p2", "P2", ModelSize.XL, ModelCost.HIGH_COST))))

        assertEquals(3, pm.allModels.size)
    }

    @Test
    fun `ProviderConfig toApiHeaders returns Bearer header`() {
        val provider = ProviderConfig("p1", "P1", "http://localhost", "secret-key", AuthHeaderType.BEARER)
        val headers = provider.toApiHeaders()
        assertEquals(1, headers.size)
        assertEquals("Bearer secret-key", headers["Authorization"])
        assertNull(headers["x-api-key"])
    }

    @Test
    fun `ProviderConfig toApiHeaders returns x-api-key header`() {
        val provider = ProviderConfig("p1", "P1", "http://localhost", "secret-key", AuthHeaderType.X_API_KEY)
        val headers = provider.toApiHeaders()
        assertEquals(1, headers.size)
        assertEquals("secret-key", headers["x-api-key"])
        assertNull(headers["Authorization"])
    }
}
