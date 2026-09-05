package com.aiagent.chat.util

import com.aiagent.chat.debug.DebugLog

/**
 * Safe JCEF (J Chromium Embedded Framework) availability probe.
 *
 * Inspired by refact-main's JcefSupport.kt.
 * Checks whether JCEF is available in the current IDE without throwing
 * NoClassDefFoundError. JCEF may not be present in all IDE editions or
 * may be disabled by the user.
 *
 * Usage: Call [isAvailable] before attempting to use any JCEF-based UI.
 * If false, fall back to Swing-based UI.
 */
object JcefSupport {

    private var cachedResult: Boolean? = null
    private var cacheChecked = false

    /**
     * Check if JCEF is available in the current runtime.
     * Caches the result after first check.
     *
     * This method catches NoClassDefFoundError which can occur when:
     * - The IDE edition doesn't bundle JCEF (e.g. some CLion builds)
     * - JCEF is disabled via system property
     * - JCEF native libraries are missing
     */
    fun isAvailable(): Boolean {
        if (cacheChecked) return cachedResult ?: false

        cacheChecked = true

        // Check system property override
        val disabled = System.getProperty("ide.browser.jcef.enabled")
        if (disabled == "false") {
            DebugLog.info("JcefSupport", "JCEF disabled by system property ide.browser.jcef.enabled=false")
            cachedResult = false
            return false
        }

        // Try to load JCEF classes without triggering NoClassDefFoundError
        cachedResult = try {
            // Check if the JCEF main class is loadable
            val jcefClass = try {
                Class.forName("com.intellij.ui.jcef.JBCefBrowser")
            } catch (e: ClassNotFoundException) {
                null
            }

            if (jcefClass != null) {
                // Check if JCEF is actually functional by testing the factory method
                val isCefAvailable = try {
                    val cefAppClass = Class.forName("com.intellij.ui.jcef.JBCefApp")
                    val isCreatedMethod = cefAppClass.getMethod("isCreated")
                    isCreatedMethod.invoke(null) as? Boolean ?: true
                } catch (e: Exception) {
                    // If we can't check, assume it's available since the class exists
                    true
                }
                DebugLog.info("JcefSupport", "JCEF classes found, isCefAvailable=$isCefAvailable")
                isCefAvailable
            } else {
                DebugLog.info("JcefSupport", "JCEF classes not found - JCEF not available in this IDE")
                false
            }
        } catch (e: NoClassDefFoundError) {
            DebugLog.info("JcefSupport", "JCEF not available (NoClassDefFoundError): ${e.message}")
            false
        } catch (e: Exception) {
            DebugLog.warn("JcefSupport", "JCEF availability check failed: ${e.message}")
            false
        }

        return cachedResult ?: false
    }

    /**
     * Reset the cached result (for testing or after configuration changes).
     */
    fun resetCache() {
        cachedResult = null
        cacheChecked = false
    }
}
