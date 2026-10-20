# 生成发行版启动器图标（ZCode RC "Remote Control" 插头线缆标志）
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File design\make-icon.ps1
# 产出：res/mipmap-*/ic_launcher.png（legacy 全出血）、ic_launcher_fg.png（自适应前景，透明底）
#       design/icon-master.png（1024 底稿）。背景色 #0E0F10 由 res/values/colors.xml ic_launcher_bg 提供。
Add-Type -AssemblyName System.Drawing
$root  = "E:\.ZCode\zremote"
$script:bgC   = [System.Drawing.Color]::FromArgb(255,14,15,16)    # ZCode 官方图标实测黑
$script:redC  = [System.Drawing.Color]::FromArgb(255,229,72,77)   # 线缆/双针
$script:whtC  = [System.Drawing.Color]::White                     # 插头体/接头
$script:grayC = [System.Drawing.Color]::FromArgb(255,199,204,211) # 文字

function RoundRect([float]$x,[float]$y,[float]$w,[float]$h,[float]$r) {
  $p = New-Object System.Drawing.Drawing2D.GraphicsPath
  $d = $r*2
  $p.AddArc($x, $y, $d, $d, 180, 90)
  $p.AddArc($x+$w-$d, $y, $d, $d, 270, 90)
  $p.AddArc($x+$w-$d, $y+$h-$d, $d, $d, 0, 90)
  $p.AddArc($x, $y+$h-$d, $d, $d, 90, 90)
  $p.CloseFigure()
  return $p
}

function New-DesignMaster([bool]$transparent) {
  $bmp = New-Object System.Drawing.Bitmap(1024,1024)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
  $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
  if ($transparent) { $g.Clear([System.Drawing.Color]::Transparent) }
  else { $g.Clear($script:bgC) }

  # 主图案：绕自身中心放大 10%（定稿 v2）
  $cx=507.0; $cy=482.5
  $g.TranslateTransform($cx,$cy); $g.ScaleTransform(1.1,1.1); $g.TranslateTransform(-$cx,-$cy)

  $pen = New-Object System.Drawing.Pen($script:redC, 46)
  $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
  $pen.EndCap   = [System.Drawing.Drawing2D.LineCap]::Round
  $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
  $loopC = New-Object System.Drawing.PointF(530, 560); $loopR = 140
  $p1 = New-Object System.Drawing.Drawing2D.GraphicsPath
  $p1.AddBezier(280,415, 280,510, 300,560, ($loopC.X-$loopR),560)
  $g.DrawPath($pen, $p1); $p1.Dispose()
  $g.DrawArc($pen, ($loopC.X-$loopR),($loopC.Y-$loopR),($loopR*2),($loopR*2), 0, 360)
  $seX = $loopC.X + $loopR*0.707; $seY = $loopC.Y + $loopR*0.707
  $p2 = New-Object System.Drawing.Drawing2D.GraphicsPath
  $p2.AddBezier($seX,$seY, 690,706, 760,600, 760,672)
  $g.DrawPath($pen, $p2); $p2.Dispose()
  $pen.Dispose()

  foreach ($px in @(245,315)) {
    $pp = RoundRect ($px-16) 175 32 95 14
    $b = New-Object System.Drawing.SolidBrush($script:redC)
    $g.FillPath($b, $pp); $b.Dispose(); $pp.Dispose()
  }
  foreach ($def in @(@(200,268,160,118,36,$script:whtC), @(252,386,56,40,14,$script:whtC), `
                     @(706,640,108,150,32,$script:whtC), @(752,668,16,94,8,$script:bgC))) {
    $rr = RoundRect $def[0] $def[1] $def[2] $def[3] $def[4]
    $b = New-Object System.Drawing.SolidBrush($def[5])
    $g.FillPath($b, $rr); $b.Dispose(); $rr.Dispose()
  }
  $g.ResetTransform()

  # 文字：108px 右下角（定稿 v2，允许轻压图案）
  $font = New-Object System.Drawing.Font("Segoe UI Semibold", 108, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
  $b = New-Object System.Drawing.SolidBrush($script:grayC)
  $sz = $g.MeasureString("Remote Control", $font)
  $g.DrawString("Remote Control", $font, $b, (1024 - 64 - $sz.Width), (1024 - 64 - $sz.Height))
  $b.Dispose(); $font.Dispose()
  $g.Dispose()
  return $bmp
}

$master  = New-DesignMaster $false
$masterF = New-DesignMaster $true
$master.Save("$root\design\icon-master.png", [System.Drawing.Imaging.ImageFormat]::Png)

# 密度表：legacy 边px, 前景画布px（108dp）
$pairs = @(@("mdpi",48,108), @("hdpi",72,162), @("xhdpi",96,216), @("xxhdpi",144,324), @("xxxhdpi",192,432))
foreach ($p in $pairs) {
  $d=$p[0]; $ls=$p[1]; $fs=$p[2]
  # legacy：整图缩放
  $o = New-Object System.Drawing.Bitmap($ls,$ls)
  $og = [System.Drawing.Graphics]::FromImage($o)
  $og.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $og.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
  $og.DrawImage($master, 0, 0, $ls, $ls)
  $o.Save("$root\res\mipmap-$d\ic_launcher.png", [System.Drawing.Imaging.ImageFormat]::Png)
  $og.Dispose(); $o.Dispose()
  # 前景：透明底稿缩放至内容高≈70/108 并按内容包围盒居中
  $f = New-Object System.Drawing.Bitmap($fs,$fs)
  $fg = [System.Drawing.Graphics]::FromImage($f)
  $fg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $fg.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
  $side = [int]($fs*0.8132)
  $off  = [int](($fs-$side)/2)
  $offX = [int]($off - 0.0288*$side)   # 内容包围盒中心 (541.5,552) 校正回画布中心
  $offY = [int]($off - 0.0390*$side)
  $fg.DrawImage($masterF, $offX, $offY, $side, $side)
  $f.Save("$root\res\mipmap-$d\ic_launcher_fg.png", [System.Drawing.Imaging.ImageFormat]::Png)
  $fg.Dispose(); $f.Dispose()
}
$master.Dispose(); $masterF.Dispose()
Write-Host "ICON_OK"
