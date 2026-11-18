# Privacy notes

HMXPDF is an offline-capable Android document app. It has no account system,
in-app advertising, analytics SDK, or crash-reporting service. Document
operations run on the device, and there is no HMXPDF server for uploading user
files or extracted text.

## Network use

Network access is limited to features that need it:

- **Optional Office engine.** In the FOSS build, you can choose to download the
  LibreOffice-based engine from a pinned GitHub release in Settings. HMXPDF checks
  the expected archive size and SHA-256 before installing it. The engine converts
  documents locally in an isolated app process.
- **Web address to PDF.** The FOSS build can load a web address you enter and
  render the page as a PDF. This sends normal requests to that website and any
  page resources it loads. The request goes to the address you chose, not to an
  HMXPDF document-processing server.
- **Google Play services components.** The document scanner is provided by Google
  Play services. Background removal uses ML Kit subject segmentation, whose model
  may be delivered by Google Play services. Google manages those service and model
  downloads; document processing remains on-device.
- **Application manifests.** The main manifest does not declare the `INTERNET`
  permission; the FOSS flavor adds it for the two user-initiated features above,
  and bundled libraries may contribute permissions during manifest merging. The
  optional Office engine is downloaded by the app in the `play` and `foss` builds;
  the `fdroid` build does not offer it. See [NOTICE](NOTICE) and the dependency
  manifests for details.

Converting local HTML, rendering DOCX content, OCR, spreadsheet editing, image
tools, and PDF operations use local content. The WebViews used for local HTML and
DOCX rendering block external network loads; URL-to-PDF intentionally loads the
address you enter.

## Files and permissions

Files are opened and saved through Android's document picker or HMXPDF's export
flow. Recent-file entries are stored locally and can be removed from the home
screen. HMXPDF does not intentionally send selected documents or their extracted
contents to a server controlled by the project.

Android permissions are requested for features that need them:

- **Camera** for scanning and document capture.
- **Storage read access** (Android 12 and below) for opening files you select.
- **Storage write access** (Android 9 and below) for saving exports.
- **Notifications** and a **data-sync foreground service** for long operations such
  as engine installation.

Device and Android-version availability can affect individual features.

## Scanning and OCR

Paper scanning uses Google ML Kit Document Scanner through Google Play services.
Background removal uses ML Kit subject segmentation; Google Play services may
provide its model. OCR for scanned or image-only PDF pages uses bundled on-device
ML Kit text recognition with a Tesseract fallback. These components process
content on-device and do not send document contents to an HMXPDF server.

## Children and accounts

HMXPDF has no accounts, profiles, or social features, and collects no personal
information itself.

## Reporting a concern

Do not attach private documents, personal information, access tokens, or signing
keys to public issues. For a suspected vulnerability, use the private reporting
process in [SECURITY.md](SECURITY.md). For other privacy questions, open an issue
that describes the concern without including sensitive content.
