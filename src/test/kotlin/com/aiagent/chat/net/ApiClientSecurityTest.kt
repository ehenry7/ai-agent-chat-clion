package com.aiagent.chat.net

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ApiClient security utilities — rate limiting and log sanitization.
 * Tests the pure logic without making actual HTTP calls.
 */
class ApiClientSecurityTest {

    @Test
    fun `sanitizeForLog redacts Bearer token from authorization header`() {
        val client = ApiClient(baseUrl = "http://localhost", apiKey = "secret-key-12345")
        // Use reflection to access the private sanitizeForLog method
        val method = ApiClient::class.java.getDeclaredMethod("sanitizeForLog", String::class.java)
        method.isAccessible = true
        val input = "Authorization: Bearer secret-key-12345"
        val result = method.invoke(client, input) as String
        assertTrue("Bearer token should be redacted", result.contains("Bearer ***"))
        assertFalse("Original key should not appear", result.contains("secret-key-12345"))
    }

    @Test
    fun `sanitizeForLog redacts api_key field in JSON`() {
        val client = ApiClient(baseUrl = "http://localhost", apiKey = "my-secret")
        val method = ApiClient::class.java.getDeclaredMethod("sanitizeForLog", String::class.java)
        method.isAccessible = true
        val input = """{"api_key": "my-secret", "model": "gpt-4"}"""
        val result = method.invoke(client, input) as String
        assertTrue("api_key should be redacted", result.contains("***"))
        assertFalse("Original key should not appear in result", result.contains("my-secret"))
    }

    @Test
    fun `sanitizeForLog redacts api-key field with hyphen`() {
        val client = ApiClient(baseUrl = "http://localhost", apiKey = "hyphenated-key")
        val method = ApiClient::class.java.getDeclaredMethod("sanitizeForLog", String::class.java)
        method.isAccessible = true
        val input = """{"api-key": "hyphenated-key"}"""
        val result = method.invoke(client, input) as String
        assertTrue("api-key should be redacted", result.contains("***"))
    }

    @Test
    fun `sanitizeForLog preserves non-sensitive content`() {
        val client = ApiClient(baseUrl = "http://localhost", apiKey = "key")
        val method = ApiClient::class.java.getDeclaredMethod("sanitizeForLog", String::class.java)
        method.isAccessible = true
        val input = "API error 500: Internal server error"
        val result = method.invoke(client, input) as String
        assertEquals("Non-sensitive content should be preserved", input, result)
    }

    @Test
    fun `sanitizeForLog handles empty string`() {
        val client = ApiClient(baseUrl = "http://localhost", apiKey = "key")
        val method = ApiClient::class.java.getDeclaredMethod("sanitizeForLog", String::class.java)
        method.isAccessible = true
        val result = method.invoke(client, "") as String
        assertEquals("", result)
    }
}
