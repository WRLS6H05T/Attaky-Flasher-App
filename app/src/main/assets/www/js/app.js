/**
 * The bundled offline page: a two-button menu.
 *
 * "Firmware store" hands back to the live store; "Native flasher" asks the app
 * to swap the WebView for the fully native flasher screen. Both go through the
 * `window.AttakyUSB` bridge. If the bridge is missing (for example this page was
 * opened outside the app), the store link still works and the native button
 * explains that it needs the app.
 */
(function () {
  'use strict';

  var STORE_URL = 'https://fwstore.attaky.com/en/';

  function bridge() {
    return (typeof window.AttakyUSB !== 'undefined' && window.AttakyUSB) || null;
  }

  function setHint(text) {
    var hint = document.getElementById('menuHint');
    if (hint) hint.textContent = text;
  }

  function openStore() {
    var api = bridge();
    if (api && typeof api.openStore === 'function') {
      api.openStore();
      return;
    }
    window.location.href = STORE_URL;
  }

  function openNative() {
    var api = bridge();
    if (api && typeof api.openNativeFlasher === 'function') {
      api.openNativeFlasher();
      return;
    }
    setHint('The native flasher only runs inside the Attaky Flasher app.');
  }

  function wire() {
    var store = document.getElementById('btnStore');
    var native = document.getElementById('btnNative');
    if (store) store.addEventListener('click', openStore);
    if (native) native.addEventListener('click', openNative);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', wire);
  } else {
    wire();
  }
})();
