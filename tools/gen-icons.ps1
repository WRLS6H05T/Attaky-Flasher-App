# gen-icons.ps1 — stamps the launcher PNGs for API < 26 (no adaptive icons there).
# Design matches res/drawable/ic_launcher_foreground.xml: green chip + bolt.
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$pkg  = "$root\app\src\main\res"

$densities = @{
  "mipmap-mdpi"    = 48
  "mipmap-hdpi"    = 72
  "mipmap-xhdpi"   = 96
  "mipmap-xxhdpi"  = 144
  "mipmap-xxxhdpi" = 192
}

function New-Icon($size) {
  $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
  $g.Clear([System.Drawing.Color]::FromArgb(255, 10, 15, 12))

  $green = [System.Drawing.Color]::FromArgb(255, 0, 255, 0)
  $black = [System.Drawing.Color]::FromArgb(255, 0, 0, 0)
  $bg    = [System.Drawing.Color]::FromArgb(255, 10, 15, 12)
  $brushG = New-Object System.Drawing.SolidBrush($green)
  $brushB = New-Object System.Drawing.SolidBrush($black)

  $s = [float]$size
  # chip body
  $g.FillRectangle($brushG, 0.38*$s, 0.30*$s, 0.24*$s, 0.40*$s)
  # top pins
  $g.FillRectangle($brushG, 0.36*$s, 0.20*$s, 0.05*$s, 0.12*$s)
  $g.FillRectangle($brushG, 0.59*$s, 0.20*$s, 0.05*$s, 0.12*$s)
  # bottom pins
  $g.FillRectangle($brushG, 0.36*$s, 0.68*$s, 0.05*$s, 0.12*$s)
  $g.FillRectangle($brushG, 0.59*$s, 0.68*$s, 0.05*$s, 0.12*$s)
  # left pins
  $g.FillRectangle($brushG, 0.20*$s, 0.36*$s, 0.12*$s, 0.05*$s)
  $g.FillRectangle($brushG, 0.20*$s, 0.59*$s, 0.12*$s, 0.05*$s)
  # right pins
  $g.FillRectangle($brushG, 0.68*$s, 0.36*$s, 0.12*$s, 0.05*$s)
  $g.FillRectangle($brushG, 0.68*$s, 0.59*$s, 0.12*$s, 0.05*$s)
  # bolt
  $pts = [System.Drawing.PointF[]]@(
    [System.Drawing.PointF]::new([float](0.52 * $s), [float](0.34 * $s)),
    [System.Drawing.PointF]::new([float](0.41 * $s), [float](0.53 * $s)),
    [System.Drawing.PointF]::new([float](0.49 * $s), [float](0.53 * $s)),
    [System.Drawing.PointF]::new([float](0.44 * $s), [float](0.67 * $s)),
    [System.Drawing.PointF]::new([float](0.60 * $s), [float](0.47 * $s)),
    [System.Drawing.PointF]::new([float](0.52 * $s), [float](0.47 * $s))
  )
  $g.FillPolygon($brushB, $pts)

  $brushG.Dispose(); $brushB.Dispose(); $g.Dispose()
  return $bmp
}

foreach ($entry in $densities.GetEnumerator()) {
  $dir = "$pkg\$($entry.Key)"
  if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
  $bmp = New-Icon $entry.Value
  $bmp.Save("$dir\ic_launcher.png", [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Save("$dir\ic_launcher_round.png", [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()
  Write-Host "wrote $($entry.Key) ($($entry.Value)px)"
}