package com.aiagent.chat.model

import org.junit.Assert.*
import org.junit.Test

class ModelCategorizerTest {

    @Test
    fun `categorize known OpenAI models`() {
        val gpt4o = ModelCategorizer.categorize("gpt-4o", "prov1", "OpenAI")
        assertEquals(ModelSize.LARGE, gpt4o.sizeTag)
        assertEquals(ModelCost.HIGH_COST, gpt4o.costTag)

        val gpt4oMini = ModelCategorizer.categorize("gpt-4o-mini", "prov1", "OpenAI")
        assertEquals(ModelSize.SMALL, gpt4oMini.sizeTag)
        assertEquals(ModelCost.LOW_COST, gpt4oMini.costTag)

        val o1Preview = ModelCategorizer.categorize("o1-preview", "prov1", "OpenAI")
        assertEquals(ModelSize.XL, o1Preview.sizeTag)
        assertEquals(ModelCost.HIGH_COST, o1Preview.costTag)
    }

    @Test
    fun `categorize known Claude models`() {
        val haiku = ModelCategorizer.categorize("claude-3-haiku", "prov1", "Anthropic")
        assertEquals(ModelSize.SMALL, haiku.sizeTag)
        assertEquals(ModelCost.LOW_COST, haiku.costTag)

        val sonnet = ModelCategorizer.categorize("claude-3-5-sonnet", "prov1", "Anthropic")
        assertEquals(ModelSize.LARGE, sonnet.sizeTag)
        assertEquals(ModelCost.MEDIUM_COST, sonnet.costTag)

        val opus = ModelCategorizer.categorize("claude-3-opus", "prov1", "Anthropic")
        assertEquals(ModelSize.XL, opus.sizeTag)
        assertEquals(ModelCost.HIGH_COST, opus.costTag)
    }

    @Test
    fun `categorize known GLM models`() {
        val flash = ModelCategorizer.categorize("glm-4-flash", "prov1", "Zhipu")
        assertEquals(ModelSize.SMALL, flash.sizeTag)
        assertEquals(ModelCost.FREE, flash.costTag)

        val plus = ModelCategorizer.categorize("glm-4-plus", "prov1", "Zhipu")
        assertEquals(ModelSize.LARGE, plus.sizeTag)
        assertEquals(ModelCost.MEDIUM_COST, plus.costTag)
    }

    @Test
    fun `categorize known DeepSeek models`() {
        val v3 = ModelCategorizer.categorize("deepseek-v3", "prov1", "DeepSeek")
        assertEquals(ModelSize.LARGE, v3.sizeTag)
        assertEquals(ModelCost.LOW_COST, v3.costTag)

        val r1 = ModelCategorizer.categorize("deepseek-r1", "prov1", "DeepSeek")
        assertEquals(ModelSize.XL, r1.sizeTag)
        assertEquals(ModelCost.MEDIUM_COST, r1.costTag)
    }

    @Test
    fun `categorize unknown model uses heuristics`() {
        val unknown = ModelCategorizer.categorize("my-custom-mini-model", "prov1", "Custom")
        assertEquals(ModelSize.SMALL, unknown.sizeTag)

        val unknownLarge = ModelCategorizer.categorize("my-custom-large-model", "prov1", "Custom")
        assertEquals(ModelSize.LARGE, unknownLarge.sizeTag)
    }

    @Test
    fun `categorize unknown model defaults to medium size`() {
        val unknown = ModelCategorizer.categorize("some-random-model-xyz", "prov1", "Custom")
        assertEquals(ModelSize.MEDIUM, unknown.sizeTag)
    }

    @Test
    fun `categorizeAll returns list with correct provider info`() {
        val ids = listOf("gpt-4o-mini", "claude-3-opus", "glm-4-flash")
        val result = ModelCategorizer.categorizeAll(ids, "prov1", "TestProvider")
        assertEquals(3, result.size)
        result.forEach { m ->
            assertEquals("prov1", m.providerId)
            assertEquals("TestProvider", m.providerName)
        }
    }

    @Test
    fun `categorize model with XL keyword gets XL size`() {
        val xlModel = ModelCategorizer.categorize("custom-xl-model", "prov1", "Custom")
        assertEquals(ModelSize.XL, xlModel.sizeTag)
        assertEquals(ModelCost.HIGH_COST, xlModel.costTag)
    }

    @Test
    fun `categorize Llama models`() {
        val llama8b = ModelCategorizer.categorize("llama-3.1-8b", "prov1", "Meta")
        assertEquals(ModelSize.SMALL, llama8b.sizeTag)
        assertEquals(ModelCost.FREE, llama8b.costTag)

        val llama70b = ModelCategorizer.categorize("llama-3.1-70b", "prov1", "Meta")
        assertEquals(ModelSize.MEDIUM, llama70b.sizeTag)
    }

    @Test
    fun `categorize preserves original model ID casing`() {
        val model = ModelCategorizer.categorize("GPT-4o-Mini", "prov1", "OpenAI")
        assertEquals("GPT-4o-Mini", model.id)
    }
}
