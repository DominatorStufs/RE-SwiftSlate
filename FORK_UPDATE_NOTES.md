# RE-SwiftSlate fork update notes

This codebase was refreshed from the latest upstream SwiftSlate source and keeps the fork's extra keyless AI providers:

- Codex API (`https://chatbot.codexapi.workers.dev`) with random/chosen model support.
- Unofficial Copilot API (`https://copilot-api-delta.vercel.app/v1/chat/completions`) with fixed `copilot` model.

Credit for both community endpoints: @nepcodexcc.

Validation performed in this workspace:

- `./gradlew :app:compileDebugKotlin` completed successfully after installing a temporary JDK/Android SDK in the sandbox.
- `./gradlew :app:testDebugUnitTest` reached test execution, but the Gradle daemon disappeared before the test report was written in this sandbox. Main and test Kotlin compilation had already completed.
