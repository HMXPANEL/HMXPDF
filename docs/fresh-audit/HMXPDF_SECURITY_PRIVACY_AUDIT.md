# HMXPDF security and privacy audit

Source review only. No exploit execution, network traffic capture, credential use or backend access occurred. No confirmed P0 exploit is claimed. `A/` = `app/src/main/java/com/hmx/pdf/`.

## Trust boundaries and permissions

The main manifest requests camera, legacy read storage through API 32, legacy write through API 28, notifications and foreground data-sync services (`app/src/main/AndroidManifest.xml:6–21`). SAF/MediaStore are the principal storage APIs; verify whether legacy read permission is still necessary for supported provider flows. No MANAGE_EXTERNAL_STORAGE permission is declared.

MainActivity and DocumentActivity are exported as launcher/document entry points (:59,156). External MIME/URI inputs are untrusted; MainActivity's supported-extension heuristic uses substring matching (:164–178), so it is routing convenience, not content validation. PDF links only open http/https/mailto/tel (`PdfViewerScreen.kt:1030–1047`). FileProvider and Office service are non-exported (:178–202), and URI grants are explicit. No authentication/admin endpoint is required by the current local app architecture.

Provider coverage is functionally broken (FA-01): only cache/scan_images, cache/shared and external-files `.` are mapped. Decrypted PDFs, image exports, raster pages and patched Office files are outside these roots. Do not fix this by exposing every private recovery file. Stage explicit share copies in a narrow root, use internal file access for internal processing, and remove file-URI fallback (`SpreadsheetScrolling.kt:280–291`). External-files `.` is broad, but non-exported provider plus specific grants is not evidence of unrestricted remote reading.

## Data storage and backup

| Data | Location / source | Protection and gap |
|---|---|---|
| Save journal, original backup, prepared Office file | noBackupFilesDir/save_recovery; SaveRecovery.kt:71 | Durable private data excluded by platform noBackup; no app-level encryption, incomplete package validation |
| PDF Save Copy backup | noBackupFilesDir/save_copy_recovery; PdfViewerScreen.kt:234 | Failure backup exists; no durable URI journal or next-launch recovery UI for these `.bak` files |
| Local mirrors | external app files/Documents/mirrored, internal fallback; LocalDocumentMirror.kt:52 | Controlled by preference, clear/delete paths exist; backup rules do not exclude mirror locations |
| Signatures | filesDir/signatures; SignatureManager.kt:16 | Sensitive handwriting PNGs; not excluded from configured backup |
| Recents/progress | SharedPreferences; Preferences.kt:41 | Names/URIs/history retained locally and eligible for backup unless excluded |
| OCR text | cache/ocr_cache; OcrPageCache.kt:26 | Plaintext derived content, no explicit age/quota cleanup |
| Decrypted PDF | cache/decrypted_pdfs; PdfSecurityService.kt:118 | Session cleanup and 24-hour stale threshold, no password persistence |
| Office input/output/tmp | cache/office-in, office-pdf, office-tmp | Input finally cleanup; cache policy limited; password output ownership incomplete |

`allowBackup=true` and backup/data-extraction rules omit mirrored documents and signatures. Cache exclusions use `domain="file" path="cache/..."`, which is not the actual cache directory; Android cache is excluded by platform policy anyway. Actual sensitive files outside cache need an explicit policy (FA-12). Android app-private storage and OS device encryption are not application-managed cryptographic protection. Behavior of cloud/device-transfer backups remains device/provider dependent.

## Network and document privacy

Known outbound paths are the pinned Office engine download, explicit URL-to-PDF browsing, user-opened links/share/process-text intents, and Google service/model provisioning. No first-party document upload endpoint, analytics SDK, crash reporting SDK, account backend, remote config client or secret-bearing service client was found in application sources/dependencies. This is bounded source evidence, not a guarantee about closed SDK internals.

Local HTML disables JavaScript and uses OfflineWebContent; DOCX enables bundled JavaScript but blocks network and arbitrary content requests (`A/util/OfflineWebContent.kt:18–28`; `utils/DocxWebRenderer.kt:116–171`). URL conversion enables JavaScript and DOM storage and intentionally loads remote resources (`HtmlToPdfConverter.kt:37`). Main manifest disables Safe Browsing lookups globally (:206), affecting remote URL flow too. No custom TLS-bypass implementation was found in first-party networking. Lint's TrustAll findings point into BouncyCastle dependency jars, not an observed network call using that trust manager; reachability must be established before calling this an exploitable TLS bypass.

PPTX DOM parsing sets namespace awareness but does not explicitly disable DTD/external entities (`office/PptxPlaceholderFix.kt:168–170`). This is an inferred parser-hardening risk dependent on Android parser behavior, not a confirmed XXE exploit. ZIP readers lack comprehensive inflated-size/depth/count limits (FA-16). Treat externally supplied documents as hostile inputs even when no network is intended.

## Integrity, logs and policy accuracy

SaveRecovery verifies destination hashes but validates Office packages only by part presence/nonzero size (:247–272). It does not prove XML well-formedness, relationships or preserved content. HF root rewriting can introduce unbound prefixes (FA-03). PDF text-cover fallback retains original content in some cases; it must not be described as secure redaction (`PdfTextEditor.kt:152`).

First-party logs generally record errors and generated file identifiers, not deliberate full-document dumps. Exceptions may include paths or document-derived messages (`OfficeEngineClient.kt:111`, `DocxPdfWriter.kt:59`), and native stdio is redirected (`OfficeEngineRuntime.kt:39`). Audit actual native logs on device and sanitize messages before any future diagnostics export. No blanket “never logs sensitive data” claim is justified.

`PRIVACY.md` retains ClearPDF branding and claims Play delivery (:12), contradicted by current installer selection. FOSS includes Google scanner/segmentation libraries; its name does not establish a fully free-software build. User-controlled sharing/process-text can send content to another app; local processing claims should distinguish that consented action from background upload.

## Required verification

Test backup/restore exclusions, URI grants and revoked grants, exported activity malformed inputs, narrow provider roots, archive quotas, DTD rejection, clear-local-copies behavior under active mirroring, journal corruption, disk-full and interrupted saves. Capture network behavior for local DOCX/HTML/OCR versus explicit URL conversion. Inspect actual merged manifests for all variants. No such runtime checks were performed here.
