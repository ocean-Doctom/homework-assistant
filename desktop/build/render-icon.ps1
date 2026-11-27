Add-Type -AssemblyName System.Drawing

$s = 1024
$bmp = New-Object System.Drawing.Bitmap $s, $s
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$g.Clear([System.Drawing.Color]::Transparent)

function New-RoundRect([single]$x, [single]$y, [single]$w, [single]$h, [single]$r) {
  $path = New-Object System.Drawing.Drawing2D.GraphicsPath
  $d = $r * 2
  $path.AddArc($x, $y, $d, $d, 180, 90) | Out-Null
  $path.AddArc(($x + $w - $d), $y, $d, $d, 270, 90) | Out-Null
  $path.AddArc(($x + $w - $d), ($y + $h - $d), $d, $d, 0, 90) | Out-Null
  $path.AddArc($x, ($y + $h - $d), $d, $d, 90, 90) | Out-Null
  $path.CloseFigure()
  return $path
}

function Fill-Local($graphics, $brush, [single]$rotation, [single]$ox, [single]$oy, [single]$x, [single]$y, [single]$w, [single]$h, [single]$r) {
  $path = New-RoundRect $x $y $w $h $r
  $matrix = New-Object System.Drawing.Drawing2D.Matrix
  $matrix.Rotate($rotation)
  $matrix.Translate($ox, $oy, [System.Drawing.Drawing2D.MatrixOrder]::Append)
  $path.Transform($matrix)
  $graphics.FillPath($brush, $path)
  $path.Dispose()
  $matrix.Dispose()
}

$white = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
$ink = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 28, 25, 23))

# Leaning book, then the upright book. Bottoms share one line.
Fill-Local $g $ink 20 416 748 -172 -418 154 418 28
Fill-Local $g $ink 0 612 748 -78 -460 156 460 28

function Save-Mark {
  $mark = Join-Path $PSScriptRoot "book-mark.png"
  $bmp.Save($mark, [System.Drawing.Imaging.ImageFormat]::Png)
}

Save-Mark

$tile = New-RoundRect 0 0 1024 1024 228
$g.FillPath($white, $tile)
$tile.Dispose()
Fill-Local $g $ink 20 416 748 -172 -418 154 418 28
Fill-Local $g $ink 0 612 748 -78 -460 156 460 28

$out = Join-Path $PSScriptRoot "icon.png"
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose()
$bmp.Dispose()
$white.Dispose()
$ink.Dispose()
Write-Output $out
