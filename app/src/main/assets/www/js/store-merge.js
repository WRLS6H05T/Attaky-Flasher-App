/*
 * store-merge.js — one merged download for the flasher, from the top of a
 * firmware page.
 *
 * A firmware page's hero carries a "Go to Download" button that jumps to the
 * "Download Files" table, where each part has its own row and Download link.
 * Those rows are left exactly as they are: tapping one still saves that single
 * part.
 *
 * The hero button is the merged download instead — "Download (merged for
 * flasher)". It takes every part of the firmware on the page, at the flash
 * address the table lists for it, and the app joins them into one image ready to
 * flash offline.
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
  var HINT = 'Downloads every part of this firmware and merges them for the flasher';
  var HERO = 'a[href="#download-section"]';
  var PART = 'a[href*="/firmware/"][href*="/parts/"]';

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

  function payload(anchors) {
    return {
      title: title(anchors),
      parts: anchors.map(function (a) {
        return {
          name: fileName(a),
          url: absolute(a.getAttribute('href')),
          offset: flashAddress(a),
        };
      }),
    };
  }

  function relabel() {
    // A page with no parts has nothing to merge; leave its button alone.
    if (!parts().length) return;
    var heroes = document.querySelectorAll(HERO);
    for (var i = 0; i < heroes.length; i++) {
      if (heroes[i].textContent.trim() !== LABEL) heroes[i].textContent = LABEL;
      if (heroes[i].getAttribute('title') !== HINT) heroes[i].setAttribute('title', HINT);
    }
  }

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

      var result;
      try {
        result = bridge.downloadMerged(JSON.stringify(payload(group)));
      } catch (e) {
        result = 'error: ' + (e && e.message ? e.message : e);
      }
      if (result !== 'ok') window.alert('Could not start the merged download: ' + result);
    },
    true,
  );

  relabel();
  if (window.MutationObserver && document.body) {
    new MutationObserver(relabel).observe(document.body, { childList: true, subtree: true });
  }
})();
