# CLion Plugin Gap Closure — Todo List

This document contains a detailed, actionable todo list for closing the feature gap between the VSCode plugin (`C:\work\AgenticAI\ai-agent-chat`, v0.46.7) and the CLion plugin (`C:\work\ai-agent-chat-clion`, v0.46.5).

---

## Table of Contents

- [Phase 1: Core Functionality Enhancement](#phase-1-core-functionality-enhancement)
  - [P1-T1: Multi-Session Management](#p1-t1-multi-session-management)
  - [P1-T2: fetch_url Tool](#p1-t2-fetch_url-tool)
  - [P1-T3: web_search Tool](#p1-t3-web_search-tool)
  - [P1-T4: Agent Robustness Enhancement](#p1-t4-agent-robustness-enhancement)
- [Phase 2: UI Interaction Enhancement](#phase-2-ui-interaction-enhancement)
  - [P2-T1: IntelliJ UI Interaction Tools](#p2-t1-intellij-ui-interaction-tools)
  - [P2-T2: Missing Slash Commands](#p2-t2-missing-slash-commands)
- [Phase 3: User Experience Optimization](#phase-3-user-experience-optimization)
  - [P3-T1: delete_file Tool](#p3-t1-delete_file-tool)
  - [P3-T2: run_in_terminal Capability](#p3-t2-run_in_terminal-capability)
  - [P3-T3: Theme Adaptation](#p3-t3-theme-adaptation)
  - [P3-T4: Syntax Highlighting](#p3-t4-syntax-highlighting)
- [Phase 4: Testing and Documentation](#phase-4-testing-and-documentation)
  - [P4-T1: Unit Test Coverage](#p4-t1-unit-test-coverage)
  - [P4-T2: Documentation Updates](#p4-t2-documentation-updates)
- [Test and Verification Checklist](#test-and-verification-checklist)

---

## Phase 1: Core Functionality Enhancement

**Goal**: Implement the highest-impact missing features.
**Timeline**: 1-2 weeks

---

### P1-T1: Multi-Session Management

**Priority**: P0 | **Estimated Effort**: Medium | **Impact**: High

#### What to do

Implement full multi-session management to match VSCode behavior. Currently the CLion plugin only maintains a single active session. Users need the ability to save, load, list, rename, and delete sessions.

#### How to do it

1. **Add `SessionInfo` data class** to `src/main/kotlin/com/aiagent/chat/model/Models.kt`:
   - Fields: `id: String`, `name: String`, `createdAt: Long`, `updatedAt: Long`, `messageCount: Int`, `lastMessagePreview: String`
   - Add `sessions: List<SessionInfo>` field to `SessionState`

2. **Create `SESSIONS.md` session index** in the `.ai-agent-chat/` directory within the project workspace (not the plugin config directory). This file stores metadata for all saved sessions.
   - Format: JSON array of `SessionInfo` objects
   - Location: `{workspace}/.ai-agent-chat/sessions.json`

3. **Extend `Persistence.kt`**:
   - Add `saveSession(name: String)` — serialize current `SessionState` to `{workspace}/.ai-agent-chat/sessions/{id}.json`
   - Add `loadSession(id: String)` — deserialize and replace current session
   - Add `listSessions(): List<SessionInfo>` — read from `sessions.json`
   - Add `deleteSession(id: String)` — delete file and remove from index
   - Add `renameSession(id: String, newName: String)` — update name in index and file

4. **Add session list UI** to `ChatToolWindowPanel.kt`:
   - Add a "Sessions" button or dropdown in the tool window toolbar
   - Display a popup/list showing all saved sessions with name and timestamp
   - Allow click-to-load functionality

5. **Register new slash commands** in `SlashCommands.kt`:
   - `/sessions` — open the session list popup
   - `/save [name]` — save current session (use auto-generated name if not provided)
   - `/rename [new_name]` — rename current session

#### Reference implementation

VSCode implementation in `C:\work\AgenticAI\ai-agent-chat\src\persistence.ts` — look for `saveSession`, `loadSession`, `listSessions`, `deleteSession`, and `renameSession` functions. Also reference the session file format in `.ai-agent-chat/sessions/` directory.

#### Files affected

| File | Change |
|------|--------|
| `src/main/kotlin/com/aiagent/chat/model/Models.kt` | Add `SessionInfo` data class |
| `src/main/kotlin/com/aiagent/chat/persistence/Persistence.kt` | Add session CRUD operations |
| `src/main/kotlin/com/aiagent/chat/ui/ChatToolWindowPanel.kt` | Add session list UI |
| `src/main/kotlin/com/aiagent/chat/tools/SlashCommands.kt` | Add `/sessions`, `/save`, `/rename` commands |
| `src/main/kotlin/com/aiagent/chat/services/ChatStateService.kt` | Add session-related config if needed |

#### Dependencies

- Requires `Persistence.kt` to be stable before UI integration
- UI changes depend on data model finalization

#### Verification

- [ ] Save a session with a custom name
- [ ] Verify session file is created in `.ai-agent-chat/sessions/`
- [ ] Load the saved session and verify messages are restored
- [ ] List all sessions and verify correct metadata
- [ ] Rename a session and verify the name updates
- [ ] Delete a session and verify file removal
- [ ] No regression in existing chat functionality

---

### P1-T2: fetch_url Tool

**Priority**: P0 | **Estimated Effort**: Small | **Impact**: High

#### What to do

Add a `fetch_url` tool that allows the Agent to perform HTTP GET requests to retrieve content from URLs. This is needed for web research tasks.

#### How to do it

1. **Add `WebTool` definitions** to `src/main/kotlin/com/aiagent/chat/model/Models.kt`:
   ```kotlin
   data class FetchUrlTool(
       val name: String = "fetch_url",
       val description: String = "Performs an HTTP GET request to retrieve content from a URL. Use this when you need to fetch information from the web. Max response size: 100KB.",
       val inputSchema: Map<String, Any> = mapOf(
           "type" to "object",
           "properties" to mapOf(
               "url" to mapOf("type" to "string", "description" to "The URL to fetch"),
               "headers" to mapOf("type" to "object", "description" to "Optional HTTP headers")
           ),
           "required" to listOf("url")
       )
   )
   ```

2. **Implement the tool handler** in `src/main/kotlin/com/aiagent/chat/tools/PlatformTools.kt` (or create a new `WebTools.kt` file):
   - Use `java.net.URL` and `java.net.HttpURLConnection` (or reuse `ApiClient.kt`'s `HttpClient`)
   - Set request method to GET
   - Add optional headers support
   - Enforce 100KB response size limit — if content exceeds, truncate and add a note
   - Handle timeouts (connect: 10s, read: 30s)
   - Return JSON: `{ "content": "...", "statusCode": 200, "headers": {...} }`

3. **Register the tool** in the tool definitions list so it is included in the system prompt.

#### Reference implementation

VSCode implementation in `C:\work\AgenticAI\ai-agent-chat\src\tools.ts` — look for `fetch_url` in the tools array and its handler.

#### Files affected

| File | Change |
|------|--------|
| `src/main/kotlin/com/aiagent/chat/model/Models.kt` | Add `FetchUrlTool` definition |
| `src/main/kotlin/com/aiagent/chat/tools/PlatformTools.kt` | Add fetch_url handler |

#### Dependencies

- None — uses existing `HttpClient` from `ApiClient.kt` or standard Java networking

#### Verification

- [ ] Fetch a simple URL and verify content is returned
- [ ] Verify 100KB truncation works for large responses
- [ ] Verify headers are correctly passed
- [ ] Verify error handling for invalid URLs
- [ ] Verify timeout handling

---

### P1-T3: web_search Tool

**Priority**: P0 | **Estimated Effort**: Small | **Impact**: High

#### What to do

Add a `web_search` tool that allows the Agent to search the web using a configurable search engine. This should use the `webSearchUrl` configuration template.

#### How to do it

1. **Add `WebSearchTool` definition** to `src/main/kotlin/com/aiagent/chat/model/Models.kt`:
   ```kotlin
   data class WebSearchTool(
       val name: String = "web_search",
       val description: String = "Searches the web using a search engine. Use this when you need to find information that you cannot get from local files or the current project.",
       val inputSchema: Map<String, Any> = mapOf(
           "type" to "object",
           "properties" to mapOf(
               "query" to mapOf("type" to "string", "description" to "The search query")
           ),
           "required" to listOf("query")
       )
   )
   ```

2. **Implement the tool handler** in `WebTools.kt`:
   - Read `webSearchUrl` from `ChatStateService`
   - URL template format: `https://search.example.com/search?q={query}`
   - Replace `{query}` with URL-encoded search query
   - Use `fetch_url` internally to perform the actual HTTP request
   - Parse the search results from the response (format depends on search engine)
   - Return formatted search results: `{ "results": [...], "query": "..." }`

3. **Register the tool** in the tool definitions list.

#### Reference implementation

VSCode implementation in `C:\work\AgenticAI\ai-agent-chat\src\tools.ts` — look for `web_search` in the tools array. Also check `src\tools.ts` for the `webSearchUrl` configuration.

#### Files affected

| File | Change |
|------|--------|
| `src/main/kotlin/com/aiagent/chat/model/Models.kt` | Add `WebSearchTool` definition |
| `src/main/kotlin/com/aiagent/chat/tools/WebTools.kt` | Add web_search handler |
| `src/main/kotlin/com/aiagent/chat/services/ChatStateService.kt` | Ensure `webSearchUrl` config exists |

#### Dependencies

- Depends on `P1-T2: fetch_url` being implemented (web_search can use fetch_url internally)

#### Verification

- [ ] Perform a web search and verify results are returned
- [ ] Verify the search uses the configured `webSearchUrl` template
- [ ] Verify query is properly URL-encoded
- [ ] Verify error handling for failed searches

---

### P1-T4: Agent Robustness Enhancement

**Priority**: P1 | **Estimated Effort**: Small | **Impact**: Medium

#### What to do

Enhance the `AgentEngine` to handle edge cases that cause retries or parsing failures, specifically: empty responses and XML tool_call tags.

#### How to do it

##### 4a. Empty Response Auto-Retry

In `src/main/kotlin/com/aiagent/chat/agent/AgentEngine.kt`:

1. After receiving an API response, check if `content` is null or empty
2. If empty, increment a retry counter
3. If retry count < 3, re-send the same request
4. If retry count >= 3, treat as a fatal error and return failure to UI

##### 4b. XML `<tool_call>` Fallback Extraction

In `src/main/kotlin/com/aiagent/chat/agent/AgentEngine.kt`:

1. After normal JSON parsing attempt, if no valid tool calls found, fall back to regex extraction
2. Pattern: `<tool_call>\s*<name>(\w+)</name>\s*<arguments>(.*?)</arguments>\s*</tool_call>`
3. Parse the extracted `name` and `arguments` (arguments may be JSON)
4. If multiple matches found, create multiple `ToolCall` entries
5. Log a warning that XML fallback was used

##### 4c. Raise maxSteps Configurable Upper Limit

In `src/main/kotlin/com/aiagent/chat/services/ChatStateService.kt` and `ChatToolWindowPanel.kt`:

1. Change the max validation for `maxSteps` from current limit (e.g., 25 or 100) to 500
2. Update the settings UI spinner/input to allow up to 500
3. Update the configuration description in `plugin.xml` if applicable

#### Reference implementation

VSCode implementation in `C:\work\AgenticAI\ai-agent-chat\src\agent.ts` — look for `runAgent()`, `retryCount`, and XML fallback regex patterns.

#### Files affected

| File | Change |
|------|--------|
| `src/main/kotlin/com/aiagent/chat/agent/AgentEngine.kt` | Add retry logic and XML fallback |
| `src/main/kotlin/com/aiagent/chat/services/ChatStateService.kt` | Raise maxSteps limit to 500 |
| `src/main/kotlin/com/aiagent/chat/ui/ChatToolWindowPanel.kt` | Update maxSteps input UI |

#### Dependencies

- None — self-contained changes in AgentEngine

#### Verification

- [ ] Verify empty response triggers retry up to 3 times
- [ ] Verify XML `<tool_call>...</tool_call>` format is correctly parsed
- [ ] Verify maxSteps can be set to 500 without error
- [ ] Verify no regression in normal JSON tool_call parsing

---

## Phase 2: UI Interaction Enhancement

**Goal**: Implement IntelliJ-equivalent UI interaction tools and missing slash commands.
**Timeline**: 1 week

---

### P2-T1: IntelliJ UI Interaction Tools

**Priority**: P1 | **Estimated Effort**: Medium | **Impact**: Medium

#### What to do

Implement tools that provide interactive UI capabilities equivalent to VSCode's `show_quick_pick`, `show_input_box`, and `open_file_in_editor`. Also implement `run_in_terminal`.

#### How to do it

##### 5a. show_quick_pick

In `PlatformTools.kt`, add a handler that:
1. Takes `items: List<String>` and optional `placeholder: String`
2. Uses `com.intellij.ui.popup.PopupFactoryImpl.getActivityPopup()` or `com.intellij.ui.ListPopup`
3. Shows a modal list popup
4. Returns the selected item string, or `null` if cancelled

##### 5b. show_input_box

In `PlatformTools.kt`, add a handler that:
1. Takes `prompt: String` and optional `defaultValue: String`
2. Uses `com.intellij.openapi.ui.Messages.showInputDialog()`
3. Returns the input string, or `null` if cancelled

##### 5c. open_file_in_editor

In `PlatformTools.kt`, add a handler that:
1. Takes `filePath: String` and optional `lineNumber: Int`
2. Uses `com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile()`
3. If `lineNumber` is provided, also navigate to that line using `FileEditorManager.openFile()` with `OpenFileDescriptor`
4. Returns success/failure result

##### 5d. run_in_terminal

In `PlatformTools.kt`, add a handler that:
1. Takes `command: String` and optional `workingDirectory: String`
2. Uses `com.intellij.execution.configurations.GeneralCommandLine`
3. Uses `com.intellij.execution.process.ProcessHandler` to execute
4. Attaches a `ProcessListener` to capture output
5. Returns the stdout/stderr output after execution completes

#### Reference implementation

VSCode implementation in `C:\work\AgenticAI\ai-agent-chat\src\tools.ts` — look for `show_quick_pick`, `show_input_box`, `open_file_in_editor`, and `run_in_terminal` in the tools array.

IntelliJ platform APIs to use:
- `com.intellij.openapi.ui.Messages`
- `com.intellij.openapi.fileEditor.FileEditorManager`
- `com.intellij.execution.configurations.GeneralCommandLine`
- `com.intellij.execution.process.ProcessHandler`

#### Files affected

| File | Change |
|------|--------|
| `src/main/kotlin/com/aiagent/chat/tools/PlatformTools.kt` | Add all 4 UI tool handlers |

#### Dependencies

- Requires project instance — tool execution context must include `Project`

#### Verification

- [ ] `show_quick_pick` displays a list and returns selected item
- [ ] `show_input_box` displays an input dialog and returns user input
- [ ] `open_file_in_editor` opens a file in the editor and optionally navigates to a line
- [ ] `run_in_terminal` executes a command and returns output

---

### P2-T2: Missing Slash Commands

**Priority**: P1 | **Estimated Effort**: Small | **Impact**: Medium

#### What to do

Implement the missing slash commands: `/stop`, `/plan`, `/todo`.

The `/save`, `/rename` commands are already covered by P1-T1 (Multi-Session Management).

#### How to do it

In `src/main/kotlin/com/aiagent/chat/tools/SlashCommands.kt`:

##### 6a. /stop

1. Check if Agent is currently running (track state in `AgentEngine`)
2. If running, set a cancellation flag
3. The Agent loop checks the flag on each iteration and exits gracefully
4. Return "Agent stopped." message to chat

##### 6b. /plan

1. Read the current user message history
2. Generate a summary of what the Agent has done so far
3. Display a formatted plan showing completed steps and remaining work
4. Reference the `TodoItem` list from `Todos.kt` for pending items

##### 6c. /todo

1. Display the current todo list (from `Todos.kt`)
2. Allow the user to mark items as complete/incomplete via UI
3. Sync changes back to the agent's context

#### Reference implementation

VSCode implementation in `C:\work\AgenticAI\ai-agent-chat\src\tools\commands\built-in-commands.ts` — look for `/stop`, `/plan`, and `/todo` handlers.

#### Files affected

| File | Change |
|------|--------|
| `src/main/kotlin/com/aiagent/chat/tools/SlashCommands.kt` | Add `/stop`, `/plan`, `/todo` handlers |
| `src/main/kotlin/com/aiagent/chat/agent/AgentEngine.kt` | Add cancellation flag check |

#### Dependencies

- `/stop` depends on `AgentEngine` having a cancellable state
- `/plan` depends on message history being accessible
- `/todo` depends on `Todos.kt` integration

#### Verification

- [ ] `/stop` successfully halts a running Agent
- [ ] `/plan` displays a meaningful summary of current task progress
- [ ] `/todo` displays the current todo list

---

## Phase 3: User Experience Optimization

**Goal**: Complete edit functionality, add theme adaptation and syntax highlighting.
**Timeline**: 1 week

---

### P3-T1: delete_file Tool

**Priority**: P2 | **Estimated Effort**: Small | **Impact**: Low

#### What to do

Add a `delete_file` tool to allow the Agent to delete files from the workspace.

#### How to do it

In `src/main/kotlin/com/aiagent/chat/tools/PlatformTools.kt`:

1. Add handler for `delete_file` tool
2. Takes parameter `filePath: String`
3. Use IntelliJ VFS: `VirtualFileManager.getInstance().findFileByUrl()` then `delete()`
4. Show confirmation dialog before deletion (already handled by existing confirmation mechanism)
5. Return success/failure result

#### Reference implementation

VSCode implementation in `C:\work\AgenticAI\ai-agent-chat\src\tools.ts` — look for `delete_file` in the tools array.

#### Files affected

| File | Change |
|------|--------|
| `src/main/kotlin/com/aiagent/chat/tools/PlatformTools.kt` | Add delete_file handler |

#### Verification

- [ ] Delete a file via the Agent and verify it is removed from filesystem
- [ ] Verify confirmation dialog appears before deletion
- [ ] Verify error handling for non-existent files

---

### P3-T2: run_in_terminal Capability

**Priority**: P2 | **Estimated Effort**: Medium | **Impact**: Medium

#### Note

This is the same as P2-T1 item 5d. The `run_in_terminal` tool is listed here for completeness since it was identified as a separate gap item in the analysis.

If already implemented in P2-T1, mark this as complete.

---

### P3-T3: Theme Adaptation

**Priority**: P2 | **Estimated Effort**: Medium | **Impact**: Low

#### What to do

Adapt the ChatToolWindowPanel UI to respond to IntelliJ theme changes (light/dark/high-contrast).

#### How to do it

In `src/main/kotlin/com/aiagent/chat/ui/ChatToolWindowPanel.kt`:

1. Implement `com.intellij.openapi.wm.impl.ToolWindowHeadlessPanelImpl` or register a `ThemeChangeListener`
2. Use `com.intellij.ide.ui.UIUtilities` and `JBColor` to query current theme colors
3. On theme change:
   - Query background color: `UIUtil.getPanelBackground()` or `JBColor.Panel`
   - Query text color: `JBColor.Foreground` or `UIUtil.getLabelForeground()`
   - Query accent color: look for `JBColor.accent` or similar
   - Update all UI component colors accordingly
4. Handle special cases:
   - Light theme: use dark text on light background
   - Dark theme: use light text on dark background
   - High contrast: use maximum contrast colors

#### Reference implementation

Look at how other IntelliJ plugins handle theming, such as the AWS Toolkit or any plugin using `JBColor` and `UIUtil`.

#### Files affected

| File | Change |
|------|--------|
| `src/main/kotlin/com/aiagent/chat/ui/ChatToolWindowPanel.kt` | Add theme listener and color updates |

#### Dependencies

- None — uses IntelliJ platform APIs

#### Verification

- [ ] Switch IntelliJ to dark theme and verify chat panel adapts
- [ ] Switch IntelliJ to light theme and verify chat panel adapts
- [ ] Switch to high-contrast theme and verify chat panel adapts

---

### P3-T4: Syntax Highlighting

**Priority**: P3 | **Estimated Effort**: Medium | **Impact**: Low

#### What to do

Add syntax highlighting for code blocks displayed in the chat UI, similar to the VSCode plugin's `highlight.ts`.

#### How to do it

In `src/main/kotlin/com/aiagent/chat/ui/ChatToolWindowPanel.kt`:

1. **Option A (preferred) — Use IntelliJ's SyntaxHighlighter**:
   - Get `SyntaxHighlighterFactory.getSyntaxHighlighter(project, null, null)` for a given file type
   - Use `HighlighterIterator` to tokenize code
   - Apply colors to text segments

2. **Option B — Use a lightweight highlighting library**:
   - Integrate a library like `org.fxmisc.richtext` (JavaFX-based) for syntax highlighting
   - Or integrate `com.github.icegalaxy.Highlighter` or similar

3. Apply highlighting to code blocks in chat messages:
   - Detect code blocks (language identifier + content) from markdown
   - Apply syntax highlighting based on the language identifier
   - Fall back to plain text if language is unknown

#### Reference implementation

VSCode implementation in `C:\work\AgenticAI\ai-agent-chat\src\webview\highlight.ts` — look for language detection and tokenization logic.

#### Files affected

| File | Change |
|------|--------|
| `src/main/kotlin/com/aiagent/chat/ui/ChatToolWindowPanel.kt` | Integrate syntax highlighting |

#### Dependencies

- Requires a syntax highlighter library or IntelliJ's built-in highlighter

#### Verification

- [ ] Code blocks in chat messages display with syntax highlighting
- [ ] Multiple languages are correctly highlighted (e.g., Kotlin, Python, JavaScript)
- [ ] Unknown languages fall back to plain text

---

## Phase 4: Testing and Documentation

**Goal**: Ensure quality through tests and update documentation.
**Timeline**: Ongoing

---

### P4-T1: Unit Test Coverage

**Priority**: P1 | **Estimated Effort**: Medium | **Impact**: Medium

#### What to do

Add unit tests for new functionality to prevent regressions.

#### How to do it

##### Test files to create

1. **`src/test/kotlin/com/aiagent/chat/agent/AgentEngineTest.kt`**:
   - Test empty response retry logic (verify retry count increments, verify max retry limit)
   - Test XML `<tool_call>` fallback extraction (happy path, multiple tool calls, malformed XML)
   - Test maxSteps configuration boundary (verify 500 is accepted, verify values > 500 are rejected)

2. **`src/test/kotlin/com/aiagent/chat/tools/WebToolsTest.kt`**:
   - Test `fetch_url` with a mock HTTP server (use MockWebServer or similar)
   - Test 100KB truncation boundary
   - Test timeout handling
   - Test `web_search` with mock fetch_url

3. **`src/test/kotlin/com/aiagent/chat/tools/SlashCommandsTest.kt`**:
   - Test `/stop` command
   - Test `/plan` command output
   - Test `/todo` command
   - Test `/sessions`, `/save`, `/rename` commands (depends on P1-T1)

4. **`src/test/kotlin/com/aiagent/chat/persistence/PersistenceTest.kt`**:
   - Test session save/load roundtrip
   - Test session list sorting
   - Test session deletion
   - Test rename operation

##### Test framework

Use the existing test framework already configured in the project. If JUnit 5 is not yet configured, add it to `build.gradle.kts`:

```kotlin
dependencies {
    testImplementation("junit:junit:4.13.2")
    // or JUnit 5 if preferred
}
```

#### Files affected

| File | Change |
|------|--------|
| `src/test/kotlin/com/aiagent/chat/agent/AgentEngineTest.kt` | New — Agent engine tests |
| `src/test/kotlin/com/aiagent/chat/tools/WebToolsTest.kt` | New — Web tools tests |
| `src/test/kotlin/com/aiagent/chat/tools/SlashCommandsTest.kt` | New — Slash commands tests |
| `src/test/kotlin/com/aiagent/chat/persistence/PersistenceTest.kt` | New — Persistence tests |
| `build.gradle.kts` | Add test dependencies if needed |

#### Verification

- [ ] All new tests pass
- [ ] No regression in existing tests
- [ ] Run `./gradlew test` successfully

---

### P4-T2: Documentation Updates

**Priority**: P2 | **Estimated Effort**: Small | **Impact**: Low

#### What to do

Update the project documentation to reflect new features.

#### How to do it

##### README.md updates

Add sections for:
1. Multi-session management (new feature)
2. Web tools (`fetch_url`, `web_search`)
3. UI interaction tools
4. Additional slash commands

##### AGENTS.md updates

Update the developer guide with:
1. Architecture notes for new components (WebTools.kt, UiTools.kt)
2. How to add a new tool
3. How to add a new slash command
4. Session management internal design

#### Files affected

| File | Change |
|------|--------|
| `README.md` | Add new feature documentation |
| `AGENTS.md` | Update developer guide |

#### Verification

- [ ] README.md includes all new features
- [ ] AGENTS.md is accurate and up to date

---

## Test and Verification Checklist

Run this checklist after completing each phase:

### Pre-build checks

- [ ] Code compiles without errors: `./gradlew compileKotlin`
- [ ] No linter warnings (if configured)
- [ ] All new files have correct package declarations

### Functional verification

- [ ] All existing chat functionality still works
- [ ] New tools (`fetch_url`, `web_search`, `delete_file`, etc.) are registered and callable
- [ ] New slash commands are registered and executable
- [ ] Session save/load/delete works correctly
- [ ] Agent retry and XML fallback works in edge cases

### Build verification

- [ ] Full plugin build succeeds: `./gradlew buildPlugin`
- [ ] No warnings or errors in build output
- [ ] Plugin ZIP artifact is generated in `build/distributions/`

### Runtime verification

- [ ] Plugin installs correctly in CLion
- [ ] Plugin activates without errors
- [ ] Tool window appears and is usable
- [ ] All UI interactions work as expected
- [ ] Theme adaptation works (if implemented)
- [ ] Syntax highlighting works (if implemented)

### Test verification

- [ ] All unit tests pass: `./gradlew test`
- [ ] Test coverage report generated (if configured)

---

## File Summary

### New files to create

| File | Purpose |
|------|---------|
| `src/main/kotlin/com/aiagent/chat/tools/WebTools.kt` | fetch_url and web_search implementations |
| `src/test/kotlin/com/aiagent/chat/agent/AgentEngineTest.kt` | Agent engine unit tests |
| `src/test/kotlin/com/aiagent/chat/tools/WebToolsTest.kt` | Web tools unit tests |

### Files to modify

| File | Changes |
|------|---------|
| `src/main/kotlin/com/aiagent/chat/agent/AgentEngine.kt` | Retry logic, XML fallback |
| `src/main/kotlin/com/aiagent/chat/tools/PlatformTools.kt` | WebTools integration, delete_file, UI tools, run_in_terminal |
| `src/main/kotlin/com/aiagent/chat/tools/SlashCommands.kt` | /stop, /plan, /todo, /sessions, /save, /rename |
| `src/main/kotlin/com/aiagent/chat/persistence/Persistence.kt` | Multi-session management |
| `src/main/kotlin/com/aiagent/chat/model/Models.kt` | SessionInfo, FetchUrlTool, WebSearchTool |
| `src/main/kotlin/com/aiagent/chat/ui/ChatToolWindowPanel.kt` | Session list UI, theme adaptation, syntax highlighting |
| `src/main/kotlin/com/aiagent/chat/services/ChatStateService.kt` | maxSteps limit increase, webSearchUrl config |
| `build.gradle.kts` | Test dependencies |
| `README.md` | Feature documentation updates |
| `AGENTS.md` | Developer guide updates |

---

*Generated from `plan.md` — CLion Plugin Gap Closure Plan*
