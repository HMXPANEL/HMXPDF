# Security Policy

## Supported versions

Security fixes are developed on the default branch, `release/hmxpdf-2.1.1-build`.
Report issues affecting the official GitHub release or current development branch.
Local builds and CI debug artifacts are development snapshots.

| Version | Supported |
|---|---|
| `release/hmxpdf-2.1.1-build` (development) | Fixes accepted |
| [Published releases](https://github.com/HMXPANEL/HMXPDF/releases) | Reports accepted |
| Locally built or CI debug APKs | Not supported |

## Report a vulnerability privately

**Do not open a public issue, discussion, or pull request containing vulnerability
details.**

Use GitHub's **private vulnerability reporting** form if available:

<https://github.com/HMXPANEL/HMXPDF/security/advisories/new>

GitHub delivers the report privately to the repository maintainers. If the form is
unavailable to you, open a public issue that says only that you need a private
security contact, without describing the vulnerability, and a maintainer will
follow up.

Do not send secrets, signing keys, private documents, personal data, or access
tokens in any report. If a proof of concept needs a document, generate a minimal
synthetic file.

## What to include

A useful report contains:

- A short description of the issue and the affected component (for example, PDF
  parsing, DOCX import, the optional Office engine download, or a file export path).
- The affected version, commit, or build, and the distribution (source build,
  FOSS, or Play).
- The impact: what an attacker could read, change, or execute, and under what
  preconditions.
- Reproduction steps or a minimal proof of concept.
- Device and Android version, if the issue is device-specific.
- Any suggested fix or mitigation, if you have one.

## Disclosure expectations

- Please allow reasonable time to confirm the report, prepare a fix, and publish a
  build before public disclosure.
- Maintainers will acknowledge a complete report and keep you informed of progress
  where practical.
- Credit can be included in the advisory or changelog if you want it; say so in the
  report.
- Please do not test against other people's devices, documents, or accounts, and do
  not run denial-of-service tests against third-party services.

## Scope

In scope:

- The Android application code in this repository.
- The `pdf-core`, `ocr-core`, and `backdrop` modules.
- Parser and writer handling of untrusted PDF, DOCX, XLSX, and image files.
- The optional Office engine download and integrity checks.
- Release signing and CI configuration in `.github/workflows/`.

Out of scope:

- Vulnerabilities in third-party libraries that are already publicly known and
  reported upstream, unless this project's use makes the impact materially worse.
- Issues that require a rooted device, a modified APK, or physical access to an
  unlocked device.
- Missing hardening headers on the separate static website; report those to the
  website maintainers instead.
- Social engineering and spam.

## Privacy

HMXPDF processes selected documents on the device and does not provide a
document-upload service. The FOSS build makes network requests only for the
optional Office engine download and for converting a web address you enter. See
[PRIVACY.md](PRIVACY.md) for the complete description.
