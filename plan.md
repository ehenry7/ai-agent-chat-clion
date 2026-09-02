# CLion Plugin Gap Closure Plan

## Source Repositories

| Plugin | Path | Version |
|--------|------|---------|
| VSCode Plugin (Reference) | `C:\work\AgenticAI\ai-agent-chat` | 0.46.7 |
| CLion Plugin (Target) | `C:\work\ai-agent-chat-clion` | 0.46.5 |

---

## 1. Feature Gap Analysis

### 1.1 Completed Features ( parity achieved )

| Feature | VSCode | CLion |
|---------|--------|-------|
| Dual-phase execution (discovery/execution) | ✅ | ✅ |
| Semantic sliding window context compression | ✅ | ✅ |
| File read/write/edit | ✅ | ✅ |
| Directory listing and search | ✅ | ✅ |
| Git operations (status/diff/log/commit) | ✅ | ✅ |
| Shell execution (run_command/run_python) | ✅ | ✅ |
| File mention (@) support | ✅ | ✅ |
| History navigation (arrow keys) | ✅ | ✅ |
| Edit confirmation dialog | ✅ | ✅ |
| Markdown rendering | ✅ | ✅ |
| Slash commands (/config, /init, /memory, /new, /clear, /status) | ✅ | ✅ |

### 1.2 Missing Features ( gap to close )

| Feature | Priority | Effort | Impact |
|---------|----------|--------|--------|
| Multi-session management (save/load/list/rename/delete) | P0 | Medium | High |
| `fetch_url` tool | P0 | Small | High |
| `web_search` tool | P0 | Small | High |
| Empty response auto-retry (3 attempts) | P1 | Small | Medium |
| XML `<tool_call>` fallback extraction | P1 | Small | Medium |
| `show_quick_pick` / `show_input_box` equivalents | P1 | Medium | Medium |
| `run_in_terminal` capability | P1 | Medium | Medium |
| Additional slash commands (/stop, /plan, /todo, /save, /rename) | P1 | Small | Medium |
| `delete_file` tool | P2 | Small | Low |
| IntelliJ theme adaptation (light/dark/high-contrast) | P2 | Medium | Low |
| Syntax highlighting for code blocks | P3 | Medium | Low |
| Configurable maxSteps upper limit (up to 500) | P3 | Small | Low |

---

## 2. Implementation Plan

### Phase 1: Core Functionality Enhancement (1-2 weeks)

#### 1.1 Multi-Session Management

**Objective**: Implement complete multi-session management matching VSCode behavior.

**Files to modify**:
- `src/main/kotlin/com/aiagent/chat/persistence/Persistence.kt` — extend session management
- `src/main/kotlin/com/aiagent/chat/model/Models.kt` — add `SessionInfo` data class
- `src/main/kotlin/com/aiagent/chat/ui/ChatToolWindowPanel.kt` — add session list UI
- `src/main/kotlin/com/aiagent/chat/tools/SlashCommands.kt` — add `/sessions`, `/save`, `/rename` commands

**New functionality**:
1. `SESSIONS.md` — session index file stored in `.ai-agent-chat/`
2. Save current session to a named slot
3. List all saved sessions
4. Load a historical session
5. Rename a session
6. Delete a session

---

#### 1.2 Web Tools

**Objective**: Add `fetch_url` and `web_search` tools.

**Files to modify**:
- `src/main/kotlin/com/aiagent/chat/tools/PlatformTools.kt` — add `WebTools`
- `src/main/kotlin/com/aiagent/chat/model/Models.kt` — add `WebTool` definitions

**New tools**:
1. `fetch_url` — HTTP GET request to retrieve URL content (100KB limit)
2. `web_search` — search using configured search engine template (`webSearchUrl`)

---

#### 1.3 Agent Robustness Enhancement

**Objective**: Improve Agent execution stability.

**Files to modify**:
- `src/main/kotlin/com/aiagent/chat/agent/AgentEngine.kt` — enhance error handling and retry logic

**New functionality**:
1. Empty response auto-retry (up to 3 times)
2. XML `<tool_call>` tag fallback extraction
3. Raise `maxSteps` configurable upper limit to 500

---

### Phase 2: UI Interaction Enhancement (1 week)

#### 2.1 IntelliJ-Specific UI Interaction Tools

**Objective**: Implement equivalent interaction functionality on the IntelliJ platform.

**Files to modify**:
- `src/main/kotlin/com/aiagent/chat/tools/PlatformTools.kt` — add UI tools

**New tools**:
1. `show_quick_pick` → use IntelliJ's `ChooseFromElementsDialog` or `ListPopup`
2. `show_input_box` → use IntelliJ's `InputDialog`
3. `open_file_in_editor` → use `FileEditorManager.openFile()`
4. `run_in_terminal` → use `TerminalToolWindowFactory` or `ProcessHandler`

---

#### 2.2 Missing Slash Commands

**Objective**: Implement `/stop`, `/plan`, `/todo`, `/save`, `/rename` commands.

**Files to modify**:
- `src/main/kotlin/com/aiagent/chat/tools/SlashCommands.kt`

**New commands**:
| Command | Description |
|---------|-------------|
| `/stop` | Stop the currently running Agent |
| `/plan` | Display the execution plan for the current task |
| `/todo` | Manage the todo list |
| `/save [name]` | Save the current session |
| `/rename [new_name]` | Rename the current session |

---

### Phase 3: User Experience Optimization (1 week)

#### 3.1 Edit Functionality Completion

**Objective**: Add `delete_file` tool.

**Files to modify**:
- `src/main/kotlin/com/aiagent/chat/tools/PlatformTools.kt` — add `deleteFile` tool

---

#### 3.2 Theme Adaptation

**Objective**: Support IntelliJ theme colors.

**Files to modify**:
- `src/main/kotlin/com/aiagent/chat/ui/ChatToolWindowPanel.kt` — adjust colors based on `ThemeAdapter`

**Implementation**:
1. Implement `ApplicationComponent.ThemeChangedListener`
2. Adjust UI colors based on current theme (light/dark/high-contrast)

---

#### 3.3 Syntax Highlighting

**Objective**: Add syntax highlighting for code blocks.

**Files to modify**:
- `src/main/kotlin/com/aiagent/chat/ui/ChatToolWindowPanel.kt` — integrate syntax highlighting

**Implementation**:
1. Use IntelliJ's `SyntaxHighlighter`
2. Or integrate the `JBEHighlighting` library

---

### Phase 4: Testing and Documentation (ongoing)

#### 4.1 Unit Test Coverage

Add tests for:
- `AgentEngine` retry logic
- New web tools
- Session management functionality
- `DiffEngine` and `PatchEngine`

#### 4.2 Documentation Updates

- `README.md` — new feature descriptions
- `AGENTS.md` — developer guide

---

## 3. Implementation Priority

| Priority | Feature | Workload | Impact |
|----------|---------|----------|--------|
| P0 | Session management (save/load/list) | Medium | High |
| P0 | `fetch_url` tool | Small | High |
| P0 | `web_search` tool | Small | High |
| P1 | Agent retry and XML fallback | Small | Medium |
| P1 | `show_quick_pick` / `show_input_box` | Medium | Medium |
| P1 | `/stop`, `/plan`, `/todo` commands | Small | Medium |
| P2 | `delete_file` tool | Small | Low |
| P2 | `run_in_terminal` | Medium | Medium |
| P2 | Theme adaptation | Medium | Low |
| P3 | Syntax highlighting | Medium | Low |
| P3 | `maxSteps` upper limit increase | Small | Low |

---

## 4. Technical Challenges and Recommendations

| Challenge | Recommendation |
|-----------|----------------|
| IntelliJ UI interaction | Use `Messages.showInputDialog` and `ChooseFromElementsDialog` to replace VSCode's quickPick/inputBox |
| Terminal execution | Use `GeneralCommandLine` + `ProcessHandler` to implement `run_in_terminal` |
| Session storage | Reference VSCode's `.ai-agent-chat/session.json` format, use IntelliJ's `PathManager` to get plugin data directory |
| HTTP requests | Reuse `HttpClient` in `ApiClient.kt` to implement `fetch_url` |

---

## 5. File Change Summary

### New Files to Create

| File Path | Purpose |
|-----------|---------|
| `src/main/kotlin/com/aiagent/chat/tools/WebTools.kt` | Web tool implementations |
| `src/main/kotlin/com/aiagent/chat/tools/UiTools.kt` | UI interaction tools |
| `src/test/kotlin/com/aiagent/chat/agent/AgentEngineTest.kt` | Agent engine tests |
| `src/test/kotlin/com/aiagent/chat/tools/WebToolsTest.kt` | Web tools tests |

### Existing Files to Modify

| File | Modifications |
|------|---------------|
| `src/main/kotlin/com/aiagent/chat/agent/AgentEngine.kt` | Add retry logic, XML fallback, maxSteps config |
| `src/main/kotlin/com/aiagent/chat/tools/PlatformTools.kt` | Add WebTools, UiTools, delete_file, run_in_terminal |
| `src/main/kotlin/com/aiagent/chat/tools/SlashCommands.kt` | Add /stop, /plan, /todo, /save, /rename, /sessions |
| `src/main/kotlin/com/aiagent/chat/persistence/Persistence.kt` | Extend for multi-session management |
| `src/main/kotlin/com/aiagent/chat/model/Models.kt` | Add SessionInfo, WebTool definitions |
| `src/main/kotlin/com/aiagent/chat/ui/ChatToolWindowPanel.kt` | Session list UI, theme adaptation, syntax highlighting |
| `src/main/kotlin/com/aiagent/chat/services/ChatStateService.kt` | Add session-related configuration |
| `build.gradle.kts` | Add test dependencies |
| `README.md` | Update feature list |
| `AGENTS.md` | Update developer guide |

---

## 6. Verification Plan

After each phase, verify:
1. All existing tests pass
2. New functionality works as expected
3. No regression in existing features
4. Build completes successfully (`./gradlew buildPlugin`)
5. Plugin runs correctly in development IDE (`./gradlew runIde`)
