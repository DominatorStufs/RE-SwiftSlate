# RE-SwiftSlate updated package

This package keeps Codex API and Unofficial Copilot API on top of the refreshed SwiftSlate code.

Debug/fix notes included in this ZIP:

- Fixed Android lint MissingTranslation errors for fork-only keyless-provider strings.
- Replaced the upstream release/signing-heavy GitHub Actions workflow with a fork-friendly `Build APK` workflow.
- The workflow now runs lint, unit tests, and builds a debug APK artifact without keystore secrets, tags, releases, or failure-issue creation.
- Removed the obsolete `Comment Preview APK` workflow so manually running the wrong workflow cannot fail the repo status.

After uploading, open Actions → Build APK. The APK will be in the `Build APK` job artifacts.
