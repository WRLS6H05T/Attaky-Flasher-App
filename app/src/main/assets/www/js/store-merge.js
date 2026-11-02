/*
 * store-merge.js — one merged download for the flasher, from the top of a
 * firmware page, and a "downloaded" mark on what this phone already has.
 *
 * A firmware page's hero carries a "Go to Download" button that jumps to the
 * "Download Files" table, where each part has its own row and Download link.
 * Those rows are left exactly as they are: tapping one still saves that single
 * part.
 *
 * The hero button is the merged download instead — "Download (merged for
 * flasher)". It takes every part of the firmware on the page, at the flash
 * address the table lists for it, and the app joins them into one image ready to
 * flash offline. Once that image is on the phone the button says so
 * ("Downloaded ✓"), and every card in the store's listing that is already on the
 * phone wears a small chip, so nothing has to be downloaded twice to find out.
 *
 * Firmwares are recognised by the store's own id: detail pages are
 * `/en/normol_index/firmware_details/50` and parts live under
 * `/firmware/50/parts/…`, which the app records with each download.
 *
 * Injected by MainActivity into store pages only (the bundled page never needs
 * it), so this file may run more than once — it installs itself once.
 */
(function () {
  'use strict';
  if (window.__attakyMergeInstalled) return;
  window.__attakyMergeInstalled = true;

  var bridge = window.AttakyUSB;
  if (!bridge || typeof bridge.downloadMerged !== 'function') return;

  var LABEL = 'Download (merged for flasher)';
  var BUSY_LABEL = 'Downloading…';
  var DONE_LABEL = 'Downloaded ✓ (merged for flasher)';
  var HINT = 'Downloads every part of this firmware and merges them for the flasher';
  var DONE_HINT = 'Already saved for the flasher — tap to download it again';
  var HERO = 'a[href="#download-section"]';
  var PART = 'a[href*="/firmware/"][href*="/parts/"]';
  var CARD = '.firmware-card';
  var CARD_IMAGE = '.firmware-card-image';
  var CHIP_CLASS = 'attaky-dl-chip';
  var NOTE_CLASS = 'attaky-dl-note';
  var DETAIL_ID = /firmware_details\/(\d+)/;
  var PART_ID = /\/firmware\/(\d+)\/parts\//;

  /** Firmware id → what the app saved for it: {name, size, added}. */
  var downloaded = {};

  /** Firmware id → true while its merged download is running. */
  var pending = {};

  // ------------------------------------------------------------ what is on the phone

  function readDownloads() {
    var list = [];
    try {
      list = JSON.parse(bridge.listFirmware() || '[]');
    } catch (e) {
      list = [];
    }
    var map = {};
    for (var i = 0; i < list.length; i++) {
      var entry = list[i];
      var id = entry && entry.firmwareId ? String(entry.firmwareId) : '';
      if (id && !map[id]) map[id] = entry;
    }
    downloaded = map;
  }

  function sizeLabel(bytes) {
    var value = Number(bytes) || 0;
    if (value <= 0) return '';
    if (value < 1024 * 1024) return Math.round(value / 1024) + ' KB';
    return (value / (1024 * 1024)).toFixed(1) + ' MB';
  }

  /** The firmware id of the page this script is running on, if it has one. */
  function pageFirmwareId() {
    var tag = document.querySelector('[data-firmware-id]');
    var fromTag = tag ? tag.getAttribute('data-firmware-id') : '';
    if (fromTag) return String(fromTag);
    var fromUrl = DETAIL_ID.exec(window.location.pathname || '');
    if (fromUrl) return fromUrl[1];
    var groups = parts();
    for (var i = 0; i < groups.length; i++) {
      var m = PART_ID.exec(groups[i].getAttribute('href') || '');
      if (m) return m[1];
    }
    return '';
  }

  function idOfHref(href) {
    var m = DETAIL_ID.exec(href || '');
    return m ? m[1] : '';
  }

  // ------------------------------------------------------------------- page parts

  function parts() {
    return Array.prototype.slice.call(document.querySelectorAll(PART));
  }

  function absolute(href) {
    try {
      return new URL(href, window.location.origin).href;
    } catch (e) {
      return href;
    }
  }

  function fileName(anchor) {
    var href = (anchor.getAttribute('href') || '').split('?')[0].split('#')[0];
    var tail = href.substring(href.lastIndexOf('/') + 1);
    try {
      return decodeURIComponent(tail);
    } catch (e) {
      return tail;
    }
  }

  /** The address in the part's row of the Download Files table, if listed. */
  function flashAddress(anchor) {
    var row = anchor.closest ? anchor.closest('tr') : null;
    if (!row) return null;
    var code = row.querySelector('td code') || row.querySelector('td');
    var m = code ? /0x([0-9a-f]+)/i.exec(code.textContent) : null;
    return m ? parseInt(m[1], 16) : null;
  }

  function versionOf(anchors) {
    for (var i = 0; i < anchors.length; i++) {
      var version = anchors[i].getAttribute('data-firmware-version');
      if (version) return version;
    }
    return '';
  }

  function title(anchors) {
    var heading = document.querySelector('h1');
    var name = heading ? heading.textContent.trim() : '';
    var combined = (name + ' ' + versionOf(anchors)).trim();
    if (combined) return combined;
    // No heading: fall back to the first part's name without its role token.
    var bits = anchors.length ? fileName(anchors[0]).replace(/\.bin$/i, '').split('-') : [];
    if (bits.length > 1) bits.pop();
    return bits.join('-');
  }

  /**
   * The firmware's own app icon, as the page shows it in the hero. The app keeps
   * it next to the merged image so the flasher's downloaded list can show which
   * app an image belongs to.
   */
  function iconUrl() {
    var img =
      document.querySelector('img.firmware-hero-icon') ||
      document.querySelector('.firmware-hero-icon') ||
      document.querySelector('.firmware-card-image img') ||
      document.querySelector('img.firmware-icon');
    var src = img ? img.getAttribute('src') || '' : '';
    return src ? absolute(src) : '';
  }

  function payload(anchors) {
    return {
      id: pageFirmwareId(),
      title: title(anchors),
      icon: iconUrl(),
      parts: anchors.map(function (a) {
        return {
          name: fileName(a),
          url: absolute(a.getAttribute('href')),
          offset: flashAddress(a),
        };
      }),
    };
  }

  // ------------------------------------------------------------------------ style

  function installStyle() {
    if (document.getElementById('attaky-store-style')) return;
    var style = document.createElement('style');
    style.id = 'attaky-store-style';
    style.textContent =
      '.' + CHIP_CLASS + '{position:absolute;top:8px;left:8px;z-index:3;' +
      'display:inline-flex;align-items:center;gap:4px;background:#FFFFFF;color:#141414;' +
      'border:1px solid #E5E5E5;border-radius:999px;padding:4px 9px;' +
      'font:600 10px/1 system-ui,-apple-system,sans-serif;letter-spacing:.06em;' +
      'text-transform:uppercase;box-shadow:0 1px 2px rgba(0,0,0,.10);pointer-events:none}' +
      '.' + NOTE_CLASS + '{display:block;margin-top:10px;' +
      'font:500 12px/1.45 system-ui,-apple-system,sans-serif;color:#5C5C5C}' +
      '.' + NOTE_CLASS + ' b{color:#141414;font-weight:600}';
    (document.head || document.documentElement).appendChild(style);
  }

  // ---------------------------------------------------------------- detail page

  function labelFor(id) {
    if (id && pending[id]) return BUSY_LABEL;
    if (id && downloaded[id]) return DONE_LABEL;
    return LABEL;
  }

  function hintFor(id) {
    return id && downloaded[id] ? DONE_HINT : HINT;
  }

  /** "Already on this phone as <name>" under the hero button. */
  function noteFor(id, cta) {
    var existing = cta.querySelector('.' + NOTE_CLASS);
    var entry = id ? downloaded[id] : null;
    if (!entry) {
      if (existing && existing.parentNode) existing.parentNode.removeChild(existing);
      return;
    }
    var note = existing;
    if (!note) {
      note = document.createElement('span');
      note.className = NOTE_CLASS;
      cta.appendChild(note);
    }
    var size = sizeLabel(entry.size);
    note.innerHTML =
      'Already on this phone as <b></b>' + (size ? ' · ' + size : '') + ' — tap to download it again';
    var nameTag = note.querySelector('b');
    if (nameTag) nameTag.textContent = entry.name || 'the merged image';
  }

  function markDetail() {
    var heroes = document.querySelectorAll(HERO);
    if (!heroes.length) return;
    var id = pageFirmwareId();
    for (var i = 0; i < heroes.length; i++) {
      var hero = heroes[i];
      var label = labelFor(id);
      // A page with no parts has nothing to merge; leave its button alone.
      if (!parts().length && !pending[id]) continue;
      if (hero.textContent.trim() !== label) hero.textContent = label;
      var hint = hintFor(id);
      if (hero.getAttribute('title') !== hint) hero.setAttribute('title', hint);
      if (hero.parentNode) noteFor(id, hero.parentNode);
    }
  }

  // -------------------------------------------------------------- listing cards

  function markCards() {
    var cards = document.querySelectorAll(CARD);
    for (var i = 0; i < cards.length; i++) {
      var card = cards[i];
      var link = card.querySelector('a[href*="firmware_details"]');
      var id = link ? idOfHref(link.getAttribute('href')) : '';
      var chip = card.querySelector('.' + CHIP_CLASS);
      if (id && downloaded[id]) {
        if (chip) continue;
        var image = card.querySelector(CARD_IMAGE) || card;
        if (window.getComputedStyle && window.getComputedStyle(image).position === 'static') {
          image.style.position = 'relative';
        }
        var badge = document.createElement('span');
        badge.className = CHIP_CLASS;
        badge.textContent = 'Downloaded';
        badge.setAttribute('title', 'Already saved for the flasher');
        image.appendChild(badge);
      } else if (chip && chip.parentNode) {
        chip.parentNode.removeChild(chip);
      }
    }
  }

  function mark() {
    installStyle();
    markDetail();
    markCards();
  }

  // ------------------------------------------------------------------------ click

  document.addEventListener(
    'click',
    function (event) {
      var target = event.target;
      if (!target || !target.closest) return;
      var hero = target.closest(HERO);
      if (!hero) return; // part rows keep their own one-file behaviour
      var group = parts();
      if (!group.length) return; // nothing to merge: let the page scroll

      event.preventDefault();
      event.stopPropagation();

      var request;
      try {
        request = payload(group);
      } catch (e) {
        window.alert('Could not read this firmware: ' + (e && e.message ? e.message : e));
        return;
      }

      var id = request.id;
      if (id) {
        pending[id] = true;
        mark();
        // If the app never reports back (a crash, a killed download), stop
        // claiming a download is running.
        window.setTimeout(function () {
          if (!pending[id]) return;
          delete pending[id];
          readDownloads();
          mark();
        }, 180000);
      }

      var result;
      try {
        result = bridge.downloadMerged(JSON.stringify(request));
      } catch (e) {
        result = 'error: ' + (e && e.message ? e.message : e);
      }
      if (result !== 'ok') {
        if (id) {
          delete pending[id];
          mark();
        }
        window.alert('Could not start the merged download: ' + result);
      }
    },
    true,
  );

  // --------------------------------------------------------------- app → page

  // A download finished (or the list changed): re-read what is on the phone and
  // redraw every mark. Keep any handler the page installed itself.
  var previousChanged =
    window.__attakyFirmware && typeof window.__attakyFirmware.onChanged === 'function'
      ? window.__attakyFirmware.onChanged
      : null;
  window.__attakyFirmware = window.__attakyFirmware || {};
  window.__attakyFirmware.onChanged = function () {
    pending = {};
    readDownloads();
    mark();
    if (previousChanged) previousChanged();
  };

  readDownloads();
  mark();

  if (window.MutationObserver && document.body) {
    var queued = false;
    new MutationObserver(function () {
      // Our own insertions mutate the page too; coalesce into one redraw.
      if (queued) return;
      queued = true;
      window.setTimeout(function () {
        queued = false;
        mark();
      }, 50);
    }).observe(document.body, { childList: true, subtree: true });
  }
})();
