# Licensing audit

Date: 2026-11-18
Repository: <https://github.com/bharti1233/HMXPDF>
Branch: `release/hmxpdf-2.1.1-build`

This audit records the licensing evidence found in the repository, the upstream
chain it implies, and the decision made. **No license text was changed.**

## 1. Evidence found in this repository

| File | Evidence |
|---|---|
| `LICENSE` | MIT License, `Copyright (c) 2026 Chethan616`. SHA-256 `a03f3574b14dc58163947d66e2b82719cbc89888f87936dbe69ec7a47c392f17`. |
| `NOTICE` | Titled "ClearPDF NOTICE"; attributes AndroidLiquidGlass (Apache-2.0), PdfBox-Android (Apache-2.0), Google ML Kit, ImageToolbox (Apache-2.0), GPUImage (Apache-2.0), and states "ClearPDF remains licensed under the MIT License". SHA-256 `cde9c9e0946a7daf699d3427bc2be131c649ac98efcaba2bdfebdc6bf22fb497`. |
| `THIRD_PARTY_NOTICES.md` | Per-component licenses, versions, notice practices, and source-availability notes. |
| `app/**` adapted files | Apache-2.0 headers retained with a "Modified by ClearPDF" line, as described in `NOTICE`. |
| Git history | First commit `e488aa2` (2026-10-30) "migrate HMXPDF with audited fixes and Office Engine reliability improvements". No license change commits. |

`LICENSE` and `NOTICE` are **byte-identical** to the same files on
`HMXPANEL/HMXPDF@main`, the repository the initial README was copied from:

```text
a03f3574...  LICENSE  (this repository)
a03f3574...  LICENSE  (HMXPANEL/HMXPDF main)
cde9c9e0...  NOTICE   (this repository)
cde9c9e0...  NOTICE   (HMXPANEL/HMXPDF main)
```

## 2. Upstream chain

Observed through the GitHub API and raw files on 2026-11-18:

1. **Chethan616/ClearPDF** now redirects to **Chethan616/Artefact**. Artefact's
   README says "Previously known as ClearPDF".
2. Artefact's `NOTICE` states that Artefact is a modified version of **PdfCraft for
   Android** (<https://github.com/artcraft-android/pdfcraft-android>) by the ArtCraft
   Team, built on the **PdfCraft** engine (<https://github.com/storytold/pdfcraft>)
   by storytold and contributors.
3. Artefact currently ships `LICENSE-MIT` (`Copyright (c) 2026 ArtCraft Team and
   the PdfCraft contributors`) and `LICENSE-APACHE`; GitHub reports Apache-2.0.
4. This repository's inherited `LICENSE` records an **MIT** grant from
   `Copyright (c) 2026 Chethan616`, and its `NOTICE`/source headers follow the
   ClearPDF-era notices.

Conclusion: this code was obtained under the MIT grant recorded in `LICENSE`, from
the ClearPDF/HMXPDF lineage, before upstream's later dual MIT/Apache-2.0
packaging. That MIT grant travels with the copies already distributed under it.

## 3. Decision

**Preserve the existing MIT license and copyright notice. Do not relicense.**

Reasons:

- The project owner is distributing code whose controlling notice is MIT with
  copyright held by Chethan616. Replacing it with a different license, or removing
  the notice, would breach the MIT condition that the copyright and permission
  notice be included in all copies or substantial portions.
- Adding an MIT license for *new* contributions is possible in principle, but it
  would create a mixed-ownership file that misrepresents the inherited code unless
  the upstream grant is confirmed. That is a legal decision for the owner, not a
  documentation task.
- No evidence in this repository supports relicensing, and the task instructions
  explicitly prohibit an automatic MIT replacement.

What was done instead:

- `LICENSE` is unchanged.
- `NOTICE` keeps every inherited line and gains a short preamble naming HMXPDF and
  restating the derivation.
- `README.md` adds an acknowledgement section naming ClearPDF (Chethan616),
  PdfCraft for Android (ArtCraft Team), and PdfCraft (storytold and contributors).
- `THIRD_PARTY_NOTICES.md` keeps every license, version, and obligation; only the
  product name in prose was updated.

## 4. Obligations that remain in force

| Component / license | Obligation | Where satisfied |
|---|---|---|
| MIT (inherited app code) | Include the copyright notice and permission text in copies and substantial portions. | `LICENSE`, retained verbatim |
| Apache-2.0 (PdfBox-Android, AndroidLiquidGlass, ImageToolbox, GPUImage, Apache POI, Tesseract4Android, docx-preview) | Retain notices, state modifications, do not imply endorsement. | `THIRD_PARTY_NOTICES.md`, `NOTICE`, source headers |
| MIT (AndroidLiquidGlassView, JSZip, Gander) | Retain copyright and permission notice. | `THIRD_PARTY_NOTICES.md` (Gander text included in full) |
| BSD-2-Clause (Leptonica via Tesseract) | Retain notice. | `THIRD_PARTY_NOTICES.md` |
| SIL OFL 1.1 (Carlito; fonts inside LibreOffice) | Keep the license with the font and do not use reserved names. | `THIRD_PARTY_NOTICES.md`, font directory |
| MPL-2.0 (optional LibreOffice engine) | If the engine is redistributed, make corresponding source available and keep notices. The engine is not bundled in the APK. | `THIRD_PARTY_NOTICES.md` §LibreOffice |
| Google ML Kit terms | Comply with Google's terms; no endorsement claim. | `THIRD_PARTY_NOTICES.md` |
| Trademarks | Descriptive use only: "LibreOffice" (The Document Foundation), "Word/Excel/PowerPoint" (Microsoft), "Android" (Google). | `THIRD_PARTY_NOTICES.md`, README |

## 5. Risks and open questions

1. **Upstream relicensing.** If the owner wants to move this repository to
   Apache-2.0 or a dual license, they must confirm the grant with the upstream
   copyright holder first. This audit does not authorize that change.
2. **`Modified by ClearPDF` headers.** Adapted Apache-2.0 files keep the original
   modification marker. Renaming that string would misrepresent the modification
   history; it was left untouched.
3. **F-Droid metadata.** `fdroid/com.chethan616.clearpdf.yml` uses the upstream
   package identity. It is not published and was not modified, because it is
   distribution configuration rather than documentation.
4. **CI secret names.** `CLEARPDF_UPLOAD_*` names are inherited from the workflow.
   Renaming them would break the release job and touches signing configuration, so
   they were left unchanged and documented instead.

## 6. Verification performed

- `LICENSE` and `NOTICE` compared byte-for-byte with `HMXPANEL/HMXPDF@main`.
- All Markdown files checked for license statements; no file claims a license
  different from MIT.
- No new dependency, asset, or adapted source was introduced by this change.
- No APK, keystore, or signing file was added or modified.
