#!/usr/bin/env python3
"""Static checks for the HMXPDF website.

Checks HTML structure, local links, metadata, navigation, images, release
wiring, the sitemap, robots.txt, and the web manifest. No network access.
"""

from __future__ import annotations

import json
import re
import sys
import xml.etree.ElementTree as ET
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urlparse

ROOT = Path(__file__).resolve().parent.parent

MAIN_PAGES = [
    "index.html",
    "features.html",
    "download.html",
    "privacy.html",
    "faq.html",
    "support.html",
    "about.html",
]
ALL_PAGES = MAIN_PAGES + ["404.html"]
VOID = {
    "area",
    "base",
    "br",
    "col",
    "embed",
    "hr",
    "img",
    "input",
    "link",
    "meta",
    "param",
    "source",
    "track",
    "wbr",
}

errors: list[str] = []
warnings: list[str] = []
checks = 0


def ok(label: str) -> None:
    global checks
    checks += 1


def fail(label: str) -> None:
    global checks
    checks += 1
    errors.append(label)


def warn(label: str) -> None:
    global checks
    checks += 1
    warnings.append(label)


class Doc(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.stack: list[str] = []
        self.ids: list[str] = []
        self.links: list[tuple[str, dict]] = []
        self.images: list[dict] = []
        self.metas: list[dict] = []
        self.stylesheets: list[dict] = []
        self.scripts: list[dict] = []
        self.h1 = 0
        self.aria_current: list[dict] = []
        self.in_title = False
        self.title_parts: list[str] = []
        self.text_parts: list[str] = []
        self.canonical = ""
        self.has_doctype = False

    def _start(self, tag: str, attrs: list[tuple[str, str | None]], self_closing: bool) -> None:
        tag = tag.lower()
        data = {key: (value or "") for key, value in attrs}

        if "id" in data and data["id"]:
            self.ids.append(data["id"])

        if tag == "a" and "href" in data:
            self.links.append((data["href"], data))
        if tag == "link" and "href" in data:
            self.links.append((data["href"], data))
            rel = data.get("rel", "").lower().split()
            if "stylesheet" in rel:
                self.stylesheets.append(data)
            if "canonical" in rel:
                self.canonical = data["href"]
        if tag == "script":
            self.scripts.append(data)
        if tag == "img":
            self.images.append(data)
        if tag == "meta":
            self.metas.append(data)
        if tag == "h1":
            self.h1 += 1
        if data.get("aria-current") == "page":
            self.aria_current.append(data)
        if tag == "title":
            self.in_title = True

        if tag in VOID or self_closing:
            return

        self.stack.append(tag)

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self._start(tag, attrs, False)

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self._start(tag, attrs, True)

    def handle_endtag(self, tag: str) -> None:
        tag = tag.lower()
        if tag == "title":
            self.in_title = False
        if tag in VOID:
            return
        if not self.stack:
            fail(f"stray closing tag </{tag}> in {self.page}")
            return
        if self.stack[-1] == tag:
            self.stack.pop()
        elif tag in self.stack:
            unclosed = self.stack[self.stack.index(tag) + 1 :]
            fail(
                f"mismatched closing tag </{tag}> in {self.page}; unclosed: {', '.join(unclosed)}"
            )
            while self.stack and self.stack[-1] != tag:
                self.stack.pop()
            if self.stack:
                self.stack.pop()
        else:
            fail(f"unexpected closing tag </{tag}> in {self.page}")

    def handle_data(self, data: str) -> None:
        if self.in_title:
            self.title_parts.append(data)
        self.text_parts.append(data)

    def handle_decl(self, decl: str) -> None:
        if decl.lower().strip() == "doctype html":
            self.has_doctype = True

    @property
    def title(self) -> str:
        return " ".join("".join(self.title_parts).split())

    @property
    def text(self) -> str:
        return " ".join("".join(self.text_parts).split())


def parse(path: Path) -> Doc:
    doc = Doc()
    doc.page = path.name  # type: ignore[attr-defined]
    doc.feed(path.read_text(encoding="utf-8"))
    doc.close()
    return doc


def meta_content(doc: Doc, key: str) -> str:
    for item in doc.metas:
        if item.get("name", "").lower() == key or item.get("property", "").lower() == key:
            return item.get("content", "")
    return ""


def check_page(page: str) -> Doc:
    path = ROOT / page
    if not path.exists():
        fail(f"{page}: file is missing")
        return Doc()

    doc = parse(path)

    if not doc.has_doctype:
        fail(f"{page}: missing <!doctype html>")
    else:
        ok(f"{page}: doctype")

    if doc.stack:
        fail(f"{page}: unclosed tag(s) at end of file: {', '.join(doc.stack)}")
    else:
        ok(f"{page}: tag structure")

    title = doc.title
    if not title:
        fail(f"{page}: missing <title>")
    elif len(title) > 70:
        warn(f"{page}: title is {len(title)} characters (aim for 70 or fewer)")
    else:
        ok(f"{page}: title")

    description = meta_content(doc, "description")
    if not description:
        fail(f"{page}: missing meta description")
    elif not 50 <= len(description) <= 185:
        warn(f"{page}: meta description is {len(description)} characters (aim 50-185)")
    else:
        ok(f"{page}: meta description")

    if doc.h1 != 1:
        fail(f"{page}: expected exactly one <h1>, found {doc.h1}")
    else:
        ok(f"{page}: h1")

    if len(doc.ids) != len(set(doc.ids)):
        duplicates = sorted({item for item in doc.ids if doc.ids.count(item) > 1})
        fail(f"{page}: duplicate id(s): {', '.join(duplicates)}")
    else:
        ok(f"{page}: unique ids")

    canonical = doc.canonical
    if page == "404.html":
        if canonical:
            fail("404.html: should not declare a canonical URL")
        if meta_content(doc, "robots") != "noindex, follow":
            fail("404.html: expected noindex, follow")
        else:
            ok("404.html: noindex")
    else:
        if not canonical:
            fail(f"{page}: missing canonical link")
        else:
            parsed = urlparse(canonical)
            expected_path = "/" if page == "index.html" else "/" + page[:-5]
            if parsed.scheme != "https" or not parsed.netloc:
                fail(f"{page}: canonical must be an absolute https URL")
            elif parsed.path != expected_path:
                fail(f"{page}: canonical path {parsed.path} should be {expected_path}")
            else:
                ok(f"{page}: canonical")

        # Open Graph and Twitter basics.
        for key in ("og:type", "og:title", "og:description", "og:url", "og:image"):
            if not meta_content(doc, key):
                fail(f"{page}: missing {key}")
        if not meta_content(doc, "twitter:card"):
            fail(f"{page}: missing twitter:card")
        og_image = meta_content(doc, "og:image")
        if og_image:
            image_path = ROOT / unquote(urlparse(og_image).path.lstrip("/"))
            if not image_path.exists():
                fail(f"{page}: og:image does not exist locally: {og_image}")
            elif urlparse(og_image).scheme != "https":
                fail(f"{page}: og:image must use https")
            else:
                ok(f"{page}: og:image")

        og_url = meta_content(doc, "og:url")
        if og_url and canonical and urlparse(og_url).hostname != urlparse(canonical).hostname:
            fail(f"{page}: og:url host differs from canonical host")
        if og_url and canonical and urlparse(og_url).path != urlparse(canonical).path:
            fail(f"{page}: og:url path differs from canonical path")

    if page != "404.html":
        current = doc.aria_current
        expected = "index.html" if page == "index.html" else page
        # The active item appears once in the desktop nav and once in the mobile nav.
        if len(current) != 2:
            fail(f"{page}: expected two aria-current=page markers, found {len(current)}")
        elif any(item.get("href", "") != expected for item in current):
            targets = [item.get("href", "") for item in current]
            fail(f"{page}: aria-current points to {targets}, expected {expected}")
        else:
            ok(f"{page}: active navigation")

        # Every main page must link to every main page (header or footer).
        hrefs = {urlparse(href).path for href, _ in doc.links if href}
        for target in MAIN_PAGES:
            if target not in hrefs:
                fail(f"{page}: missing navigation link to {target}")
        if page not in ("404.html",):
            text = doc.text
            if "trademark of Google" not in text:
                fail(f"{page}: missing Android trademark attribution")
            if "not affiliated with or endorsed by these companies" not in text:
                fail(f"{page}: missing affiliation disclaimer")

    # Links and assets.
    for href, attrs in doc.links:
        if href == "":
            fail(f"{page}: empty link")
            continue
        if href.lower().startswith(("javascript:", "data:")):
            fail(f"{page}: unsafe link scheme: {href[:40]}")
            continue
        parsed = urlparse(href)
        if parsed.scheme in ("http", "https", "mailto", "tel"):
            if attrs.get("target") == "_blank" and "noopener" not in attrs.get("rel", ""):
                fail(f"{page}: target=_blank without rel=noopener: {href}")
            continue
        if parsed.scheme:
            fail(f"{page}: unexpected link scheme: {href[:40]}")
            continue

        local = unquote(parsed.path)
        if local.startswith("/"):
            target_path = ROOT / local.lstrip("/")
        else:
            target_path = ROOT / local if local else path

        if local:
            if not target_path.exists():
                fail(f"{page}: broken local link: {href}")
            continue

        if parsed.fragment:
            if parsed.fragment not in doc.ids:
                fail(f"{page}: missing anchor target #{parsed.fragment} for {href}")
            else:
                ok(f"{page}: anchor {href}")

    for image in doc.images:
        src = image.get("src", "")
        if not src:
            fail(f"{page}: image without src")
            continue
        if "alt" not in image:
            fail(f"{page}: image without alt: {src}")
        if urlparse(src).scheme:
            fail(f"{page}: image should be local: {src}")
            continue
        if not (ROOT / unquote(src)).exists():
            fail(f"{page}: missing image: {src}")
        if not image.get("width") or not image.get("height"):
            warn(f"{page}: image without width/height: {src}")

    for css in doc.stylesheets:
        href = css.get("href", "")
        if urlparse(href).scheme:
            fail(f"{page}: external stylesheet is not allowed: {href}")
        elif not (ROOT / unquote(href)).exists():
            fail(f"{page}: missing stylesheet: {href}")
        else:
            ok(f"{page}: stylesheet")

    for script in doc.scripts:
        src = script.get("src", "")
        if not src:
            fail(f"{page}: inline <script> is not allowed by the CSP")
        elif urlparse(src).scheme:
            fail(f"{page}: external script is not allowed: {src}")
        elif not (ROOT / unquote(src)).exists():
            fail(f"{page}: missing script: {src}")
        else:
            ok(f"{page}: script {src}")

    # Release integration wiring.
    if page == "download.html":
        if "release-panel" not in path.read_text(encoding="utf-8"):
            fail("download.html: missing release-panel")
        if "assets/js/release.js" not in [s.get("src", "") for s in doc.scripts]:
            fail("download.html: missing release.js")
        else:
            ok("download.html: release integration")
    if page == "index.html":
        if "home-release-status" not in path.read_text(encoding="utf-8"):
            fail("index.html: missing home-release-status")
        if "assets/js/release.js" not in [s.get("src", "") for s in doc.scripts]:
            fail("index.html: missing release.js")
        else:
            ok("index.html: release integration")

    return doc


def check_sitemap(host: str) -> None:
    path = ROOT / "sitemap.xml"
    if not path.exists():
        fail("sitemap.xml: missing")
        return
    try:
        tree = ET.parse(path)
    except ET.ParseError as error:
        fail(f"sitemap.xml: XML parse error: {error}")
        return

    ns = {"sm": "http://www.sitemaps.org/schemas/sitemap/0.9"}
    locs = [item.text.strip() for item in tree.findall(".//sm:loc", ns) if item.text]
    expected = {("https://" + host + ("/" if page == "index.html" else "/" + page[:-5])) for page in MAIN_PAGES}
    if set(locs) != expected:
        missing = sorted(expected - set(locs))
        extra = sorted(set(locs) - expected)
        fail(f"sitemap.xml: URL set mismatch; missing={missing}, extra={extra}")
    else:
        ok("sitemap.xml: page set")

    if len(locs) != len(set(locs)):
        fail("sitemap.xml: duplicate URLs")
    for loc in locs:
        if urlparse(loc).scheme != "https":
            fail(f"sitemap.xml: non-https URL: {loc}")

    host_el = host
    robots = (ROOT / "robots.txt").read_text(encoding="utf-8") if (ROOT / "robots.txt").exists() else ""
    if f"Sitemap: https://{host_el}/sitemap.xml" not in robots:
        fail("robots.txt: Sitemap host does not match the canonical host")
    else:
        ok("robots.txt: sitemap")


def check_manifest() -> None:
    path = ROOT / "site.webmanifest"
    if not path.exists():
        fail("site.webmanifest: missing")
        return
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        fail(f"site.webmanifest: invalid JSON: {error}")
        return
    for icon in data.get("icons", []):
        icon_path = ROOT / unquote(icon.get("src", ""))
        if not icon_path.exists():
            fail(f"site.webmanifest: missing icon {icon.get('src')}")
    ok("site.webmanifest: valid")


def main() -> int:
    if not (ROOT / "index.html").exists():
        print(f"FAIL: run this from the website folder; {ROOT}/index.html is missing")
        return 1

    docs: dict[str, Doc] = {}
    for page in ALL_PAGES:
        docs[page] = check_page(page)

    hosts = set()
    for page in ALL_PAGES:
        canonical = docs[page].canonical
        if canonical:
            host = urlparse(canonical).hostname
            if host:
                hosts.add(host)
    if len(hosts) != 1:
        fail(f"canonical URLs use inconsistent hosts: {sorted(hosts)}")
        host = "example.com"
    else:
        host = hosts.pop()
        ok("canonical host is consistent")

    check_sitemap(host)
    check_manifest()

    # No installable packages may be published through the website folder.
    packages = list(ROOT.glob("*.apk")) + list(ROOT.glob("*.aab")) + list(ROOT.glob("**/*.apk")) + list(ROOT.glob("**/*.aab"))
    if packages:
        fail(f"unexpected APK/AAB file(s) in website: {[str(p.relative_to(ROOT)) for p in packages]}")
    else:
        ok("no APK/AAB files in the website")

    # Confirm the release loader has the documented no-release fallback.
    release_js = (ROOT / "assets/js/release.js").read_text(encoding="utf-8")
    for needle in ("No public APK release yet", "No installable APK in the latest release", "api.github.com/repos/HMXPANEL/HMXPDF"):
        if needle not in release_js:
            fail(f"release.js: missing expected fallback marker: {needle}")
    else:
        ok("release.js: fallback and API wiring")

    print()
    for message in warnings:
        print(f"WARN  {message}")
    for message in errors:
        print(f"FAIL  {message}")
    print(f"\n{checks} checks, {len(errors)} failure(s), {len(warnings)} warning(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
