# AI Agent Chat for CLion

An agentic chat assistant ported natively for CLion/IntelliJ Platform, backed by an OpenAI-compatible chat-completions API. The agent can read and write files in your workspace, parse syntax, and run shell/Python commands to complete tasks.

## Features
- **Native Tool Window**: Docked chat interface built with Swing.
- **Agent Tools**: Native VFS-backed implementations of `read_file`, `write_file`, `edit_file`, `run_command`, `git_commit`, and more.
- **Persistence**: Two-tier memory scoping (`AGENTS.md` and global). API keys are stored securely using IntelliJ PasswordSafe.

## Development
```bash
./gradlew buildPlugin
./gradlew runIde
