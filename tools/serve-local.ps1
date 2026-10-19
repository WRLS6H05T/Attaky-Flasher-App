# serve-local.ps1 — preview the flasher page in a desktop browser.
# Note: desktop Chrome has real Web Serial / WebUSB, but the page expects the
# Android `window.AttakyUSB` bridge. Without it you can still review layout and
# the console banner ("No Web Serial bridge").
param(
  [int]$Port = 8899
)
$root = Split-Path -Parent $PSScriptRoot
$www = Join-Path $root "app\src\main\assets\www"
Write-Host "Serving $www at http://localhost:$Port"
python -m http.server $Port --directory $www