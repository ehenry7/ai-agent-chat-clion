package com.aiagent.chat.model

import org.junit.Assert.*
import org.junit.Test

class ModelRouterTest {

    private val testModels = listOf(
        ModelInfo("gpt-4o-mini", "p1", "OpenAI", ModelSize.SMALL, ModelCost.LOW_COST),
        ModelInfo("gpt-4o", "p1", "OpenAI", ModelSize.LARGE, ModelCost.HIGH_COST),
        ModelInfo("o1-preview", "p1", "OpenAI", ModelSize.XL, ModelCost.HIGH_COST),
        ModelInfo("claude-3-haiku", "p2", "Anthropic", ModelSize.SMALL, ModelCost.LOW_COST),
        ModelInfo("claude-3-5-sonnet", "p2", "Anthropic", ModelSize.LARGE, ModelCost.MEDIUM_COST),
        ModelInfo("claude-3-opus", "p2", "Anthropic", ModelSize.XL, ModelCost.HIGH_COST),
        ModelInfo("glm-4-flash", "p3", "Zhipu", ModelSize.SMALL, ModelCost.FREE),
        ModelInfo("deepseek-v3", "p4", "DeepSeek", ModelSize.LARGE, ModelCost.LOW_COST),
        ModelInfo("deepseek-r1", "p4", "DeepSeek", ModelSize.XL, ModelCost.MEDIUM_COST),
        ModelInfo("llama-3.1-8b", "p5", "Meta", ModelSize.SMALL, ModelCost.FREE)
    )

    @Test
    fun `analyzeComplexity detects SIMPLE tasks`() {
        assertEquals(TaskComplexity.SIMPLE, ModelRouter.analyzeComplexity("format this code"))
        assertEquals(TaskComplexity.SIMPLE, ModelRouter.analyzeComplexity("list all files in the project"))
        assertEquals(TaskComplexity.SIMPLE, ModelRouter.analyzeComplexity("fix typo in README"))
        assertEquals(TaskComplexity.SIMPLE, ModelRouter.analyzeComplexity("sort the imports"))
    }

    @Test
    fun `analyzeComplexity detects MEDIUM tasks`() {
        assertEquals(TaskComplexity.MEDIUM, ModelRouter.analyzeComplexity("write unit tests for the parser"))
        assertEquals(TaskComplexity.MEDIUM, ModelRouter.analyzeComplexity("fix bug in the login handler"))
        assertEquals(TaskComplexity.MEDIUM, ModelRouter.analyzeComplexity("explain how this function works"))
        assertEquals(TaskComplexity.MEDIUM, ModelRouter.analyzeComplexity("add documentation for the API"))
    }

    @Test
    fun `analyzeComplexity detects COMPLEX tasks`() {
        assertEquals(TaskComplexity.COMPLEX, ModelRouter.analyzeComplexity("refactor the authentication module"))
        assertEquals(TaskComplexity.COMPLEX, ModelRouter.analyzeComplexity("implement feature for user notifications"))
        assertEquals(TaskComplexity.COMPLEX, ModelRouter.analyzeComplexity("add new module for data export"))
        assertEquals(TaskComplexity.COMPLEX, ModelRouter.analyzeComplexity("design API for the integration layer"))
    }

    @Test
    fun `analyzeComplexity detects XL_TASK tasks`() {
        assertEquals(TaskComplexity.XL_TASK, ModelRouter.analyzeComplexity("architect a new system design"))
        assertEquals(TaskComplexity.XL_TASK, ModelRouter.analyzeComplexity("comprehensive review of the entire codebase"))
        assertEquals(TaskComplexity.XL_TASK, ModelRouter.analyzeComplexity("migrate from monolith to microservices"))
        assertEquals(TaskComplexity.XL_TASK, ModelRouter.analyzeComplexity("full rewrite of the compiler"))
    }

    @Test
    fun `analyzeComplexity defaults to MEDIUM for unknown tasks`() {
        assertEquals(TaskComplexity.MEDIUM, ModelRouter.analyzeComplexity("hello world"))
        assertEquals(TaskComplexity.MEDIUM, ModelRouter.analyzeComplexity("do something"))
    }

    @Test
    fun `selectModel routes SIMPLE task to small free model`() {
        val model = ModelRouter.selectModel(TaskComplexity.SIMPLE, testModels)
        assertNotNull(model)
        assertEquals(ModelSize.SMALL, model!!.sizeTag)
        // Should prefer free over low_cost
        assertEquals(ModelCost.FREE, model.costTag)
    }

    @Test
    fun `selectModel routes MEDIUM task to medium low_cost model`() {
        // Add a medium model to the pool
        val modelsWithMedium = testModels + ModelInfo("mistral-medium", "p6", "Mistral", ModelSize.MEDIUM, ModelCost.LOW_COST)
        val model = ModelRouter.selectModel(TaskComplexity.MEDIUM, modelsWithMedium)
        assertNotNull(model)
        assertEquals(ModelSize.MEDIUM, model!!.sizeTag)
    }

    @Test
    fun `selectModel routes COMPLEX task to large model`() {
        val model = ModelRouter.selectModel(TaskComplexity.COMPLEX, testModels)
        assertNotNull(model)
        assertTrue(model!!.sizeTag == ModelSize.LARGE || model.sizeTag == ModelSize.XL)
    }

    @Test
    fun `selectModel routes XL_TASK to XL high_cost model`() {
        val model = ModelRouter.selectModel(TaskComplexity.XL_TASK, testModels)
        assertNotNull(model)
        assertEquals(ModelSize.XL, model!!.sizeTag)
        assertEquals(ModelCost.HIGH_COST, model.costTag)
    }

    @Test
    fun `selectModel returns null for empty model list`() {
        assertNull(ModelRouter.selectModel(TaskComplexity.SIMPLE, emptyList()))
    }

    @Test
    fun `selectModel falls back to largest available when no exact match`() {
        val onlySmall = listOf(
            ModelInfo("mini-1", "p1", "P1", ModelSize.SMALL, ModelCost.FREE),
            ModelInfo("mini-2", "p1", "P1", ModelSize.SMALL, ModelCost.LOW_COST)
        )
        val model = ModelRouter.selectModel(TaskComplexity.XL_TASK, onlySmall)
        assertNotNull(model)
        assertEquals(ModelSize.SMALL, model!!.sizeTag)
    }

    @Test
    fun `routeTask combines analysis and selection`() {
        val model = ModelRouter.routeTask("format this code", testModels)
        assertNotNull(model)
        assertEquals(ModelSize.SMALL, model!!.sizeTag)
    }

    @Test
    fun `explainRouting produces readable explanation`() {
        val model = testModels.first()
        val explanation = ModelRouter.explainRouting(TaskComplexity.SIMPLE, model)
        assertTrue(explanation.contains("SIMPLE"))
        assertTrue(explanation.contains(model.id))
        assertTrue(explanation.contains("size="))
        assertTrue(explanation.contains("cost="))
    }

    @Test
    fun `explainRouting handles null model`() {
        val explanation = ModelRouter.explainRouting(TaskComplexity.SIMPLE, null)
        assertTrue(explanation.contains("No model available"))
    }
}
