# Generate huyi extension icons (rounded gradient tile + Chinese char U+72D0 "hu" fox)
# ASCII-only script: PS 5.1 reads BOM-less files as ANSI, so the glyph is built from its code point.
param([string]$OutDir = "$PSScriptRoot\..\extension\icons")

Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force $OutDir | Out-Null
$glyph = [string][char]0x72D0

function New-RoundedPath([single]$w, [single]$h, [single]$r) {
  $p = New-Object System.Drawing.Drawing2D.GraphicsPath
  $d = $r * 2
  $p.AddArc(0, 0, $d, $d, 180, 90)
  $p.AddArc($w - $d, 0, $d, $d, 270, 90)
  $p.AddArc($w - $d, $h - $d, $d, $d, 0, 90)
  $p.AddArc(0, $h - $d, $d, $d, 90, 90)
  $p.CloseFigure()
  return $p
}

foreach ($size in 16, 32, 48, 128) {
  $bmp = New-Object System.Drawing.Bitmap($size, $size)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
  $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
  $g.Clear([System.Drawing.Color]::Transparent)

  $rect = New-Object System.Drawing.Rectangle(0, 0, $size, $size)
  $c1 = [System.Drawing.Color]::FromArgb(255, 96, 165, 250)   # #60a5fa
  $c2 = [System.Drawing.Color]::FromArgb(255, 99, 102, 241)   # #6366f1
  $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, $c1, $c2, [single]45)
  $path = New-RoundedPath ([single]$size) ([single]$size) ([single]($size * 0.22))
  $g.FillPath($brush, $path)

  $fontSize = [single]($size * 0.60)
  $font = New-Object System.Drawing.Font("Microsoft YaHei", $fontSize, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
  $fmt = New-Object System.Drawing.StringFormat
  $fmt.Alignment = [System.Drawing.StringAlignment]::Center
  $fmt.LineAlignment = [System.Drawing.StringAlignment]::Center
  $textRect = New-Object System.Drawing.RectangleF(0, [single]($size * 0.02), $size, $size)
  $g.DrawString($glyph, $font, [System.Drawing.Brushes]::White, $textRect, $fmt)

  $out = Join-Path $OutDir ("icon{0}.png" -f $size)
  $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
  $g.Dispose(); $bmp.Dispose(); $font.Dispose(); $brush.Dispose(); $path.Dispose()
  Write-Host "wrote $out"
}
