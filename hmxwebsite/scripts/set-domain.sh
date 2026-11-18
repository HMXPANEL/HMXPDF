#!/usr/bin/env bash
#
# Replace the https://example.com placeholder with the real website origin.
#
#   ./scripts/set-domain.sh https://hmxpdf.example
#   ./scripts/set-domain.sh hmxpdf.example
#
# Only canonical URLs, Open Graph URLs, sitemap.xml, and robots.txt are updated.
# The script refuses http:// and refuses to keep example.com.

set -euo pipefail
cd "$(dirname "$0")/.." || exit 1

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 https://your-domain.example" >&2
  exit 2
fi

input="$1"

if [[ "$input" == http://* ]]; then
  echo "Refusing http://; the canonical origin must use https://." >&2
  exit 1
fi

if [[ "$input" != https://* ]]; then
  origin="https://$input"
else
  origin="$input"
fi

origin="${origin%/}"

if [[ ! "$origin" =~ ^https://([A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?\.)+[A-Za-z]{2,}(:[0-9]+)?$ ]]; then
  echo "Invalid origin: $origin" >&2
  echo "Use a hostname such as https://hmxpdf.example (no path, query, or trailing slash)." >&2
  exit 1
fi

if [[ "$origin" == *"example.com"* ]]; then
  echo "That is still the placeholder domain. Use the real authorized domain." >&2
  exit 1
fi

files="$(grep -rlF 'https://example.com' --include='*.html' --include='*.xml' --include='*.txt' . || true)"
if [ -z "$files" ]; then
  echo "No https://example.com placeholder found. Nothing to change."
  exit 0
fi

while IFS= read -r file; do
  [ -n "$file" ] || continue
  sed -i "s|https://example.com|$origin|g" "$file"
  echo "updated $file"
done <<< "$files"

if grep -rqF 'https://example.com' --include='*.html' --include='*.xml' --include='*.txt' .; then
  echo "Placeholder still present after update; inspect the files above." >&2
  exit 1
fi

echo
echo "Canonical origin set to: $origin"
echo "Next: ./scripts/preflight.sh"
