package com.aiagent.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for HtmlPaneFactory.insertWbr() — the soft wrap-opportunity
 * inserter that lets Swing's HTML renderer wrap long unbroken tokens
 * (JSON blobs, URLs, base64) instead of collapsing them to an invisible
 * single-line height.
 */
class HtmlPaneFactoryTest {

    @Test
    fun `short text is returned unchanged`() {
        assertEquals("hello world", HtmlPaneFactory.insertWbr("hello world"))
    }

    @Test
    fun `long space-free token receives wbr tags`() {
        val input = "a".repeat(200)
        val result = HtmlPaneFactory.insertWbr(input)
        assertTrue(result.contains("<wbr>"))
        // 200 chars at 40 per segment => 5 segments, 4 breaks
        assertEquals(result.count { it == 'a' }, 200)
        assertEquals("<wbr>".toRegex().findAll(result).count(), 4)
    }

    @Test
    fun `normal prose with spaces is not modified`() {
        val prose = "This is a very normal sentence with spaces between every single word."
        assertEquals(prose, HtmlPaneFactory.insertWbr(prose))
    }

    @Test
    fun `html entities are kept intact even inside long runs`() {
        // 100 repeated escaped < > characters form one long non-space run;
        // splits must never land inside &lt; or &gt;.
        val input = "&lt;".repeat(60)
        val result = HtmlPaneFactory.insertWbr(input)
        // No wbr may follow an unclosed entity prefix (i.e. never split mid-entity)
        assertFalse(Regex("&[a-zA-Z#0-9]{0,11}<wbr>").containsMatchIn(result))
        assertTrue(result.contains("<wbr>"))
        // Every entity survives verbatim
        assertEquals(60, Regex("&lt;").findAll(result).count())
        // 240 chars at 40 per segment => 5 breaks
        assertEquals(5, "<wbr>".toRegex().findAll(result).count())
    }

    @Test
    fun `tokens in a word-wrapped sentence each get breaks`() {
        val longWord = "x".repeat(120)
        val sentence = "prefix $longWord suffix"
        val result = HtmlPaneFactory.insertWbr(sentence)
        assertTrue(result.contains("<wbr>"))
        assertTrue(result.startsWith("prefix "))
        assertTrue(result.endsWith(" suffix"))
        // 120 chars => 3 breaks inside the token
        assertEquals("<wbr>".toRegex().findAll(result).count(), 2)
    }

    @Test
    fun `long run with mixed punctuation wraps safely`() {
        val token = "https://example.com/very/long/path/with/many/segments/"
            .repeat(5)
        val result = HtmlPaneFactory.insertWbr(token)
        assertTrue(result.contains("<wbr>"))
        assertFalse(result.contains("<wbr>" + " ")) // never after a space
    }
}
