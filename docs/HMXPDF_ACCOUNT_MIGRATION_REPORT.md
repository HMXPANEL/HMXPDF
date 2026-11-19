# HMXPDF account migration

## Source and snapshot

- Destination: https://github.com/HMXPANEL/HMXPDF
- Historical source: `bharti1233/HMXPDF`, primary commit `15ee95e67275f4a032b887a35f2746154fbe1579`.
- The initial commit exports the tracked primary-branch tree and intentionally applies repository identity, website download, and CI reporting updates. No source Git history, local files, or credentials are imported. The pending guard fix was applied as a patch, not imported as a commit.
- Both branches are intended to start at the single root commit containing this report. Resolve its hash with `git rev-list --max-parents=0 origin/release/hmxpdf-2.1.1-build`. The primary branch is the default; `main` is backup-only and is updated only on request.
- The supplied logo and its existing Android, website, and store variants are retained. The promotional artwork now identifies the destination. Actual screenshots are unchanged.
- Old owner references in archived audits, phase reports and this report identify historical provenance, not active endpoints. `LICENSE`, `NOTICE`, and `THIRD_PARTY_NOTICES.md` are preserved verbatim. HMXPANEL maintains the project; upstream ownership and inherited license obligations are unchanged.

## Release provenance and signing

The destination release is published at https://github.com/HMXPANEL/HMXPDF/releases/tag/v2.1.1. It targets `646c0c35547e3e905e9a7763e48c1329d13e008a`, was produced by tag-triggered Actions run `44448335798`, and contains five individual APKs plus `HMXPDF-v2.1.1-APK-SHA256SUMS.txt`. The draft assets were downloaded again through GitHub's binary asset API and matched the checksums below. The historical source ZIP is not attached.

The original `v2.1.1` release came from historical commit `1a069bbb0b68268de33e4da834dffd7e7cd6d7dc`, Actions run `44343854184`. Its five APKs matched the original CI artifact byte for byte. Each verified as package `com.hmx.pdf`, version `2.1.1`, versionCode `6`, and carried the same verified release certificate. These are **historical** checksums, not destination release checksums:

| APK | SHA-256 |
| --- | --- |
| `app-play-arm64-v8a-release.apk` | `a545041593e90cdd0fb5cb23e09f5199db3b2456577383a14aadbcd7829af47b` |
| `app-play-armeabi-v7a-release.apk` | `ce1512d9d139d6e5386493eee52ffe76b216607094774e2033011bde54b96be0` |
| `app-play-x86-release.apk` | `18382d4e5481aefa8187cc7b97165bfe22ab107453ef346d0b12f80adffcdf6e` |
| `app-play-x86_64-release.apk` | `eaa4a05ddf79b4e25bf3d1785775005740fa7484db7fad3238f6ac3053d4904d` |
| `app-play-universal-release.apk` | `37088d629f7c3bf19837229a466468a9c0a1ac469e4b30804c53517aa3902e29` |

The destination APKs are the signed CI build of the actual tagged snapshot. They retain the package/version and existing signing certificate; the fresh source snapshot also contains the migrated repository identity and current launcher resources.

The destination `release` environment was configured with these existing signing credentials:

| Secret | Purpose |
| --- | --- |
| `CLEARPDF_UPLOAD_STORE_BASE64` | Encoded existing keystore |
| `CLEARPDF_UPLOAD_STORE_PASSWORD` | Keystore password |
| `CLEARPDF_UPLOAD_KEY_ALIAS` | Existing signing key alias |
| `CLEARPDF_UPLOAD_KEY_PASSWORD` | Signing key password |

No signing variables are required. The release guard deliberately tests four unsigned dry runs. `EXPECTED BLOCKED` is a passing assertion; unexpected acceptance, other failures, or APK/AAB output fails the guard.

## Website cutover

- Existing project: `hmxpdf-website`; production URL stays https://hmxpdf-website.vercel.app.
- Reconnect the existing project to `HMXPANEL/HMXPDF` after granting the Vercel GitHub app access. Use `hmxwebsite`, preset Other, no build or install command, output `.`, and production branch `release/hmxpdf-2.1.1-build`.
- The destination site uses the destination GitHub API and keeps Download APK disabled. The GitHub button opens the official Releases page. Production cutover remains pending until Vercel Git authorization is granted and a Git-triggered production deployment is verified.
- Upload `screenshots/social-preview.png` in the repository's General settings for a custom social preview.
