# Upstream Comparison: JegernOUTT/refact vs Local refact-main

**Date:** 2026-09-05  
**Upstream HEAD:** `cbfca9ecf` (2026-09-04)  
**Upstream version:** v8.6.3  
**Local base:** `C:\work\refact-main` (pre-v8.5.0 snapshot)  
**Our plugin:** `C:\work\ai-agent-chat-clion` (custom CLion plugin, package `com.aiagent.chat`)

---

## Summary

The upstream repo has ~950 commits since July 2026, spanning 4 minor releases (v8.5.0 → v8.6.3). Changes span the Rust engine, the React GUI, and the IntelliJ plugin. Our plugin uses a different architecture (direct OpenAI-compatible API calls, no LSP daemon), so many upstream changes are refact-infrastructure-specific. Below are the changes **relevant to our plugin**, ranked by impact.

---

## Tier 1: Directly Applicable — High Priority

### 1. Context Size Tracking Fix (commit `4f08dee81`)
**Problem:** The context token counter read the last assistant message, but a newly started assistant message carries no usage data until its stream ends — so the context size dropped to zero between turns and only recovered on the next reply.

**Fix:** A memoized selector walks back to the last assistant message that actually reported input tokens, counting cache reads and creations so a fully cached turn still reports a size.

**Relevance to us:** Our `UsageTracker` has the same pattern — it reads token counts from the last assistant message. If our usage counter resets between turns, this is the fix. We should verify our `computeSummary()` handles the case where the latest assistant message has no usage data yet.

**Suggested action:** Audit `UsageTracker.kt` — ensure `computeSummary()` scans backward for the last message with real token data, not just the last assistant message.

---

### 2. Kill Silent Truncation (commit `014fcab8e`)
**Problem:** `MAX_TOOL_BUDGET` capped every model at 32k tokens of code regardless of context window (3.2% of a 1M window). 22+ caps sized for 8-32k-era models were hardcoded. Truncation was silent — no indication of what was cut.

**Fix:**
- All limits are now configurable settings with validated ranges
- Every truncation marker states "showing X of Y" and names the setting to raise
- Long trajectory messages are split into multiple indexed chunks instead of truncating at 2KB
- Failures are distinguishable from empty results (parse failures no longer reported as "no symbols")

**Relevance to us:** Our `ContextCompactor` has similar issues — `SUMMARIZE_CONTENT_LIMIT` truncates to 2000 chars silently, `MAX_MESSAGES_TO_SUMMARIZE` drops messages, and `fallbackCompact()` truncates to 200 chars. We already fixed some of these (commit `d5caf9b`), but we should add **loud truncation markers** that state what was cut and suggest raising the limit.

**Suggested action:** Add truncation markers to `ContextCompactor` — when content is truncated, include "[truncated: showing X of Y chars, raise maxContextTokens to see full]" in the compressed output.

---

### 3. Per-Project Model Slot Overrides (commit `69804dded`)
**Feature:** Project-scoped model configuration on top of global defaults. Overrides live in `<project>/.refact/model_defaults.yaml`. A slot with a model replaces the global slot wholesale; absent slots inherit.

**Relevance to us:** Our multi-provider architecture (`ProviderManager`) currently uses global settings only. Adding per-project overrides would let users use different models for different projects (e.g., a cheaper model for experimentation, a powerful model for production code).

**Suggested action:** Add a project-scoped settings layer to `ProviderManager` — load `<project>/.aiagent/model_defaults.json` on project open, merge with global settings, allow per-project model/provider overrides.

---

### 4. JCEF Safe Availability Probe (`JcefSupport.kt`)
**Problem:** Since 2025.3.1/2026.x, JCEF API classes live behind the `com.intellij.modules.jcef` plugin alias. IDEs without JCEF (remote-dev/headless) don't expose the classes at all. Referencing `JBCefApp` from startup code crashes on JCEF-less IDEs.

**Fix:** A `JcefSupport.isAvailable()` method that catches `Throwable` (including `NoClassDefFoundError`) and caches the result.

**Relevance to us:** If we ever add a web-based chat UI (like the upstream's `ChatWebView`), we need this safe probe. Even without JCEF, the pattern of safely probing for optional platform features is good practice.

**Suggested action:** Add `JcefSupport.kt` to our utils. If we add a web-based chat view later, gate it behind this probe and degrade gracefully.

---

## Tier 2: Architecturally Relevant — Medium Priority

### 5. IDE Log Reader (`IdeaLogReader.kt`)
**Feature:** Reads the last 256KB of `idea.log`, parses log levels/timestamps, filters for plugin-specific entries. Uses `RandomAccessFile` for efficient tail reading.

**Relevance to us:** Our plugin uses `DebugLog` for logging, but there's no way for users to see plugin logs from within the chat UI. An IDE log reader would let the agent itself read its own logs for debugging, or surface errors to the user.

**Suggested action:** Port `IdeaLogReader.kt` — add a `/logs` slash command or a "View Logs" button in the setup panel that shows recent plugin log entries.

---

### 6. Test Isolation (build.gradle.kts changes)
**Feature:** Tests are isolated from the developer's real daemon home via `systemProperty("refact.daemon.dir", ...)` and `environment("REFACT_DAEMON_DIR", ...)`. Each test run gets a clean `build/test-daemon-home` directory.

**Relevance to us:** Our tests currently share the real user home directory, which can cause flaky tests if settings files are modified during test runs. We should isolate our test environment.

**Suggested action:** Add test isolation to `build.gradle.kts` — set a temporary home directory and config path for tests via system properties.

---

### 7. Unbounded Until-Build with API Warning Tolerance
**Feature:** The upstream plugin now declares an unbounded `untilBuild` (no upper limit) and treats `INTERNAL_API_USAGES` as a warning, not a failure. This keeps the plugin available on future IDE versions (262+).

**Relevance to us:** Our plugin is pinned to `untilBuild = "242.*"` (CLion 2024.2 only). If we want to support newer CLion versions without releasing updates, we should consider making `untilBuild` optional or unbounded.

**Suggested action:** Make `untilBuild` optional in `build.gradle.kts` — only set it if the gradle property is provided, otherwise leave unbounded. Change plugin verification to treat `INTERNAL_API_USAGES` as a warning.

---

### 8. Inlay-Based Code Lens (replaces CodeVision)
**Feature:** The upstream migrated from `CodeVisionProvider` (IntelliJ's code lens system) to a custom inlay-based system (`CodeLensInlayService`, `CodeLensInlayRenderer`, `CodeLensEditorFactoryListener`). This gives more control over rendering and doesn't depend on the CodeVision framework.

**Relevance to us:** If we want to show AI-related code lens (e.g., "AI: 3 potential bugs found", "AI: explain this function"), the inlay-based approach is more flexible and doesn't require CodeVision integration. However, this is a significant feature addition.

**Suggested action:** Defer unless we want code lens features. If yes, port the inlay-based approach rather than CodeVision — it's more portable across IDE versions.

---

## Tier 3: Interesting Patterns — Low Priority

### 9. Binary Auto-Download with SHA256 Verification (`RefactBinaryResolver.kt`)
**Pattern:** Resolves a binary from: explicit path → bundled → system PATH → auto-download from GitHub releases. Downloads include SHA256 verification, atomic file moves, install locking with stale-lock detection, and archive extraction (tar.gz + zip) with path traversal protection.

**Relevance to us:** If our plugin ever needs to manage external binaries (e.g., a Python interpreter, a code analysis tool), this is a gold-standard implementation. The path traversal protection in archive extraction is particularly well-done.

**Suggested action:** Reference implementation only. No action needed unless we add binary management.

---

### 10. PATH Registration (`RefactPathRegistrar.kt`)
**Pattern:** Idempotently registers a binary directory on the user's PATH. On Unix, maintains a marker-delimited block in shell profiles (`.zshrc`, `.bashrc`, `.config/fish/config.fish`). On Windows, uses PowerShell to update the HKCU PATH registry value. All operations are non-throwing with structured results.

**Relevance to us:** Same as above — useful if we need to register tools. The marker-block pattern for shell profiles is elegant and worth borrowing.

---

### 11. Daemon Health Gate Pattern (`RefactDaemonClient.kt`)
**Pattern:** Before using a daemon process: check if it's running → verify version compatibility → if mismatch, shut down and wait for it to stop → spawn a new one with health polling → verify the new one is healthy before proceeding. Includes executable SHA256 verification.

**Relevance to us:** We don't use a daemon, but the health-gate pattern is applicable to our API client — we could verify API connectivity and model availability before starting an agent loop, rather than failing mid-conversation.

**Suggested action:** Consider adding a pre-flight check to `ApiClient` — verify the endpoint is reachable and the model is available before starting the agent loop.

---

### 12. LiteLLM Provider Enable Toggle (commit `c168cf973`)
**Feature:** An explicit setting to control whether LiteLLM provider models appear in global selectors.

**Relevance to us:** Our multi-provider architecture could benefit from per-provider enable/disable toggles, so users can register a provider but temporarily disable it without deleting the configuration.

**Suggested action:** Add an `enabled: Boolean` field to `ProviderConfig` — disabled providers are stored but not used for routing. Add a checkbox in the setup panel's provider list.

---

### 13. Performance Audit Patterns (commit `4e403f211`)
**Feature:** Watcher deadlock fix, retention policies, caching, and telemetry improvements across engine and GUI.

**Relevance to us:** Our plugin should periodically audit for: file watcher deadlocks, memory retention in long sessions, cache effectiveness in `ContextCompactor`, and coroutine leak prevention.

**Suggested action:** Add a periodic health check to `AgentEngine` — log warnings if coroutine count exceeds a threshold, if message history grows unbounded, or if compaction isn't reducing context size.

---

## Files Changed in Upstream (IntelliJ Plugin Only)

### New Files (not in local)
| File | Purpose |
|------|---------|
| `lsp/RefactBinaryResolver.kt` | Auto-download/resolve refact binary with SHA256 verification |
| `lsp/RefactDaemonClient.kt` | HTTP client for refact daemon with health gates |
| `lsp/RefactPathRegistrar.kt` | Register refact binary on system PATH |
| `code_lens/CodeLensEditorFactoryListener.kt` | Listen for editor creation for inlay code lens |
| `code_lens/CodeLensInlayRenderer.kt` | Render inlay-based code lens |
| `code_lens/CodeLensInlayService.kt` | Manage code lens inlays per editor |
| `code_lens/CodeLensModels.kt` | Data models for code lens |
| `code_lens/CodeLensParser.kt` | Parse code lens data |
| `utils/JcefSupport.kt` | Safe JCEF availability probe |
| `panes/sharedchat/IdeaLogReader.kt` | Read and filter IDE logs |
| `META-INF/refact-jcef.xml` | Optional JCEF dependency declaration |
| `test/.../RefactBinaryResolverTest.kt` | Tests for binary resolver |
| `test/.../RefactDaemonClientTest.kt` | Tests for daemon client |
| `test/.../RefactPathRegistrarTest.kt` | Tests for PATH registrar |
| `test/.../IdeaLogReaderTest.kt` | Tests for IDE log reader |
| `test/.../SharedChatPaneReadyTest.kt` | Tests for chat pane readiness |
| `test/.../status_bar/` | Status bar tests |

### Removed Files (in local, not in upstream)
| File | Reason |
|------|--------|
| `code_lens/CodeLensInvalidatorService.kt` | Replaced by `CodeLensInlayService` |
| `code_lens/RefactCodeVisionProvider.kt` | Replaced by inlay-based system |
| `code_lens/RefactCodeVisionProviderFactory.kt` | Replaced by inlay-based system |
| `codecompletion/InlineCompletionGrayTextElement.kt` | Code completion refactored |
| `codecompletion/RefactAIContinuousEvent.kt` | Code completion refactored |
| `codecompletion/RefactInlineCompletionDocumentListener.kt` | Code completion refactored |
| `listeners/ForceCompletionAction.kt` | Action system cleanup |
| `listeners/ForceCompletionActionPromoter.kt` | Action system cleanup |
| `listeners/InlineActionPromoter.kt` | Action system cleanup |

### Key Modified Files
| File | Key Changes |
|------|-------------|
| `settings/AppSettingsState.kt` | +refactBinaryPath, +codegraphIsEnabled, +codegraphFileLimit, +httpHost, +browserHost |
| `resources/META-INF/plugin.xml` | JCEF optional dep, code lens migration, action cleanup, vendor change |
| `build.gradle.kts` | Test isolation, binary bundling, unbounded until-build, env-var version override |
| `lsp/LSPProcessHolder.kt` | Daemon client integration |
| `lsp/LSPConfig.kt` | Daemon settings support |
| `panes/sharedchat/SharedChatPane.kt` | JCEF gating, daemon URL support |
| `panes/sharedchat/browser/ChatWebView.kt` | JCEF safe probing |
| `status_bar/StatusBarWidget.kt` | Daemon status display |

---

## Recommended Action Plan (Priority Order)

1. **Context size tracking audit** — Verify `UsageTracker.computeSummary()` handles missing token data
2. **Loud truncation markers** — Add "showing X of Y" markers to `ContextCompactor`
3. **Per-project model overrides** — Add project-scoped settings layer to `ProviderManager`
4. **IDE log reader** — Port `IdeaLogReader.kt`, add `/logs` command
5. **Test isolation** — Isolate test home/config directories in `build.gradle.kts`
6. **Provider enable/disable toggle** — Add `enabled` field to `ProviderConfig`
7. **JCEF safe probe** — Add `JcefSupport.kt` for future web UI
8. **Unbounded until-build** — Make `untilBuild` optional for broader IDE compatibility
9. **API pre-flight check** — Verify endpoint/model availability before agent loop
10. **Performance health checks** — Add coroutine/memory/compaction effectiveness monitoring
