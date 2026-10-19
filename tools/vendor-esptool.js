#!/usr/bin/env node
/*
 * vendor-esptool.js — re-vendors the esptool-js browser bundle for the Attaky
 * Flasher web page.
 *
 * esptool-js ships a single-file ESM bundle (`bundle.js`) that ends in one
 * `export { ... }` statement. Android WebView ES-module loading depends on the
 * server reporting the right MIME type, and the WebViewAssetLoader cannot always
 * be trusted to do that, so we rewrite the trailing `export` into a plain
 * `window.esptool = { ... }` assignment wrapped in an IIFE that preserves ES
 * module strict-mode semantics. The result is a classic script that works from
 * `file://` and from `https://appassets.androidplatform.net/` alike.
 *
 * Usage:
 *   node tools/vendor-esptool.js <input-bundle.js> <output.js>
 *
 * To refresh from npm:
 *   curl -L https://unpkg.com/esptool-js@0.7.0/bundle.js -o tools/esptool-js.bundle.esm.js
 *   node tools/vendor-esptool.js tools/esptool-js.bundle.esm.js \
 *        app/src/main/assets/www/vendor/esptool-js.js
 */

"use strict";

const fs = require("fs");
const path = require("path");

const [, , inputArg, outputArg] = process.argv;
if (!inputArg || !outputArg) {
  console.error("usage: node tools/vendor-esptool.js <input.esm.js> <output.js>");
  process.exit(2);
}

const input = path.resolve(inputArg);
const output = path.resolve(outputArg);

const src = fs.readFileSync(input, "utf8");

// Find the single, final `export { ... };` statement.
const marker = src.lastIndexOf("export{");
if (marker < 0) {
  console.error("No `export{` statement found — is this really the esptool-js ESM bundle?");
  process.exit(1);
}

const head = src.slice(0, marker);
const tail = src.slice(marker);

const match = tail.match(/^export\{([\s\S]*?)\};?\s*$/);
if (!match) {
  console.error("Could not parse the trailing export statement.");
  process.exit(1);
}

const pairs = match[1]
  .split(",")
  .map((s) => s.trim())
  .filter(Boolean)
  .map((spec) => {
    // "local as Public"  ->  Public: local
    // "foo"              ->  foo: foo
    const m = spec.match(/^(\S+)\s+(?:as\s+)?(\S+)$/);
    if (m) return `${m[2]}:${m[1]}`;
    return `${spec}:${spec}`;
  });

const banner =
  "/* esptool-js (Apache-2.0) — vendored as a classic script. See vendor/esptool-js.LICENSE.txt. */\n";

const out =
  banner +
  '(function(){"use strict";\n' +
  head +
  `\nwindow.esptool={${pairs.join(",")}};\n` +
  "})();\n";

fs.writeFileSync(output, out, "utf8");
console.log(`wrote ${output} (${out.length} bytes, ${pairs.length} exports)`);
console.log("exports:", pairs.map((p) => p.split(":")[0]).join(", "));
