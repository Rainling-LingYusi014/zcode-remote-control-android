# 从手绘正方形底稿生成分辨率启动器图标（底稿与图标图案版权归作者，见 README）
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File design\make-icon.ps1 [-Src <新底稿源图>]
# 产出：design/icon-master.png（1024 底稿，-Src 时刷新）、res/mipmap-*/ic_launcher.png（legacy 全出血）、
#       ic_launcher_fg.png（自适应前景：整幅图案缩进 61% 安全区居中，掩膜裁不到边框与文字）。
param([string]$Src)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$root = Split-Path -Parent $PSScriptRoot
$masterPath = Join-Path $PSScriptRoot 'icon-master.png'

function New-Square([System.Drawing.Image]$src, [int]$side) {
    # 居中裁方 → 缩放到 side×side
    $w = $src.Width; $h = $src.Height
    $cw = [Math]::Min($w, $h)
    $sx = [int](($w - $cw) / 2); $sy = [int](($h - $cw) / 2)
    $bmp = New-Object System.Drawing.Bitmap($side, $side)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $drect = New-Object System.Drawing.Rectangle(0, 0, $side, $side)
    $srect = New-Object System.Drawing.Rectangle($sx, $sy, $cw, $cw)
    $g.DrawImage($src, $drect, $srect, [System.Drawing.GraphicsUnit]::Pixel)
    $g.Dispose()
    return $bmp
}

if ($Src) {
    $srcImg = [System.Drawing.Image]::FromFile($Src)
    $m = New-Square $srcImg 1024
    $srcImg.Dispose()
    $m.Save($masterPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $m.Dispose()
    Write-Host "master refreshed <- $Src"
}

$master = [System.Drawing.Image]::FromFile($masterPath)

function Save-Flat([int]$size, [string]$path) {
    # legacy：底稿全出血铺满
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.DrawImage($master, 0, 0, $size, $size)
    $g.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}
function Save-Fg([int]$size, [string]$path) {
    # 自适应前景：透明画布上把整幅图案缩到 61%（66/108 安全区）居中
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)
    $art = [int][Math]::Round($size * 0.61)
    $off = [int](($size - $art) / 2)
    $g.DrawImage($master, $off, $off, $art, $art)
    $g.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

$pairs = @(@("mdpi", 48, 108), @("hdpi", 72, 162), @("xhdpi", 96, 216), @("xxhdpi", 144, 324), @("xxxhdpi", 192, 432))
foreach ($p in $pairs) {
    $dir = Join-Path $root ("res\mipmap-" + $p[0])
    Save-Flat $p[1] (Join-Path $dir 'ic_launcher.png')
    Save-Fg $p[2] (Join-Path $dir 'ic_launcher_fg.png')
}

# 报告底稿角点色，供 colors.xml 的 ic_launcher_bg 对齐（消除自适应图标的贴缝）
$probe = New-Object System.Drawing.Bitmap($masterPath)
$c = $probe.GetPixel(3, 3)
$probe.Dispose()
Write-Host ("corner=#{0:X2}{1:X2}{2:X2}" -f $c.R, $c.G, $c.B)
$master.Dispose()
Write-Host 'ICON_OK'
