# HMX PDF privacy notes

HMX PDF (ClearPDF) is an offline-capable Android document app distributed privately as an APK. The app has no account system, in-app advertising, analytics SDK, or crash-reporting service. Documents are processed on the device; HMX PDF does not provide a server for uploading documents or extracted text.

This document describes the code in this repository. It is maintained for transparency and maintenance. The app is not published on Google Play, and this document is not a Google Play Data Safety declaration.

## On-device processing

Opening, editing, annotating, converting, scanning, and OCR of documents happen on the device by default. Local HTML and DOCX rendering use WebViews that block external network loads. The optional features listed below are the only paths that use the network.

## Network requests

### Remote feature configuration

After the first-run privacy flow is completed, the app may request a small feature-configuration document once per ordinary app launch. The HTTPS request contains only:

- the flag keys `diagnostics.enabled` and `problem_reports.enabled`;
- the installed app version code.

It does not contain the install ID, an account identifier, document data, filenames, or location. The app may also refresh configuration when you tap Send for a report if the cached setting says that report type is unavailable. The server receives ordinary HTTP request metadata, including the IP address, to serve and rate-limit the request. The app stores only a SHA-256 hash of the IP address in a rate-limit bucket, which is deleted after two days. Hosting infrastructure may process standard request metadata under its own terms.

### Optional reports

Diagnostic reports and problem reports are off by default. During first-run onboarding you can enable each type independently. Choosing **Not now** leaves both off. In this version the choices are recorded only in that flow; Settings shows them but does not change them.

No report is sent automatically or in the background. Tapping **Send** always shows a confirmation, and the app sends only after you confirm. The preview shows the exact report contents. If sending fails, the report stays on the device until you retry or discard it. Retry reuses the same report ID so the server can deduplicate; there is no automatic retry or background upload.

### Report-only install ID

When you first send a report, the app creates a random version-4 UUID. It is stored in the app's private `clearpdf_privacy.xml` preferences, which are excluded from Android backup and device-to-device transfer. It is used only to deduplicate retries and apply per-install rate limits. It is not a hardware identifier, advertising ID, or account ID, and it resets if you clear app data or reinstall.

## What a report contains

Both report types include:

- schema version and report kind;
- a random client report ID and the report-only install ID;
- the client-created timestamp;
- app version code and name, build flavor and type, Android API level, and locale.

A **diagnostic report** additionally contains the results of four app self-checks: storage write, cache write, PDF engine initialization, and report-queue initialization, each reported as `ok`, `failed`, or `skipped`. It has no description, category, or error attachment.

A **problem report** additionally contains your description (up to 4,000 characters) and an optional category (`bug`, `crash`, `performance`, or `other`). The current app does not attach error logs, stack traces, screenshots, or device model. The backend schema can store an attached error summary and detail, but no current UI path fills that field; if that changes, the data will appear in the preview before sending.

The app checks descriptions for paths, URLs, email- or phone-like text, secrets or tokens, and long opaque values. It flags them and blocks preview until you edit them. Detection is not perfect, so do not include private information.

## Deliberately excluded

Reports and configuration requests do not include document contents, filenames, paths, URIs, annotations, form values, signatures, passwords, tokens, raw logs, screenshots, device model, location, contacts, hardware identifiers, or advertising IDs. No selected document or extracted text is uploaded.

A user-authored description can still contain personal information; the preview, confirmation, and warning exist so you can remove it before sending.

## Server storage and access

Reports are stored in a private `hmx` schema in a Supabase Postgres database. Row-level security is enabled and the public, anonymous, and authenticated roles have no table privileges. Only the service role used by the Edge Functions can access the table. There are no row-level security policies that expose reports.

Admin access to reports goes through the `admin-api` Edge Function. A request must come from the configured admin web origin, carry a valid Supabase Auth session, and match an active row in `hmx.admin_users` with the `admin` role. Admin report actions are written to an audit log. Multi-factor authentication is optional in the current code. In staging the admin API is deployed but fail-closed because `ADMIN_ORIGIN` is not configured; no live admin authorization has been verified.

## Retention and deletion

The backend is designed to delete reports 30 days after server receipt, measured from `received_at`, using a scheduled `pg_cron` purge that deletes bounded batches. The staging project has an active scheduler with successful run history, and staging tests confirmed that expired reports are removed while newer reports remain.

Production has not been deployed or verified for this feature. Until it is, automatic 30-day deletion is not guaranteed, and reports remain until an administrator deletes them. The in-app privacy copy states that automatic deletion is not yet active.

Rate-limit buckets use SHA-256 hashes of the IP address and install ID. They are purged after two days; this cleanup was verified in staging.

Pending reports on the device are kept in app-private no-backup storage until they are sent or discarded. Clearing app data or uninstalling the app removes them and the install ID. There is no self-service server-side deletion because reports are not linked to an account. To request deletion of a sent report, contact the maintainers through the repository and include the report ID or server ID shown in the app if available. For a request containing sensitive identifiers, use the private reporting route in [SECURITY.md](SECURITY.md).

## Other network use

- **Optional Office engine (FOSS builds):** You can choose to download the LibreOffice-based engine from a pinned GitHub release in Settings. HMX PDF checks the expected archive size and SHA-256 before installing it. The engine converts documents locally in an isolated app process.
- **Web address to PDF (FOSS builds):** The app can load a web address you enter and render the page as a PDF. This sends normal requests to that website and any page resources it loads. The request goes to the address you chose, not to an HMX PDF document-processing server.
- **Google Play services components (Play build):** The scanner is provided by Google Play services. Background removal uses ML Kit subject segmentation, whose model may be delivered by Google Play services. Google manages those service/model downloads; document processing remains on-device.
- **Internet permission:** The app declares the `INTERNET` permission because remote configuration, explicit report submission, and the FOSS network features use it. Cleartext traffic is disabled.

## Status and limitations

This policy describes the Phase 3 code as of 2026-10-26. The reports backend, configuration endpoint, retention job, and admin API have been exercised in the isolated staging project. Production deployment of the reports backend and retention job has not been authorized or verified. If a build has no report endpoint or publishable key configured, report submission is disabled and fails closed.

## Reporting a concern

Do not attach private documents, personal information, access tokens, or signing keys to public issues. For a suspected vulnerability, use the private reporting link in [SECURITY.md](SECURITY.md).
