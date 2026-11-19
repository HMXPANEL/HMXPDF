# HMXPDF v2.1.1 release quality report

Release: https://github.com/HMXPANEL/HMXPDF/releases/tag/v2.1.1
Repository: https://github.com/HMXPANEL/HMXPDF
Primary branch: `release/hmxpdf-2.1.1-build`
Release tag: `v2.1.1`
Tag commit: `646c0c35547e3e905e9a7763e48c1329d13e008a`
GitHub Actions run: https://github.com/HMXPANEL/HMXPDF/actions/runs/44448335798

## A. Release preflight

| Check | Result |
| --- | --- |
| Branch | `release/hmxpdf-2.1.1-build` |
| Working tree | clean at report time |
| Version name / code | `2.1.1` / `6` |
| Application ID | `com.hmx.pdf` |
| minSdk / targetSdk / compileSdk | 23 / 36 / 36 |
| Existing tag `v2.1.1` | present, pointing at the release commit |
| Existing release | present, published, non-draft, non-prerelease |
| Release guard | present and successful; four unsigned probes reported `EXPECTED BLOCKED` with Gradle exit 1 and produced no APK/AAB |

## B. Automated quality checks

| Check | Result |
| --- | --- |
| JVM unit tests (`testPlayDebugUnitTest`, `:pdf-core:test`, `:ocr-core:test`) | passed |
| Android lint (`lintPlayDebug`) | passed |
| Debug APK build | passed |
| Signed release APK build (`assemblePlayRelease`) | passed |
| Signed release bundle build (`bundlePlayRelease`) | passed |
| APK signature verification | passed for all five APKs |
| AAB signature verification | passed, not debug-signed |
| Release guard | passed |
| PDF module | `:pdf-core` included in `settings.gradle.kts` and depended on by `:app` |
| DOCX module | DOCX sources present in `:app`; release APK contains the assembled app DEX |
| OCR module | `:ocr-core` included and depended on by `:app` |

No test failures were ignored. No unrelated application code was changed to force a passing build.

## C. Security

| Check | Result |
| --- | --- |
| Tracked credential scan | no GitHub tokens, PATs, AWS keys or private keys found |
| Tracked keystore/secret files | none |
| Release APK `debuggable` attribute | absent (default false) in all five APKs |
| Debug-only build configuration | no `debuggable`/`debugImplementation` in the release path |
| Exported components | two exported activities; provider and services are not exported |
| Permissions | camera, scoped storage, notifications, foreground service/data sync; FOSS adds internet and network state |
| Secret exposure | no secret values printed in logs or this report |
| Release assets | no keystore, password, AAB or debug APK uploaded |

## D. Artifact quality

| APK | Size (bytes) | ABI | SHA-256 |
| --- | ---: | --- | --- |
| `app-play-arm64-v8a-release.apk` | 34,564,792 | arm64-v8a | `a01d331d5cfa896c9ba771d324f981cdbb759e47f139c0c2f6479bef44d2df30` |
| `app-play-armeabi-v7a-release.apk` | 28,260,955 | armeabi-v7a | `5f844bdfb6567a86b727e2c2b46fa188b3886d49e1b06a2b2d06e0b3a5cfcd28` |
| `app-play-universal-release.apk` | 86,211,774 | arm64-v8a, armeabi-v7a, x86, x86_64 | `cdd88c7234a10377c64c7794ad1e9a8070a57298211d86ba9c1cd3c532ae1169` |
| `app-play-x86-release.apk` | 35,363,134 | x86 | `e9e2f0bbab3f451594ff8c1a85ca32dabc114032f405db76c65079ce3508115e` |
| `app-play-x86_64-release.apk` | 35,580,829 | x86_64 | `14b7e188e7c0b76831e47fdd7a48953995e4055a4d0d39c013fcbbdae18f19e0` |

- CI APK artifact archive integrity: `unzip -t` passed for all five APKs.
- Artifact archive SHA-256: `f3a6c36b14520f4256e8884e9546c278b7783e9d1f85b8f1a49f2458faee9697`.
- Every file is an installable Android package (APK), not an AAB renamed as APK.
- Package, version name, version code, minSdk and targetSdk verified with `aapt dump badging`.
- Signing verified with `apksigner verify --verbose --print-certs`; APK Signature Scheme v2 valid, one signer, not debug-signed.

## E. Build provenance

| Field | Value |
| --- | --- |
| Repository | `HMXPANEL/HMXPDF` |
| Branch | `release/hmxpdf-2.1.1-build` |
| Commit SHA | `646c0c35547e3e905e9a7763e48c1329d13e008a` |
| GitHub Actions run ID | `44448335798` |
| Build timestamp (UTC) | 2026-11-19T03:00:24Z – 2026-11-19T03:10:18Z |
| JDK | Temurin 17 |
| Gradle | 9.3.0 |
| Android Gradle Plugin | 9.0.0 |
| Kotlin | 2.3.0 |
| Signing certificate SHA-256 | `e30ab37d83b52a0fd8a15da51a0d323107043daa9ac773fa44f17968348614d3` |
| Signing certificate DN | `CN=Harsh maurya, OU=HMXPANEL, O=HMXPANEL, L=Mumbai, ST=Maharashtra, C=IN` |

No secrets or private key material are included in this report.

## F. Published asset verification

Each APK was downloaded again from the published release through GitHub's binary asset API and compared with the validated build artifact. All five matched.

| Asset | Published size | Published digest matches local |
| --- | ---: | --- |
| `app-play-arm64-v8a-release.apk` | 34,564,792 | yes |
| `app-play-armeabi-v7a-release.apk` | 28,260,955 | yes |
| `app-play-universal-release.apk` | 86,211,774 | yes |
| `app-play-x86-release.apk` | 35,363,134 | yes |
| `app-play-x86_64-release.apk` | 35,580,829 | yes |
| `HMXPDF-v2.1.1-APK-SHA256SUMS.txt` | 757 | yes |

Direct download URLs return HTTP 200/206. The release contains no APK ZIP and no AAB asset.

## G. Website verification

- Repository source: `hmxwebsite/assets/js/release.js` points to `https://api.github.com/repos/HMXPANEL/HMXPDF/releases/latest`.
- The new release API returns `v2.1.1` with the five APK assets and checksum manifest.
- The Download page keeps **Download APK** disabled and **Download from GitHub** pointing to `https://github.com/HMXPANEL/HMXPDF/releases`.
- Production site: `https://hmxpdf-website.vercel.app` is unchanged. At report time it still serves the previous repository's release feed, so the live website does not yet display this release. The Vercel Git integration has not been reconnected. No Vercel project, domain or deployment configuration was changed.

## H. Remaining issues

1. **Vercel production cutover is pending.** The existing `hmxpdf-website` project still deploys from the previous repository. Reconnect it to `HMXPANEL/HMXPDF` with root `hmxwebsite` and production branch `release/hmxpdf-2.1.1-build`, then verify a Git-triggered production deployment. The production domain must remain `https://hmxpdf-website.vercel.app`.
2. **Custom repository social preview** is prepared at `screenshots/social-preview.png` but must still be uploaded in GitHub repository settings.
3. The checksum manifest contains descriptive header lines, so `sha256sum -c` reports them as improperly formatted while still verifying all five APK lines successfully.
