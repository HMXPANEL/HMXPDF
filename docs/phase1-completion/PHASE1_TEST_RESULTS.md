# Phase 1 test results

## Execution evidence — GitHub Actions run 43934592604

- Commit: `c031c732c6d9a8dff64f4fd8b2349ef5039e5ace`
- Branch: `release/hmxpdf-2.1.1-build` (the repository's actual default branch)
- Workflow: "Android build" (`.github/workflows/android.yml`)
- Run URL: https://github.com/bharti1233/Hmxpdf/actions/runs/43934592604
- Result: **completed / success**

Executed and passed:

- `./gradlew testPlayDebugUnitTest :pdf-core:test :ocr-core:test --rerun-tasks` — step success.
- `./gradlew lintPlayDebug` — step success.
- `./gradlew assemblePlayDebug` — step success; debug APK artifact `hmx-pdf-debug-apk` (170.70 MB) uploaded.
- Release job: `assemblePlayRelease` and `bundlePlayRelease` with signing secrets from the protected `release` environment; signature verified by `apksigner` (certificate SHA-256 must match the keystore) and `jarsigner`. Artifacts `hmx-pdf-release-apk` (134.59 MB) and `hmx-pdf-release-bundle` (51.17 MB) uploaded.
- `release-guard` job — success.

Honest limits of this evidence:

- The workflow does not upload JUnit XML reports, so exact executed/passed/failed/skipped counts are not published. Only the step outcome (success) is recorded here.
- No instrumentation (on-device) test was executed by this workflow.
- No test was skipped, disabled or weakened to achieve this result; the pre-existing `@Ignore`d classes were left untouched.

## Test changes in this phase

- `DocxReplaceTest`: out-of-bounds/expected-text stale rejection and marker offset/writer-position assertions.
- `DocxCommentsTest`: offset-aware marker assertions; editing before an existing comment keeps the range around its original text.
- `DocxRevisionsTest` and `DocxRoundTripTest`: marker assertions use the offset-aware model.
- `DocxEditorViewModeTest`: comment-marker assertion migrated from legacy `extras` to `markers`; View Mode remains read-only.
- Existing table-cell tests remained active and unchanged.
