# Documentation audit

Date: 2026-11-18
Repository: <https://github.com/bharti1233/HMXPDF>
Audited branch: `release/hmxpdf-2.1.1-build`
Audited commit: `28f6cfae68a770c17a748312083f3c78966eaf82`

This report records what existed before the documentation overhaul, what was
wrong, and what was changed. It does not describe application functionality that
was not verified in source, build files, or existing reports.

## 1. Repository facts

| Item | Verified value |
|---|---|
| Default branch (`HEAD`) | `release/hmxpdf-2.1.1-build` |
| Other branches | `main` (behind the integration branch), two dated backup branches |
| Commits on the audited branch | 96 |
| Tags | none |
| GitHub Releases | none (API returns an empty list) |
| License detected by GitHub | MIT (`LICENSE`, copyright 2026 Chethan616) |
| Application ID | `com.hmx.pdf` |
| `versionName` / `versionCode` | 2.1.1 / 6 |
| `minSdk` / `targetSdk` | 23 / 36 |
| Build stack | AGP 9.0.0, Kotlin 2.3.0, Compose 1.10.1, JDK 17 |
| Modules | `app`, `pdf-core`, `ocr-core`, `backdrop`, `office_engine` |
| CI | `.github/workflows/android.yml`, green on the audited branch |
| Private vulnerability reporting | enabled |
| Secret scanning / push protection | enabled |
| Homepage in repository settings | `https://hmxpdf-website.vercel.app` |
| Description and topics | empty |

## 2. Existing documentation inventory

| File | State found |
|---|---|
| `readme.md` (lowercase) | Inherited HMX PDF README written for `HMXPANEL/HMXPDF`; all links and badges point to that organization; claims a published release and an Obtainium listing; feature text predates Phase 4 DOCX work. |
| `CONTRIBUTING.md` | Written for ClearPDF; clone URL points to `Chethan616/ClearPDF`. |
| `SECURITY.md` | Written for ClearPDF; reporting link points to `Chethan616/ClearPDF/security/advisories/new`. |
| `CODE_OF_CONDUCT.md` | Short custom text titled ClearPDF, attributed to Contributor Covenant 2.1. |
| `PRIVACY.md` | Titled ClearPDF; technically accurate for the app's network behaviour. |
| `LICENSE` | MIT, copyright 2026 Chethan616. Byte-identical to `HMXPANEL/HMXPDF`. |
| `NOTICE` | Titled "ClearPDF NOTICE"; byte-identical to `HMXPANEL/HMXPDF`. |
| `THIRD_PARTY_NOTICES.md` | Detailed and largely accurate; every product reference says ClearPDF. |
| `.github/ISSUE_TEMPLATE/bug_report.yml` | ClearPDF wording; points at ClearPDF release page. |
| `.github/ISSUE_TEMPLATE/feature_request.yml` | Generic; no product branding problems. |
| `.github/ISSUE_TEMPLATE/config.yml` | Security contact URL points to `Chethan616/ClearPDF`. |
| `.github/pull_request_template.md` | ClearPDF-era checklist; build command is correct for the FOSS flavor. |
| `.github/FUNDING.yml` | `github: Chethan616` — funds the upstream author, not this repository. |
| `DESIGN.md`, `PLAN.md` | Project design and planning notes; no user-facing claims. |
| `docs/` | 59 Markdown files: 14 fresh-audit reports, 31 phase reports, 1 release report, plus ad-hoc reports. Internal engineering logs, not an entry point for contributors. |
| `screenshots/` | 11 files including `banner_gh.png`, whose artwork says **ClearPDF** and links `github.com/Chethan616/ClearPDF`. |
| `demo/` | 2 MP4 walkthroughs and 3 images from the ClearPDF era. |
| `fdroid/com.chethan616.clearpdf.yml` | F-Droid metadata using the upstream package name; not published. |

## 3. Findings

### Incorrect or misleading

- **D-01 — README points at a different repository.** Every download, issue,
  Actions, and stars link targets `HMXPANEL/HMXPDF`. Seven Markdown files
  reference that organization.
- **D-02 — README claims a release that does not exist.** It says the current
  stable release is "V5.1 (app version 2.1.1)" and shows release, download, and
  Obtainium links, while this repository has zero releases and zero tags. This is
  the most serious accuracy problem for users.
- **D-03 — Inherited ClearPDF branding in public documents.** Eleven Markdown
  files mention ClearPDF as the product name, including the security policy,
  contribution guide, issue templates, notices, and privacy page.
- **D-04 — Security and issue links point at the upstream project.** Users would
  be sent to `Chethan616/ClearPDF` instead of this repository's private reporting.
- **D-05 — FUNDING.yml funds a third party.** Not wrong legally, but misleading in
  this repository's sidebar.
- **D-06 — CI description is inaccurate.** The README says the workflow runs on
  pushes to `main`; the workflow actually triggers on `main`,
  `release/hmxpdf-2.1.1-build`, `v*` tags, pull requests, and manual dispatch.
- **D-07 — Feature text is out of date.** The README says Word and PowerPoint are
  view-only and implies no DOCX editing. Phase 4 added DOCX tables, footnotes,
  comments, find and replace, spell check, read aloud, and autosave, all present
  in source and CI-verified reports.
- **D-08 — The feature inventory contradicts the code.** 
  `docs/fresh-audit/HMXPDF_COMPLETE_FEATURE_INVENTORY.md` marks autosave, advanced
  tables, spell check, footnotes, and read aloud as "not implemented" (F55–F59),
  which was true when written but is now wrong.

### Outdated assets

- **D-09 — Screenshots show the previous product name.** The home screen, banner,
  and social preview contain "ClearPDF" and an upstream URL. The README used them
  directly. Clean screenshots exist in the deployed website and were imported for
  the new README.

### Structure and duplication

- **D-10 — No entry point for the docs folder.** 59 reports are unindexed, mixing
  historical audits with current reference material.
- **D-11 — Duplicate report names.** `PHASE1_FINAL_REPORT.md` and
  `PHASE1_SOURCE_AUDIT.md` each exist twice in different phase folders, and
  `docs/AUDIT_REPORT.md` overlaps `docs/fresh-audit/`.
- **D-12 — Repository file name case.** `readme.md` is lowercase; GitHub renders
  it, but `README.md` is the conventional, portable name.
- **D-13 — No changelog.** There is no user-facing history even though 96 commits
  exist.

### Missing documents

- `CHANGELOG.md`.
- A statement of release status that matches GitHub Releases.
- A documented branch and pull-request workflow.
- A documented test and lint matrix that matches CI.
- A licensing audit explaining the inherited MIT notice and upstream chain.

## 4. Changes applied

| File | Change |
|---|---|
| `README.md` | New document; replaces `readme.md`. Correct links, accurate release status, verified features including Phase 4, clean screenshots, architecture, build and test matrix, privacy, contributing, attribution. |
| `readme.md` | Removed (superseded by `README.md`). |
| `CONTRIBUTING.md` | Rewritten for this repository: branching, flavors, tests, Kotlin and Compose conventions, documentation duties, review expectations. |
| `SECURITY.md` | Rewritten: real private-reporting link, supported-version truth, report contents, disclosure and scope. |
| `CODE_OF_CONDUCT.md` | Full Contributor Covenant 2.1 with attribution; enforcement through GitHub private reporting because no conduct email exists. |
| `PRIVACY.md` | Rebranded and re-verified against manifests and the Office engine manifest. |
| `CHANGELOG.md` | New; derived from the 96-commit history with commit references. |
| `NOTICE` | Added a derivation statement above the preserved upstream notice. |
| `THIRD_PARTY_NOTICES.md` | Product references updated; all licenses, versions, and obligations preserved. |
| `.github/ISSUE_TEMPLATE/*.yml` | Rebranded, correct repository links, version placeholder updated. |
| `.github/pull_request_template.md` | Rebranded; keeps the real build command and adds test and docs checks. |
| `.github/FUNDING.yml` | Removed; it pointed at the upstream author. |
| `docs/DOCUMENTATION_AUDIT.md` | This report. |
| `docs/LICENSING_AUDIT.md` | Licensing evidence and decision. |
| `docs/media/screenshots/*.jpg` | Clean application screenshots used by the README. |

## 5. Preserved

- All Android source, resources, Gradle configuration, signing setup, workflows,
  version numbers, and package names.
- All historical reports under `docs/`, including the outdated feature inventory,
  which is treated as a dated snapshot rather than current truth.
- The existing license text and copyright notice, unchanged.
- Upstream and third-party attribution.

## 6. Remaining recommendations (not applied)

> **Follow-up (2026-11-18):** recommendation 1 was completed in
> [BRANDING_AUDIT.md](BRANDING_AUDIT.md); the branded screenshots, banner, social
> preview, and the ClearPDF onboarding video were replaced or removed.

1. Remove or regenerate the ClearPDF-branded `screenshots/banner_gh.png`,
   `screenshots/social_preview_1280x640.png`, `screenshots/*.jpg`, and `demo/`
   media once replacement captures exist.
2. Add a short note at the top of
   `docs/fresh-audit/HMXPDF_COMPLETE_FEATURE_INVENTORY.md` stating that F55–F59
   were implemented in Phase 4, or move the file to an `archive/` folder.
3. Consolidate duplicate phase reports after the project leaves 2.1.1 development.
4. Rename `fdroid/com.chethan616.clearpdf.yml` only if an F-Droid submission is
   planned; the package name is owned by the upstream project.
5. Publish a first GitHub Release when a signed APK is ready, then update the
   README download section and add release links.
