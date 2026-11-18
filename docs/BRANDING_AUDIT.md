# Branding audit

Date: 2026-11-18
Repository: <https://github.com/bharti1233/HMXPDF>
Audited branch: `release/hmxpdf-2.1.1-build`
Baseline commit: `7f24bf9` (before this cleanup)

This report covers the remaining ClearPDF branding in the repository and the
changes made to remove it from user-facing material. It does not change the
licensing decision recorded in [LICENSING_AUDIT.md](LICENSING_AUDIT.md).

## 1. Method

- `git grep -il clearpdf` across every tracked file, plus a case-insensitive
  search for `hmxpanel`, `chethan616` and the old repository URLs.
- Visual inspection of every image asset in `screenshots/`, `demo/`,
  `fastlane/metadata/android/en-US/images/`, `android_icon/` and the launcher
  `mipmap-*` folders.
- Frame extraction from `demo/1.mp4`, `demo/2.mp4` and every sampled frame of
  `screenshots/pdf_scroll.gif` to check on-screen branding without guessing.
- Comparison of the launcher icon, Play icon, website icon and favicon.
- Review of store metadata, design and roadmap documents, workflow names, issue
  and pull request templates, and user-facing links in application source.

## 2. Findings

### 2.1 Visual assets

| Asset | Finding | Action |
|---|---|---|
| `screenshots/banner_gh.png` | Artwork read **ClearPDF** and linked `github.com/Chethan616/ClearPDF` | Removed; replaced by a clean HMXPDF banner |
| `screenshots/social_preview_1280x640.png` | Same ClearPDF artwork | Removed; replaced by a clean 1280×640 image |
| `screenshots/light home screen.jpg`, `dark home screen.jpg` | Title pill read **ClearPDF** | Removed; replaced by a clean home capture |
| `screenshots/light pdf tools.jpg`, `dark pdf tools.jpg` | ClearPDF-era capture set | Removed; replaced by a clean tools capture |
| `screenshots/light settings screen.jpg`, `dark settings screen.jpg` | ClearPDF-era capture set | Removed; replaced by a clean settings capture |
| `screenshots/docx editing.jpg`, `xlsx editing.jpg` | ClearPDF-era capture set | Removed; replaced by clean captures |
| `screenshots/pdf_scroll.gif` | 80 sampled frames contain no product name | Kept unchanged |
| `demo/1.mp4` | Onboarding frame reads **"Welcome to ClearPDF"** | Removed; no clean recording exists, so none was fabricated |
| `demo/2.mp4` | PDF reading and share dialog; no product name | Kept |
| `demo/3.jpg`, `4.jpg`, `5.jpg` | PDF tools screens; title pill reads "Tools" | Kept |
| `fastlane/.../featureGraphic.png` | Artwork read **ClearPDF**, "v2.0", upstream URL | Replaced with a clean HMXPDF feature graphic |
| `fastlane/.../phoneScreenshots/1.jpg` | Onboarding reads **"Welcome to ClearPDF"** | Replaced |
| `fastlane/.../phoneScreenshots/2–7.jpg` | ClearPDF-era captures (title pill / onboarding) | Replaced with clean captures; the redundant seventh file was removed |
| `fastlane/.../icon.png`, `android_icon/play_store_512.png`, launcher `mipmap-*` | Generic blue PDF glyph; no product wordmark | Kept; this is the app's actual icon |

### 2.2 Text and metadata

| Location | Finding | Action |
|---|---|---|
| `fastlane/.../title.txt` | `ClearPDF` | Changed to `HMXPDF` |
| `fastlane/.../full_description.txt` | ClearPDF name, old claims, and "the only network use is converting a web address" | Rewritten for HMXPDF with verified PDF, DOCX, XLSX, OCR, scanning and privacy statements |
| `fastlane/.../short_description.txt` | Accurate but did not mention DOCX | Updated, still within the 80-character limit |
| `DESIGN.md` | Title and title-pill example said ClearPDF | Rebranded to HMXPDF / HMX PDF |
| `PLAN.md` | Title, goal and share-sheet wording said ClearPDF | Rebranded |
| `.gitignore` | Ignored a `ClearPDF-main/` extraction folder | Changed to `HMXPDF-main/` |
| `app/.../data/repository/Preferences.kt` | `REPO_URL` opened `github.com/Chethan616/ClearPDF` from the app's star prompt | Changed to `https://github.com/bharti1233/HMXPDF`; string constant only, no logic change |
| `hmxwebsite/**` | Site files are already HMXPDF-only | No changes; the preflight check that forbids the old brand was kept |

### 2.3 Repository URLs

Current documentation uses `https://github.com/bharti1233/HMXPDF`. The old
`HMXPANEL/HMXPDF` and `Chethan616/ClearPDF` URLs remain only in historical audit
reports and in the attribution text (see section 4). The website Open Graph image
still shows `github.com/bharti1233/Hmxpdf`; GitHub URLs are case-insensitive and
the link resolves to this repository. It is a raster asset and was not rewritten,
because redrawing baked text would degrade the image.

## 3. Assets replaced

All replacements are genuine captures or official website artwork; none is a
mock-up:

- `screenshots/banner.jpg` (1200×630) — official HMXPDF Open Graph artwork.
- `screenshots/social-preview.png` (1280×640) — the same artwork, resized for
  GitHub's social preview (upload manually; GitHub has no API for it).
- `screenshots/home.jpg`, `tools.jpg`, `docx.jpg`, `xlsx.jpg`, `settings.jpg` —
  current app captures.
- `fastlane/.../featureGraphic.png` (1024×500) — the same official artwork,
  centre-cropped to the store's aspect ratio.
- `fastlane/.../phoneScreenshots/1–6.jpg` — five current app captures plus one
  clean tools capture from `demo/5.jpg`.
- `screenshots/README.md` and `demo/README.md` record provenance and removals.

Removed from the current tree (still available in Git history):
`banner_gh.png`, `social_preview_1280x640.png`, the eight old JPG captures,
`demo/1.mp4`, and `fastlane/.../phoneScreenshots/7.jpg`.

## 4. Intentionally preserved

| Reference | Reason |
|---|---|
| `LICENSE` — MIT, `Copyright (c) 2026 Chethan616` | Legal requirement; the controlling grant for the inherited code |
| `NOTICE` | Upstream derivation and third-party attribution; retained |
| `THIRD_PARTY_NOTICES.md` | Apache-2.0, MIT, BSD, OFL and MPL obligations; retained |
| `Modified by ClearPDF` source headers | Apache-2.0 modification records; renaming them would misstate the modification history |
| `README.md` attribution sentence | Names ClearPDF/Chethan616 as the upstream project |
| `README.md` `CLEARPDF_UPLOAD_*` | Names of existing CI secrets; renaming them would break the release job |
| `app/build.gradle.kts`, `key.properties.example` — `CLEARPDF_UPLOAD_*` | Same signing-variable contract |
| `.github/workflows/android.yml` — `CLEARPDF_UPLOAD_*` | Same signing-variable contract |
| `clearpdf_*` SharedPreferences keys | Persisted user settings and recent-document state; renaming would lose user data |
| `XlsxWriter` `rIdClearPdfStyles` | Internal OOXML relationship identifier, not user-visible |
| `app/lint-baseline.xml` | Stale generated baseline entry for an old string; regenerated by lint, not user-facing |
| `fdroid/com.chethan616.clearpdf.yml` | Upstream F-Droid package identity; not published, and renaming it is a distribution decision |
| `hmxwebsite/README.md`, `hmxwebsite/scripts/preflight.sh` | The strings are part of a check that forbids the old brand on the site |
| `docs/**` historical reports and this audit | Dated engineering records; not rewritten |
| App icon and launcher assets | Generic PDF glyph, no ClearPDF wordmark; this is the actual app logo |

## 5. Not replaced

- **`demo/1.mp4`** — the only walkthrough that showed the old onboarding. No clean
  recording exists in the repository, and fabricating one would misrepresent the
  app. It was removed and documented instead.
- **Website Open Graph image URL casing** — see section 2.3; the link works.
- **F-Droid metadata** — see section 4; it is upstream distribution metadata, not
  a published asset.
- **Git history** — earlier commits and removed files remain in history by design.

## 6. Verification

- No `ClearPDF` remains in `CONTRIBUTING`, `SECURITY`, `PRIVACY`, `CHANGELOG`,
  `DESIGN`, `PLAN`, store metadata, issue templates, pull request template, or
  current operational docs.
- `README.md` contains exactly two references: the inherited
  `CLEARPDF_UPLOAD_*` secret names and the upstream attribution sentence.
- Every other remaining occurrence is either listed in section 4 or documents a
  removal in this audit or in the `screenshots/` and `demo/` README files.
- Replaced images were opened and checked for legibility and absence of the old
  product name.
- The only application source change is the `REPO_URL` string; package name,
  version, signing configuration, and functional code are unchanged.
