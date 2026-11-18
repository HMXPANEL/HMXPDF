# HMXPDF build, release and compatibility audit

No builds, dependency resolution or APK execution were performed. This report describes configuration and risks; it does not certify build success or release readiness.

## Declared toolchain and variants

| Item | Value / evidence |
|---|---|
| AGP | 9.0.0, gradle/libs.versions.toml:2 |
| Kotlin plugin catalog | 2.3.0, catalog:3; AGP built-in Kotlin use must be verified in resolved build |
| Gradle | 9.3.0, gradle/wrapper/gradle-wrapper.properties:3 |
| Java toolchain | 17, app/build.gradle.kts:208; CI setup-java:26 |
| SDK | compile/target 36, min 23, build tools 36.1.0; app/build.gradle.kts:58–69 |
| Identity | com.hmx.pdf; 2.1.1 / 6 |
| Variants | play, foss, fdroid; app/build.gradle.kts:130–154 |
| Native ABI APK splits | armeabi-v7a, arm64-v8a, x86, x86_64 plus universal; :166–176 |
| Optional Office ABIs | ARM32/ARM64 only, OfficeEngineManifest.kt:27–65 |
| Shrinking | Debug R8 enabled for POI compatibility; release minify + resource shrink; :89–114 |
| Unit stubs | isReturnDefaultValues=true; :119–123; can hide unsupported Android calls in JVM tests |

Five module directories exist; settings includes office_engine only for bundle task names (:39). Its dynamic-feature dependency on app is configured, but app.dynamicFeatures registration is absent; comments explicitly describe it as unbundled (:155–159). `bundlePlayRelease` still includes the module through settings. Treat this as an integration risk requiring CI task-graph/bundle inspection, not a demonstrated build failure.

F-Droid has a stronger source-level blocker: `app/src/main/java/com/hmx/pdf/office/OfficeEngineFlavor.kt:6` and `app/src/fdroid/java/com/hmx/pdf/office/OfficeEngineFlavor.kt:13` declare the same fully qualified object. Kotlin source sets combine rather than resource-overlay these declarations. No exclusion is configured. CI only builds Play, leaving this uncovered (FA-02).

## Release and signing

`app/build.gradle.kts:16–55` loads signing values from properties/environment and rejects release-producing tasks without configured signing. This audit did not read signing values or secret files. CI materializes a temporary keystore, verifies APK signing fingerprints and rejects debug-signed artifacts, then cleans it in an always step (`android.yml:89–220`). AAB verification checks signed/not-debug but does not compare its certificate fingerprint with the configured expected fingerprint as explicitly as the APK path does.

Build, release and release-guard jobs have no dependency gate connecting successful tests to signed artifact creation. Signed artifacts can be produced while another job fails. No GitHub publish step is present; artifacts are uploaded to Actions. The `release` environment is named, but required reviewer/environment settings cannot be verified from YAML comments. Actions use version tags, not immutable action commit pins.

## Reproducibility and supply chain

Versions are largely pinned in the catalog, but no dependency lock/verification metadata or Gradle distribution SHA-256 is present. `settings.gradle.kts:25–29` explicitly leaves dependency verification unconfigured. Repositories include Google, Maven Central, JitPack and plugin portal. Native Office archives have size/hash pins, but corresponding binary-source reproducibility was not established. No artifact SBOM, APK size measurement or startup benchmark is available.

The F-Droid recipe still points to Chethan616/ClearPDF and an inherited commit (`fdroid/com.chethan616.clearpdf.yml:5–13,31`), inconsistent with the HMXPDF application ID and organization. It is not a verified HMXPDF release recipe. Preserve upstream license attribution while correcting product-specific distribution metadata in a later authorized task.

## Compatibility risks

`app/lint-baseline.xml` contains 410 issue entries, including 46 NewApi, three Aligned16KB and three TrustAllX509TrustManager entries. This is a checked-in baseline, not a fresh lint result. Aligned16KB entries point to GPUImage 2.1.0's arm64 libyuv-decoder.so (:1182–1199). Verify actual packaged native library alignment on CI and a 16 KiB device. TLS entries point into BouncyCastle and require reachability analysis, not automatic vulnerability labeling.

Minimum API 23 conflicts with direct unguarded API-26 `android.graphics.Color.valueOf` calls in PDF overlay export (`PdfViewerViewModel.kt:1136` and sibling cases): edits can fail on API 23–25 (FA-19). Existing blur fallback guards below API 31 show compatibility handling is uneven rather than universally absent. ML Kit segmentation is explicitly API-gated and manifest overrideLibrary is used (:23–25); this requires lowest-supported-API smoke tests.

Play/FOSS currently use downloaded native Office code; privacy/distribution descriptions still promise Play Feature Delivery. Resolve architecture and policy expectations before any release, rather than assuming the unused delivery dependency implements it.

## Required release gate

CI must compile/test/lint all supported flavors; upload JUnit/lint/merged manifests and dependency inventory; build signed artifacts only after gates; inspect AAB modules, ABI contents and native alignment; compare APK/AAB signing identity; record version and source fingerprint. Device tests must cover API 23–25 overlays, current Android storage/foreground service behavior, no-GMS and low-RAM flows. No local APK/AAB build is recommended for this development device.
