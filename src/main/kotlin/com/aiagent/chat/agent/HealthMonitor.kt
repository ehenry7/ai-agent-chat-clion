package com.aiagent.chat.agent

import com.aiagent.chat.debug.DebugLog
import kotlinx.coroutines.*
import java.lang.management.ManagementFactory
import java.lang.management.MemoryMXBean

/**
 * Performance and health monitoring for the agent runtime.
 *
 * Inspired by refact-main's performance health check patterns.
 * Monitors:
 * - JVM heap memory usage
 * - Active coroutine count (via thread count proxy)
 * - Context compaction frequency
 * - Agent loop iteration time
 *
 * Usage: Call [snapshot] to get a point-in-time health report,
 * or [startMonitoring] for periodic background monitoring.
 */
class HealthMonitor(
    private val compactor: ContextCompactor? = null,
    private val usageTracker: UsageTracker? = null,
    private val warnThresholdPct: Double = 85.0,
    private val criticalThresholdPct: Double = 95.0
) {
    private val memoryBean: MemoryMXBean = ManagementFactory.getMemoryMXBean()
    private var monitoringJob: Job? = null
    private var lastSnapshotTime: Long = 0
    private var lastCompactionCount: Int = 0

    data class HealthSnapshot(
        val timestamp: Long,
        val heapUsedMB: Int,
        val heapMaxMB: Int,
        val heapPercentage: Double,
        val threadCount: Int,
        val compactionEvents: Int,
        val isMemoryWarning: Boolean,
        val isMemoryCritical: Boolean,
        val recommendations: List<String>
    ) {
        val isHealthy: Boolean get() = !isMemoryCritical && recommendations.isEmpty()

        fun toDisplayString(): String = buildString {
            appendLine("## Health Snapshot")
            appendLine("- **Heap Memory:** ${heapUsedMB}MB / ${heapMaxMB}MB (${heapPercentage.toInt()}%)")
            appendLine("- **Thread Count:** $threadCount")
            appendLine("- **Compaction Events:** $compactionEvents")
            if (isMemoryWarning) appendLine("- **WARNING:** Memory usage above ${85}%")
            if (isMemoryCritical) appendLine("- **CRITICAL:** Memory usage above ${95}%")
            if (recommendations.isNotEmpty()) {
                appendLine("- **Recommendations:**")
                recommendations.forEach { appendLine("  - $it") }
            }
            if (isHealthy) appendLine("- **Status:** Healthy")
        }.trimEnd()
    }

    /**
     * Take a point-in-time health snapshot.
     */
    fun snapshot(): HealthSnapshot {
        val heapUsage = memoryBean.heapMemoryUsage
        val heapUsed = heapUsage.used
        val heapMax = heapUsage.max
        val heapPct = if (heapMax > 0) (heapUsed.toDouble() / heapMax) * 100 else 0.0

        val threadCount = ManagementFactory.getThreadMXBean().threadCount
        val compactionEvents = usageTracker?.getCompactionEvents()?.size ?: 0

        val isMemoryWarning = heapPct >= warnThresholdPct
        val isMemoryCritical = heapPct >= criticalThresholdPct

        val recommendations = mutableListOf<String>()

        if (isMemoryCritical) {
            recommendations.add("Memory usage is critical. Consider restarting the IDE or reducing context window size.")
        } else if (isMemoryWarning) {
            recommendations.add("Memory usage is high. Consider compacting context or closing unused projects.")
        }

        if (threadCount > 200) {
            recommendations.add("Thread count is high ($threadCount). Possible coroutine leak.")
        }

        if (compactionEvents > 10) {
            recommendations.add("Frequent compaction events ($compactionEvents). Consider increasing max context tokens.")
        }

        // Check compaction diagnostics if available
        if (compactor != null) {
            try {
                val diagnostics = compactor.getCompactionDiagnostics(emptyList())
                if (diagnostics.contains("Compaction needed: true")) {
                    recommendations.add("Context compaction is needed but hasn't been triggered yet.")
                }
            } catch (e: Exception) {
                // Ignore - diagnostics are best-effort
            }
        }

        val snapshot = HealthSnapshot(
            timestamp = System.currentTimeMillis(),
            heapUsedMB = (heapUsed / (1024 * 1024)).toInt(),
            heapMaxMB = (heapMax / (1024 * 1024)).toInt(),
            heapPercentage = heapPct,
            threadCount = threadCount,
            compactionEvents = compactionEvents,
            isMemoryWarning = isMemoryWarning,
            isMemoryCritical = isMemoryCritical,
            recommendations = recommendations
        )

        lastSnapshotTime = snapshot.timestamp
        lastCompactionCount = compactionEvents

        if (isMemoryCritical || recommendations.isNotEmpty()) {
            DebugLog.warn("HealthMonitor", "Health issues detected: ${recommendations.joinToString("; ")}")
        }

        return snapshot
    }

    /**
     * Start periodic background monitoring.
     *
     * @param intervalMs Monitoring interval in milliseconds (default 60s)
     * @param onWarning Callback when a warning-level issue is detected
     */
    fun startMonitoring(
        intervalMs: Long = 60_000,
        onWarning: ((HealthSnapshot) -> Unit)? = null
    ) {
        stopMonitoring()
        monitoringJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                delay(intervalMs)
                val snap = snapshot()
                if (!snap.isHealthy) {
                    onWarning?.invoke(snap)
                }
            }
        }
        DebugLog.info("HealthMonitor", "Background monitoring started (interval=${intervalMs}ms)")
    }

    /**
     * Stop background monitoring.
     */
    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
    }

    /**
     * Record agent loop iteration time for performance tracking.
     */
    private val iterationTimes = mutableListOf<Long>()
    private val maxIterationRecords = 100

    fun recordIterationTime(durationMs: Long) {
        iterationTimes.add(durationMs)
        if (iterationTimes.size > maxIterationRecords) {
            iterationTimes.removeAt(0)
        }
        if (durationMs > 30_000) {
            DebugLog.warn("HealthMonitor", "Slow agent iteration: ${durationMs}ms")
        }
    }

    /**
     * Get average iteration time.
     */
    fun getAverageIterationTime(): Long {
        if (iterationTimes.isEmpty()) return 0
        return iterationTimes.sum() / iterationTimes.size
    }

    /**
     * Get the last N iteration times.
     */
    fun getRecentIterationTimes(count: Int = 10): List<Long> {
        return iterationTimes.takeLast(count)
    }
}
