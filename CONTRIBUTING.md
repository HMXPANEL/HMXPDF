# Contributing to HMXPDF

Thanks for helping improve HMXPDF. Bug reports, usability feedback, documentation
fixes, and focused code changes are welcome.

This guide describes how the project is actually built and reviewed. If a step
here does not match what you see in the repository, please open an issue.

## Project principles

- **Keep documents local.** Do not add a server that receives user files or
  extracted text. New network behaviour must be explicit, user-initiated, and
  documented in [PRIVACY.md](PRIVACY.md).
- **Prefer small, reviewable changes** with a clear user benefit.
- **Be honest about limits.** Do not describe a feature as complete when only part
  of the flow works. Document known limits where users will see them.
- **Preserve accessibility and both themes.** Check light mode, dark mode, and
  large font scales for UI changes.
- **Respect licenses.** Every new dependency or asset needs compatible licensing,
  an entry in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), and attribution.

## Before you start

1. Search existing issues and pull requests so work is not duplicated.
2. For a larger feature, open a feature request first so scope can be agreed.
3. Never commit private documents, signing keys, `key.properties`, keystores,
   APKs, build outputs, or generated IDE files.

## Repository setup

### Branching model

- `release/hmxpdf-2.1.1-build` is the **primary development branch**, the GitHub
  default branch, and the integration branch. Open pull requests against it.
- `main` is a **backup snapshot only**. It is not a development branch, it is not
  updated automatically, and it is fast-forwarded only when a maintainer
  explicitly requests a backup update. Do not open pull requests against it.
- The complete policy, pre-task checklist, and backup procedure are in
  [docs/BRANCHING.md](docs/BRANCHING.md).
- Create a short-lived topic branch from the integration branch, for example:

  ```bash
  git switch release/hmxpdf-2.1.1-build
  git pull --ff-only
  git switch -c fix/docx-table-save
  ```

- Rebase or merge the integration branch into your topic branch as needed, but do
  not force-push shared branches and do not rewrite published history.

### Requirements

- Android Studio with **JDK 17**.
- Android SDK Platform **36** and Build Tools **36.1.0**.
- A device or emulator on Android 6.0 (API 23) or newer for UI work.

### Clone and build

```bash
git clone https://github.com/HMXPANEL/HMXPDF.git
cd Hmxpdf
./gradlew :app:assemblePlayDebug
```

On Windows use `gradlew.bat`. Product flavors:

| Flavor | Purpose |
|---|---|
| `play` | Google Play delivery, Play services components available |
| `foss` | Sideload build with the downloadable Office engine |
| `fdroid` | FOSS-only build without Google Play services or ML Kit |

Debug builds share the application ID `com.hmx.pdf`, so they install over each
other. For a sideload release build use `./gradlew :app:assembleFossRelease`.

### Signing

Release signing is local only. Copy `key.properties.example` to `key.properties`
and point it at your own keystore. Never commit either file. Without credentials
Gradle falls back to the debug key and prints a warning; do not distribute that
build as an official release.

## Coding conventions

### Kotlin

- Follow the existing package layout under `app/src/main/java/com/hmx/pdf/`.
- Prefer immutable state and `StateFlow` for view-model state; keep UI state
  hoisted and Compose functions stateless where practical.
- Use coroutines with structured concurrency. Do not block the main thread on
  document I/O, parsing, or rendering.
- Handle errors explicitly. Document operations can fail on malformed files,
  revoked URIs, and full storage; surface a usable message instead of crashing.
- Keep `minSdk` 23 compatibility: avoid APIs that require a higher level unless
  guarded by a version check.
- Avoid unrelated reformatting. A pull request should be reviewable on its own.

### Jetpack Compose

- Use `MaterialTheme` colours and the project's design tokens rather than hard-coded
  colours, so light and dark themes stay consistent.
- Provide content descriptions for interactive icons and images.
- Test layouts at large font scales and narrow widths; avoid fixed heights for text.
- Prefer `LazyColumn` for long document lists and keep recomposition scoped.

### Native and PDF code

- `pdf-core` operations must remain free of Android UI dependencies where possible
  so they can be covered by JVM tests.
- Close PDF documents, streams, and temp files in `finally` blocks.
- Never overwrite the user's source file in place; write to a new file and let the
  user choose the destination.

## Testing requirements

Before opening a pull request, run the checks used by CI:

```bash
./gradlew testPlayDebugUnitTest :pdf-core:test :ocr-core:test
./gradlew lintPlayDebug
./gradlew :app:assemblePlayDebug
```

- Add or update JVM unit tests for logic changes, especially parsers, writers, and
  PDF operations.
- UI changes should include a short manual test note with device model and Android
  version, plus screenshots or a recording.
- If a behaviour cannot be tested in CI (for example, a real device force-stop or
  a hardware TTS engine), say so explicitly in the pull request instead of implying
  it was verified.

## Documentation requirements

- Update [README.md](README.md) when user-facing capabilities, build steps, or
  requirements change.
- Update [PRIVACY.md](PRIVACY.md) for any change to network use, permissions, or
  data handling.
- Update [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [NOTICE](NOTICE) for
  new dependencies, assets, or adapted source.
- Update [CHANGELOG.md](CHANGELOG.md) for notable user-visible changes.

## Reporting issues

Use the [bug report form](https://github.com/HMXPANEL/HMXPDF/issues/new?template=bug_report.yml)
and include:

- HMXPDF version (Settings → About) and where you installed it from.
- Device model and Android version.
- Steps to reproduce, expected behaviour, and actual behaviour.
- Relevant logs or screenshots with private document content, names, paths, and
  tokens removed.

For feature ideas, use the
[feature request form](https://github.com/HMXPANEL/HMXPDF/issues/new?template=feature_request.yml).
For security problems, follow [SECURITY.md](SECURITY.md) and do not open a public
issue.

## Pull request expectations

A good pull request:

- Explains **what** changed and **why**, and links the related issue.
- Stays focused; unrelated refactors belong in a separate pull request.
- States how it was verified (commands, device, Android version).
- Notes known limitations and follow-up work.
- Updates documentation and notices where behaviour or dependencies changed.
- Passes CI: unit tests, lint, and the debug build must be green.

Reviews focus on correctness, data safety, offline behaviour, licensing, and
maintainability. Reviewers may ask for a smaller change or for tests before merge.

By participating you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).
