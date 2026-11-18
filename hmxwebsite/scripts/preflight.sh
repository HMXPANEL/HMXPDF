#!/usr/bin/env bash
#
# Content and pre-deployment gate for the HMXPDF website.
#
#   ./scripts/preflight.sh --allow-placeholder   # during development
#   ./scripts/preflight.sh                       # launch gate (domain required)
#
# The launch gate fails while https://example.com is still present. Run
# ./scripts/set-domain.sh https://your-domain.example first.

set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

ALLOW_PLACEHOLDER=0
for arg in "$@"; do
  case "$arg" in
    --allow-placeholder) ALLOW_PLACEHOLDER=1 ;;
    -h|--help)
      sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "Unknown option: $arg" >&2
      exit 2
      ;;
  esac
done

status=0
pass() { printf '  ok    %s\n' "$1"; }
warn() { printf '  warn  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; status=1; }

echo "HMXPDF website preflight"
echo "========================"

echo
echo "Content and structure"
python3 scripts/check_site.py || status=1

echo
echo "Placeholder domain"
PLACEHOLDER_FILES="$(grep -rlF 'https://example.com' --include='*.html' --include='*.xml' --include='*.txt' . 2>/dev/null || true)"
if [ -n "$PLACEHOLDER_FILES" ]; then
  if [ "$ALLOW_PLACEHOLDER" -eq 1 ]; then
    warn "placeholder https://example.com is still present:"
    printf '        %s\n' $PLACEHOLDER_FILES
  else
    fail "placeholder https://example.com is still present; run ./scripts/set-domain.sh https://your-domain.example"
    printf '        %s\n' $PLACEHOLDER_FILES
  fi
else
  pass "no placeholder domain remains"
fi

echo
echo "Branding and forbidden content"
FORBIDDEN="$(grep -rniE 'clear ?pdf|bharti1233|chethan616' \
  --include='*.html' --include='*.css' --include='*.js' --include='*.xml' \
  --include='*.txt' --include='*.webmanifest' . 2>/dev/null || true)"
if [ -n "$FORBIDDEN" ]; then
  fail "found an excluded brand or name in site files:"
  printf '        %s\n' "$FORBIDDEN"
else
  pass "no previous product branding or repository owner in site files"
fi

echo
echo "Static-site constraints"
if grep -rnE '<script[^>]+src="https?://' --include='*.html' . >/dev/null 2>&1; then
  fail "external <script> found"
else
  pass "JavaScript is self-hosted"
fi

if grep -rnE '<link[^>]+rel="stylesheet"[^>]+href="https?://' --include='*.html' . >/dev/null 2>&1; then
  fail "external stylesheet found"
else
  pass "CSS is self-hosted"
fi

if grep -rniE 'cdn\.tailwindcss|fonts\.googleapis|fonts\.gstatic|unpkg\.com|cdn\.jsdelivr' \
  --include='*.html' --include='*.css' --include='*.js' . >/dev/null 2>&1; then
  fail "CDN or external font reference found"
else
  pass "no CDN or external font references"
fi

if grep -rnE '\son[a-z]+[[:space:]]*=' --include='*.html' . >/dev/null 2>&1; then
  fail "inline event handler found; the CSP blocks inline scripts"
else
  pass "no inline event handlers"
fi

if grep -q '@tailwind' assets/css/site.css 2>/dev/null; then
  fail "assets/css/site.css contains unprocessed Tailwind directives"
elif [ ! -s assets/css/site.css ]; then
  fail "assets/css/site.css is missing or empty"
else
  pass "compiled stylesheet is present"
fi

if find . -type f \( -name '*.apk' -o -name '*.aab' \) | grep -q .; then
  fail "APK/AAB file found in the website folder"
else
  pass "no APK/AAB files published with the website"
fi

if grep -rn 'TODO\|FIXME' --include='*.html' --include='*.js' --include='*.css' . >/dev/null 2>&1; then
  warn "TODO/FIXME text remains in the site"
else
  pass "no TODO/FIXME text"
fi

TOTAL_SIZE="$(du -sk . | awk '{print $1}')"
if [ "$TOTAL_SIZE" -gt 5120 ]; then
  warn "site folder is ${TOTAL_SIZE} KB; review large assets"
else
  pass "site folder is ${TOTAL_SIZE} KB"
fi

echo
if [ "$status" -eq 0 ]; then
  if [ "$ALLOW_PLACEHOLDER" -eq 1 ] && [ -n "$PLACEHOLDER_FILES" ]; then
    echo "Content checks passed. Deployment gate is NOT ready: configure the real domain first."
  else
    echo "All preflight checks passed."
  fi
else
  echo "Preflight failed. Fix the items marked FAIL above."
fi

exit "$status"
