# HMXPDF official website

Static website for the HMXPDF Android app. Built with HTML, compiled Tailwind CSS,
and vanilla JavaScript. No framework, backend, database, accounts, or runtime CSS
dependencies.

## Pages

| File | Purpose |
| --- | --- |
| `index.html` | Home, screenshots, feature overview, privacy summary |
| `features.html` | Verified PDF, Office, image, OCR, and conversion features, plus limits |
| `download.html` | GitHub Releases integration, APK/AAB distinction, checksums, install help |
| `privacy.html` | Privacy policy based on the app source |
| `faq.html` | Common questions and honest limits |
| `support.html` | GitHub issue reporting, install help, security guidance |
| `about.html` | Project, build, license, and attribution |
| `404.html` | Not-found page |

Supporting files: `sitemap.xml`, `robots.txt`, `site.webmanifest`, `_headers`
(Cloudflare Pages), `netlify.toml`, and this README.

## Preview locally

From the repository root:

```bash
python3 -m http.server 8080 -d hmxwebsite
```

Open <http://localhost:8080/>. The `.html` links work with any static file server.
Canonical URLs use extensionless paths because Cloudflare Pages and Netlify serve
them that way in production.

The Download page calls the public GitHub API from the browser. Previewing it from
`file://` will not work; use the local HTTP server. Without network access it shows
the error state and links to GitHub Releases.

## Rebuild the stylesheet

`assets/css/site.css` is compiled and committed, so deployment needs no build step.
After editing HTML, JavaScript class names, `src/input.css`, or
`tailwind.config.js`, rebuild it with the standalone Tailwind CLI (v3.4.x):

```bash
# Choose the binary for your machine: linux-x64, linux-arm64, macos-x64, macos-arm64
curl -L -o /tmp/tailwindcss \
  https://github.com/tailwindlabs/tailwindcss/releases/download/v3.4.17/tailwindcss-linux-x64
chmod +x /tmp/tailwindcss

/tmp/tailwindcss \
  -c tailwind.config.js \
  -i src/input.css \
  -o assets/css/site.css \
  --minify
```

If Node.js is available, `npx tailwindcss@3.4.17` works with the same arguments.
Do not switch to the Tailwind CDN: the deployed site has a strict Content Security
Policy and does not load third-party runtime code.

## How the download integration works

`assets/js/release.js` requests:

```text
https://api.github.com/repos/HMXPANEL/HMXPDF/releases/latest
```

It then:

- shows the release tag/name, publication date, and changelog body;
- lists individual `.apk` assets and identifies `.aab` assets separately;
- sends visitors to GitHub Releases; the direct Download APK button is disabled;
- shows each asset's size and SHA-256 when GitHub publishes a `digest`;
- never constructs a file name, version, date, checksum, or download URL;
- shows a clear **No public APK release yet** state on HTTP 404;
- distinguishes a release that exists but has no APK asset;
- shows an error state with a link to the official releases page if the API is
  unreachable or rate-limited.

At the time this site was built, the repository had **0 releases and 0 tags**, so
the expected state is the no-release message. The source version (`2.1.1`,
versionCode `6`) is shown only as build information and is labelled as such.

The API is unauthenticated. GitHub allows 60 requests per hour per IP address;
normal page views are far below that. There is no server-side proxy, cache, or
token in the website.

If the repository or release feed changes, update the two URLs at the top of
`assets/js/release.js` and the GitHub links throughout the pages and footer.

## Configure the real domain before launch

The site ships with `https://example.com` as an explicit placeholder in canonical
URLs, Open Graph URLs, `sitemap.xml`, and `robots.txt`. It must not be deployed
publicly while the placeholder remains.

```bash
bash scripts/set-domain.sh https://your-authorized-domain.example
```

The script rejects `http://`, paths, and `example.com`. It updates the HTML pages,
sitemap, and robots file. Do not deploy until you have an authorized domain, DNS
control, and permission to publish.

## Preflight

During development:

```bash
bash scripts/preflight.sh --allow-placeholder
```

Before deployment:

```bash
bash scripts/set-domain.sh https://your-authorized-domain.example
bash scripts/preflight.sh
```

The launch gate checks:

- HTML tag balance, one `<h1>` per page, unique IDs;
- titles, descriptions, canonical URLs, Open Graph and Twitter metadata;
- every local link, anchor, image, stylesheet, and script;
- all seven pages are present in the navigation, sitemap, and group footer;
- `aria-current` on the active navigation item;
- no `ClearPDF`, HMX Panel, or upstream author name in user-facing files;
- no external scripts, stylesheets, fonts, CDNs, or inline event handlers;
- compiled CSS, no unprocessed `@tailwind` directives;
- no APK/AAB files in the website folder;
- release fallback strings and API wiring present;
- the placeholder domain is gone.

## Deploy to Vercel (current production)

Production URL: <https://hmxpdf-website.vercel.app>

Keep the existing `hmxpdf-website` Vercel project and production domain. For the
account cutover, connect `https://github.com/HMXPANEL/HMXPDF` with these settings:

- Root Directory: `hmxwebsite`
- Framework Preset: Other
- Build Command: none (the compiled stylesheet is committed)
- Install Command: none
- Output Directory: `.`
- Production Branch: `release/hmxpdf-2.1.1-build`

After reconnection, each push to `release/hmxpdf-2.1.1-build` creates a production deployment
automatically. Pushes to other branches create preview deployments. `vercel.json`
supplies clean URLs, the CSP, the security headers, and the cache policy, and
`.vercelignore` keeps local tooling and source files out of the upload.

To redeploy the latest pushed commit without changing any file:

```bash
cd hmxwebsite
vercel --prod
```

## Deploy to Cloudflare Pages

1. Push this folder to the repository (the repository root contains `hmxwebsite/`).
2. In Cloudflare Pages, create a project and connect the GitHub repository.
3. Select the production branch you are authorized to deploy. The repository's
   current default branch is `release/hmxpdf-2.1.1-build`; do not assume `main`.
4. Framework preset: **None**.
5. Build command: leave empty.
6. Build output directory: `hmxwebsite` (if the repository root is the project
   root), or `.` if you set the root directory to `hmxwebsite`.
7. Deploy, then set the custom domain and DNS only if you control them.
8. `_headers` supplies the CSP and security headers. Confirm the deployed response
   includes `Content-Security-Policy` and `X-Content-Type-Options`.
9. Re-run the post-deploy checks below.

## Deploy to Netlify

1. Import the repository in Netlify.
2. Base directory: `hmxwebsite`.
3. Build command: leave empty.
4. Publish directory: `.`.
5. Deploy. `netlify.toml` supplies the same headers and cache policy as `_headers`.
6. Configure the custom domain and DNS only with authorization.

Alternatively, drag the `hmxwebsite` folder into Netlify Drop for a preview. Do not
attach a production domain until the domain gate passes.

## Post-deploy checks

- Open every page at desktop and phone widths (320 px, 360 px, 390 px, 768 px,
  1280 px).
- Open and close the mobile menu; confirm Escape closes it and focus is visible.
- Confirm the Download page shows either the no-release notice or the real latest
  release from GitHub — never invented data.
- If a release exists, click an APK link and confirm it resolves to a GitHub asset;
  compare the displayed SHA-256 with the GitHub release metadata.
- Check that canonical and Open Graph URLs use the real domain and return 200.
- Submit `/sitemap.xml` in the search console only after the domain is live.
- Verify the 404 page is served for a missing path.
- Confirm no `example.com` references remain: `grep -rn 'example.com' .`
- Confirm the Download page's network panel only contacts GitHub for the release
  request (plus the page's own assets).

## Content accuracy notes

- The pages work without JavaScript. Scroll reveals are opt-in via a `.js` class,
  the mobile menu falls back to the footer links, and the Download page shows a
  static "Release details need JavaScript" card plus the official releases link.
- On phones, the two privacy tables stack into labelled cards instead of
  requiring horizontal scrolling.
- Screenshots come from the current source build. The home screenshot is cropped
  to remove old branding; the other screenshots are the repository's dark-theme
  captures. No screenshot is mocked up.
- The privacy policy is based on `PRIVACY.md`, the manifests, backup rules, storage
  classes, and the absence of analytics, ads, and crash-reporting dependencies.
- The site avoids claiming full Microsoft Word parity, PDF text rewriting, PPT
  editing, a Play Store listing, or a published release.
- No email address, company address, social profile, rating, review, or download
  count is invented.
- Android, Microsoft Word/Excel/PowerPoint, and LibreOffice are trademarks of their
  respective owners; the footer states that HMXPDF is not affiliated with them.

## What not to add

Do not add a browser-based editor, accounts, a dashboard, a backend, a database, a
second app, an APK mirror, or third-party analytics. This site is intentionally a
small official information surface for the existing Android app.
