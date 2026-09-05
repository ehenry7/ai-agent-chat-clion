package com.aiagent.chat.model

import com.aiagent.chat.debug.DebugLog
import java.io.File

/**
 * Per-project model overrides.
 *
 * Inspired by refact-main's per-project model override system.
 * Reads a YAML-like config file at `<project>/.aiagent/model_defaults.yaml` (or .json)
 * to allow each project to specify a preferred model, provider, or context size
 * without changing global settings.
 *
 * Override file format (simple key=value, one per line):
 * ```
 * model: glm-5.2-1
 * provider: huawei
 * max_context_tokens: 65536
 * max_output_tokens: 8192
 * ```
 *
 * Precedence: project override > global settings > defaults
 */
data class ProjectModelOverrides(
    val model: String? = null,
    val providerId: String? = null,
    val maxContextTokens: Int? = null,
    val maxOutputTokens: Int? = null
) {

    companion object {
        private const val CONFIG_DIR = ".aiagent"
        private const val CONFIG_FILE_YAML = "model_defaults.yaml"
        private const val CONFIG_FILE_JSON = "model_defaults.json"

        /**
         * Load overrides from the project root directory.
         * Returns an empty ProjectModelOverrides if no config file exists or parsing fails.
         */
        fun load(projectRoot: String): ProjectModelOverrides {
            val rootDir = File(projectRoot)
            if (!rootDir.isDirectory) return ProjectModelOverrides()

            val configDir = File(rootDir, CONFIG_DIR)
            val yamlFile = File(configDir, CONFIG_FILE_YAML)
            val jsonFile = File(configDir, CONFIG_FILE_JSON)

            val configFile = when {
                yamlFile.exists() -> yamlFile
                jsonFile.exists() -> jsonFile
                else -> return ProjectModelOverrides()
            }

            DebugLog.info("ProjectModelOverrides", "Loading project overrides from ${configFile.absolutePath}")
            return try {
                parseConfig(configFile.readText())
            } catch (e: Exception) {
                DebugLog.warn("ProjectModelOverrides", "Failed to parse ${configFile.name}: ${e.message}")
                ProjectModelOverrides()
            }
        }

        /**
         * Parse simple key: value config format.
         * Supports both YAML-style (key: value) and JSON-style ({"key": "value"}).
         */
        private fun parseConfig(content: String): ProjectModelOverrides {
            val text = content.trim()
            if (text.startsWith("{")) {
                // JSON format
                return parseJsonConfig(text)
            }
            // Simple key: value format (YAML-like)
            var model: String? = null
            var providerId: String? = null
            var maxContextTokens: Int? = null
            var maxOutputTokens: Int? = null

            for (line in text.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val colonIdx = trimmed.indexOf(':')
                if (colonIdx < 0) continue
                val key = trimmed.substring(0, colonIdx).trim()
                val value = trimmed.substring(colonIdx + 1).trim().removeSurrounding("\"").removeSurrounding("'")

                when (key) {
                    "model" -> model = value
                    "provider", "provider_id" -> providerId = value
                    "max_context_tokens", "maxContextTokens" -> maxContextTokens = value.toIntOrNull()
                    "max_output_tokens", "maxOutputTokens" -> maxOutputTokens = value.toIntOrNull()
                }
            }

            return ProjectModelOverrides(
                model = model,
                providerId = providerId,
                maxContextTokens = maxContextTokens,
                maxOutputTokens = maxOutputTokens
            )
        }

        /**
         * Parse JSON config format.
         */
        private fun parseJsonConfig(text: String): ProjectModelOverrides {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val obj = json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
            return ProjectModelOverrides(
                model = obj["model"]?.let { it.toString().trim('"') },
                providerId = obj["provider"]?.let { it.toString().trim('"') }
                    ?: obj["provider_id"]?.let { it.toString().trim('"') },
                maxContextTokens = obj["max_context_tokens"]?.toString()?.toIntOrNull()
                    ?: obj["maxContextTokens"]?.toString()?.toIntOrNull(),
                maxOutputTokens = obj["max_output_tokens"]?.toString()?.toIntOrNull()
                    ?: obj["maxOutputTokens"]?.toString()?.toIntOrNull()
            )
        }
    }

    /**
     * Apply these overrides on top of the given base values.
     * Non-null override fields take precedence.
     */
    fun applyOverrides(
        baseModel: String,
        baseProviderId: String?,
        baseMaxContext: Int,
        baseMaxOutput: Int
    ): ResolvedModelConfig {
        return ResolvedModelConfig(
            model = model ?: baseModel,
            providerId = providerId ?: baseProviderId,
            maxContextTokens = maxContextTokens ?: baseMaxContext,
            maxOutputTokens = maxOutputTokens ?: baseMaxOutput
        )
    }

    val hasOverrides: Boolean get() = model != null || providerId != null || maxContextTokens != null || maxOutputTokens != null
}

/**
 * Resolved configuration after applying project overrides on top of global settings.
 */
data class ResolvedModelConfig(
    val model: String,
    val providerId: String?,
    val maxContextTokens: Int,
    val maxOutputTokens: Int
)
