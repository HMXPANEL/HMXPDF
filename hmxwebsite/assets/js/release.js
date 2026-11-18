/* HMXPDF website — GitHub Releases loader.
 *
 * Reads the public GitHub Releases API at runtime. The page never invents a
 * version, date, asset URL, or checksum: if nothing is published (or the
 * release has no APK), it says so and links to the official releases page.
 */
(function () {
  'use strict';

  var API_URL = 'https://api.github.com/repos/HMXPANEL/HMXPDF/releases/latest';
  var RELEASES_URL = 'https://github.com/HMXPANEL/HMXPDF/releases';

  var panel = document.getElementById('release-panel');
  var chip = document.getElementById('home-release-status');

  if (!panel && !chip) return;

  function $(id) {
    return document.getElementById(id);
  }

  function setVisible(el, visible) {
    if (el) el.hidden = !visible;
  }

  function setChip(text) {
    if (chip) chip.textContent = text;
  }

  function formatBytes(bytes) {
    if (typeof bytes !== 'number' || !isFinite(bytes) || bytes < 0) return '';
    if (bytes < 1024) return bytes + ' B';
    var kb = bytes / 1024;
    if (kb < 1024) return kb.toFixed(kb < 10 ? 1 : 0) + ' KB';
    var mb = kb / 1024;
    return mb.toFixed(1) + ' MiB';
  }

  function parseDate(value) {
    if (!value) return null;
    var date = new Date(value);
    return isNaN(date.getTime()) ? null : date;
  }

  function formatDate(date) {
    if (!date) return '';
    try {
      return new Intl.DateTimeFormat(undefined, {
        year: 'numeric',
        month: 'short',
        day: 'numeric'
      }).format(date);
    } catch (error) {
      return date.toDateString();
    }
  }

  function safeHttpsUrl(value) {
    if (typeof value !== 'string') return '';
    try {
      var url = new URL(value);
      return url.protocol === 'https:' ? url.href : '';
    } catch (error) {
      return '';
    }
  }

  function releaseTimestamp(release) {
    return parseDate(release && (release.published_at || release.created_at));
  }

  function tagLabel(release) {
    if (!release) return '';
    return release.tag_name || release.name || 'Untitled release';
  }

  function setDownloadState(state) {
    setVisible($('release-loading'), state === 'loading');
    setVisible($('release-empty'), state === 'empty');
    setVisible($('release-error'), state === 'error');
    setVisible($('release-content'), state === 'content');
    if (panel) panel.setAttribute('aria-busy', state === 'loading' ? 'true' : 'false');
  }

  function showEmpty(title, message, releaseUrl) {
    setDownloadState('empty');
    var titleEl = $('release-empty-title');
    var textEl = $('release-empty-text');
    var linkEl = $('release-empty-link');
    if (titleEl) titleEl.textContent = title;
    if (textEl) textEl.textContent = message;
    if (linkEl) linkEl.href = safeHttpsUrl(releaseUrl) || RELEASES_URL;
  }

  function showError() {
    setDownloadState('error');
    setChip('Release status unavailable — check GitHub');
  }

  function copyText(value, button) {
    function done(ok) {
      if (!button) return;
      var original = button.getAttribute('data-label') || 'Copy';
      button.textContent = ok ? 'Copied' : 'Press Ctrl+C';
      if (ok) {
        window.setTimeout(function () {
          button.textContent = original;
        }, 1800);
      }
    }

    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(value).then(
        function () {
          done(true);
        },
        function () {
          legacyCopy(value, done);
        }
      );
    } else {
      legacyCopy(value, done);
    }
  }

  function legacyCopy(value, done) {
    var area = document.createElement('textarea');
    area.value = value;
    area.setAttribute('readonly', 'readonly');
    area.className = 'fixed left-0 top-0 h-px w-px opacity-0';
    document.body.appendChild(area);
    area.select();
    var ok = false;
    try {
      ok = document.execCommand('copy');
    } catch (error) {
      ok = false;
    }
    document.body.removeChild(area);
    done(ok);
  }

  function shaBlock(asset) {
    var match = typeof asset.digest === 'string' ? asset.digest.match(/^sha256:([a-f0-9]{64})$/i) : null;
    var wrap = document.createElement('div');
    wrap.className = 'mt-3';

    var label = document.createElement('p');
    label.className = 'text-[0.7rem] font-semibold uppercase tracking-[0.14em] text-mist-dim';
    label.textContent = 'SHA-256';
    wrap.appendChild(label);

    if (!match) {
      var missing = document.createElement('p');
      missing.className = 'mt-1 text-xs text-mist-dim';
      missing.textContent = 'GitHub did not publish a SHA-256 digest for this asset.';
      wrap.appendChild(missing);
      return wrap;
    }

    var row = document.createElement('div');
    row.className = 'mt-1 flex flex-wrap items-start gap-2';

    var code = document.createElement('code');
    code.className =
      'min-w-0 break-all rounded-lg bg-white/[0.05] px-2 py-1 font-mono text-[0.7rem] leading-5 text-accent-soft';
    code.textContent = match[1];
    row.appendChild(code);

    var button = document.createElement('button');
    button.type = 'button';
    button.className =
      'rounded-full border border-white/10 px-2.5 py-1 text-[0.7rem] font-medium text-mist transition hover:border-accent/50 hover:text-white';
    button.textContent = 'Copy';
    button.setAttribute('data-label', 'Copy');
    button.setAttribute('aria-label', 'Copy SHA-256 for ' + asset.name);
    button.addEventListener('click', function () {
      copyText(match[1], button);
    });
    row.appendChild(button);

    wrap.appendChild(row);
    return wrap;
  }

  function assetRow(asset, kind) {
    var item = document.createElement('li');
    item.className =
      'flex flex-col gap-4 rounded-2xl border border-white/10 bg-night-900/60 p-4 sm:flex-row sm:items-start sm:justify-between';

    var info = document.createElement('div');
    info.className = 'min-w-0';

    var name = document.createElement('p');
    name.className = 'break-all text-sm font-semibold text-white';
    name.textContent = asset.name || 'Unnamed asset';
    info.appendChild(name);

    var meta = document.createElement('p');
    meta.className = 'mt-1 text-xs uppercase tracking-[0.12em] text-mist-dim';
    var size = formatBytes(asset.size);
    meta.textContent = kind + (size ? ' · ' + size : '');
    info.appendChild(meta);

    if (kind !== 'AAB') {
      info.appendChild(shaBlock(asset));
    }

    item.appendChild(info);

    return item;
  }

  function renderRelease(release, packages, aabs) {
    setDownloadState('content');

    var tag = tagLabel(release);
    var date = releaseTimestamp(release);

    var tagEl = $('release-tag');
    if (tagEl) tagEl.textContent = tag;

    var nameEl = $('release-name');
    if (nameEl) {
      var hasName = release.name && release.name !== release.tag_name;
      nameEl.textContent = hasName ? release.name : '';
      nameEl.hidden = !hasName;
    }

    var dateEl = $('release-date');
    if (dateEl) {
      if (date) {
        dateEl.dateTime = date.toISOString();
        dateEl.textContent = 'Published ' + formatDate(date);
      } else {
        dateEl.removeAttribute('datetime');
        dateEl.textContent = 'Publication date not provided';
      }
    }

    var notesEl = $('release-notes');
    if (notesEl) {
      var body = typeof release.body === 'string' ? release.body.trim() : '';
      notesEl.textContent = body || 'This release does not include a changelog.';
    }

    var apkList = $('release-apk-list');
    if (apkList) {
      apkList.textContent = '';
      packages.forEach(function (asset) {
        apkList.appendChild(assetRow(asset, 'APK'));
      });
    }

    var aabSection = $('release-aab-section');
    var aabList = $('release-aab-list');
    if (aabSection && aabList) {
      aabList.textContent = '';
      aabs.forEach(function (asset) {
        aabList.appendChild(assetRow(asset, 'AAB'));
      });
      aabSection.hidden = aabs.length === 0;
    }

    var releaseLink = $('release-page-link');
    if (releaseLink) {
      releaseLink.href = safeHttpsUrl(release.html_url) || RELEASES_URL;
    }

    setChip('Latest public release: ' + tag + (date ? ' · ' + formatDate(date) : ''));
  }

  function handleRelease(release) {
    if (!release) {
      showEmpty(
        'No public APK release yet',
        'No release has been published on the official GitHub repository. When an APK is published, this page will show the verified version, release date, download link, and SHA-256 automatically.',
        RELEASES_URL
      );
      setChip('No public APK release yet');
      return;
    }

    var assets = Array.isArray(release.assets) ? release.assets : [];
    var packages = assets.filter(function (asset) {
      return asset && asset.state === 'uploaded' && asset.size > 0 &&
        /\.apk$/i.test(asset.name || '');
    });
    var aabs = assets.filter(function (asset) {
      return /\.aab$/i.test(asset.name || '');
    });

    if (!packages.length) {
      var date = releaseTimestamp(release);
      showEmpty(
        'No installable APK in the latest release',
        'GitHub lists ' +
          tagLabel(release) +
          (date ? ' (published ' + formatDate(date) + ')' : '') +
          ', but that release has no APK asset. Open the release page to see its assets.',
        safeHttpsUrl(release.html_url) || RELEASES_URL
      );
      setChip('Latest release has no APK asset yet');
      return;
    }

    renderRelease(release, packages, aabs);
  }

  var controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
  var timer = controller
    ? window.setTimeout(function () {
        controller.abort();
      }, 10000)
    : null;

  fetch(API_URL, {
    headers: { Accept: 'application/vnd.github+json' },
    signal: controller ? controller.signal : undefined
  })
    .then(function (response) {
      if (timer) window.clearTimeout(timer);
      if (response.status === 404) return null;
      if (!response.ok) throw new Error('GitHub API returned ' + response.status);
      return response.json();
    })
    .then(function (release) {
      handleRelease(release);
    })
    .catch(function () {
      if (timer) window.clearTimeout(timer);
      if (panel) {
        setDownloadState('error');
        setChip('Release status unavailable — check GitHub');
      } else {
        setChip('Check release status on GitHub');
      }
    });
})();
