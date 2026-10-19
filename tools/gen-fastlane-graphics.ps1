# gen-fastlane-graphics.ps1 — builds the F-Droid / fastlane listing graphics.
#
# Produces:
#   fastlane/metadata/android/en-US/images/icon.png           512x512
#   fastlane/metadata/android/en-US/images/featureGraphic.png 1024x500
#
# The icon reuses the launcher design (green chip + bolt on the Attaky dark
# background) so the store listing matches the installed app icon.
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$out  = "$root\fastlane\metadata\android\en-US\images"
New-Item -ItemType Directory -Force -Path $out | Out-Null

$bg    = [System.Drawing.Color]::FromArgb(255, 10, 15, 12)
$green = [System.Drawing.Color]::FromArgb(255, 0, 255, 0)
$ink   = [System.Drawing.Color]::FromArgb(255, 240, 240, 240)

function Draw-Icon($g, $x, $y, $size) {
  $s = [float]$size
  $brushG = New-Object System.Drawing.SolidBrush($green)
  $brushB = New-Object System.Drawing.SolidBrush($bg)
  $g.FillRectangle($brushG, $x + 0.38*$s, $y + 0.30*$s, 0.24*$s, 0.40*$s)
  $g.FillRectangle($brushG, $x + 0.36*$s, $y + 0.20*$s, 0.05*$s, 0.12*$s)
  $g.FillRectangle($brushG, $x + 0.59*$s, $y + 0.20*$s, 0.05*$s, 0.12*$s)
  $g.FillRectangle($brushG, $x + 0.36*$s, $y + 0.68*$s, 0.05*$s, 0.12*$s)
  $g.FillRectangle($brushG, $x + 0.59*$s, $y + 0.68*$s, 0.05*$s, 0.12*$s)
  $g.FillRectangle($brushG, $x + 0.20*$s, $y + 0.36*$s, 0.12*$s, 0.05*$s)
  $g.FillRectangle($brushG, $x + 0.20*$s, $y + 0.59*$s, 0.12*$s, 0.05*$s)
  $g.FillRectangle($brushG, $x + 0.68*$s, $y + 0.36*$s, 0.12*$s, 0.05*$s)
  $g.FillRectangle($brushG, $x + 0.68*$s, $y + 0.59*$s, 0.12*$s, 0.05*$s)
  $pts = [System.Drawing.PointF[]]@(
    [System.Drawing.PointF]::new([float]($x + 0.52*$s), [float]($y + 0.34*$s)),
    [System.Drawing.PointF]::new([float]($x + 0.41*$s), [float]($y + 0.53*$s)),
    [System.Drawing.PointF]::new([float]([Math]::Round($x + 0.49*$s)), [float]($y + 0.53*$s)),
    [System.Drawing.PointF]::new([float](($x + 0.44*$s)), [float]($y + 0.67*$s)),
    [System.Drawing.PointF]::new([float]($x + 0.60*$s), [float]($y + 0.47*$s)),
    [System.Drawing.PointF]::new([float]($x + 0.52*$s), [float]($y + 0.47*$s))
  )
  $g.FillPolygon($brushB, $pts)
  $brushG.Dispose(); $brushB.Dispose()
}

function Pick-Font($size, $style) {
  foreach ($name in @('Segoe UI', 'Arial', 'Helvetica')) {
    try { return New-Object System.Drawing.Font($name, $size, $style, [System.Drawing.GraphicsUnit]::Pixel) } catch {}
  }
  return New-Object System.Drawing.Font([System.Drawing.FontFamily]::GenericSansSerif, $size, $style, [System.Drawing.GraphicsUnit]::Pixel)
}

# ---- icon.png (512x512) -------------------------------------------------
$icon = New-Object System.Drawing.Bitmap(512, 512, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$gi = [System.Drawing.Graphics]::FromImage($icon)
$gi.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$gi.Clear($bg)
Draw-Icon $gi 0 0 512
$gi.Dispose()
$icon.Save("$out\icon.png", [System.Drawing.Imaging.ImageFormat]::Png)
$icon.Dispose()
"wrote icon.png (512x512)"

# ---- featureGraphic.png (1024x500) -------------------------------------
$fg = New-Object System.Drawing.Bitmap(1024, 500, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($fg)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
$g.Clear($bg)

# green hairline frame
$pen = New-Object System.Drawing.Pen($green, 3)
$g.DrawRectangle($pen, 12, 12, 1000, 476)
$pen.Dispose()

# icon at the left
Draw-Icon $g 110 110 280

# wordmark
$fSmall = Pick-Font 40 ([System.Drawing.FontStyle]::Regular)
$fBig   = Pick-Font 96 ([System.Drawing.FontStyle]::Bold)
$fTag   = Pick-Font 30 ([System.Drawing.FontStyle]::Regular)
$inkBrush = New-Object System.Drawing.SolidBrush($ink)
$greenBrush = New-Object System.Drawing.SolidBrush($green)
$g.DrawString("ATTAKY", $fSmall, $greenBrush, 430, 140)
$g.DrawString("FLASHER", $fBig, $inkBrush, 424, 190)
$g.DrawString("USB firmware flasher for the ESP32-S3 deck", $fTag, $inkBrush, 430, 310)

$fSmall.Dispose(); $fBig.Dispose(); $fTag.Dispose(); $inkBrush.Dispose(); $greenBrush.Dispose()
$g.Dispose()
$fg.Save("$out\featureGraphic.png", [System.Drawing.Imaging.ImageFormat]::Png)
$fg.Dispose()
"wrote featureGraphic.png (1024x500)"
