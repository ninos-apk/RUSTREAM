# Export the supplied logo into Android resource sizes without changing the original artwork.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$brandRoot = Split-Path -Parent $PSScriptRoot
$resources = Join-Path $brandRoot 'app/src/main/res'
$sourceLogo = Join-Path $brandRoot 'app/src/assets/logo/new_logo_dark.png'
$brandColors = [xml](Get-Content -Raw (Join-Path $resources 'values/branding.xml'))
function Get-BrandColor([string]$Name) {
    $value = $brandColors.resources.color | Where-Object name -eq $Name
    if (!$value) { throw "Branding color missing: $Name" }
    return [System.Drawing.ColorTranslator]::FromHtml($value.InnerText)
}
$brandBackground = Get-BrandColor 'branding_background'
$brandText = Get-BrandColor 'branding_text'
$brandAccent = Get-BrandColor 'branding_accent'
$logo = [System.Drawing.Image]::FromFile($sourceLogo)
function Export-BrandImage([string]$Relative, [int]$Width, [int]$Height, [scriptblock]$Paint) {
    $destination = Join-Path $resources $Relative
    New-Item -ItemType Directory -Force (Split-Path -Parent $destination) | Out-Null
    $bitmap = [System.Drawing.Bitmap]::new($Width,$Height)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
        & $Paint $graphics $Width $Height
        $bitmap.Save($destination,[System.Drawing.Imaging.ImageFormat]::Png)
    } finally { $graphics.Dispose(); $bitmap.Dispose() }
}
try {
    Export-BrandImage 'drawable-nodpi/rustream_logo.png' 512 512 { param($g,$w,$h)
        $g.DrawImage($logo,[System.Drawing.Rectangle]::new(0,0,$w,$h))
    }
    foreach($density in @(@('mdpi',80),@('hdpi',120),@('xhdpi',160),@('xxhdpi',240),@('xxxhdpi',320))) {
        Export-BrandImage "mipmap-$($density[0])/ic_launcher.png" $density[1] $density[1] { param($g,$w,$h)
            $g.Clear($brandBackground)
            $g.DrawImage($logo,[System.Drawing.Rectangle]::new(0,0,$w,$h))
        }
    }
    # A 60dp image in the 108dp foreground keeps the complete TV motif inside launcher masks.
    Export-BrandImage 'drawable-xxxhdpi/ic_launcher_foreground.png' 432 432 { param($g,$w,$h)
        $g.Clear([System.Drawing.Color]::Transparent)
        $g.DrawImage($logo,[System.Drawing.Rectangle]::new(96,96,240,240))
    }
    foreach($density in @(@('mdpi',160,90),@('hdpi',240,135),@('xhdpi',320,180),@('xxhdpi',480,270),@('xxxhdpi',640,360))) {
        Export-BrandImage "drawable-$($density[0])/tv_banner.png" $density[1] $density[2] { param($g,$w,$h)
            $scale=$w/320.0
            $g.Clear($brandBackground)
            $g.DrawImage($logo,[System.Drawing.RectangleF]::new(8*$scale,8*$scale,164*$scale,164*$scale))
            $titleBrush=[System.Drawing.SolidBrush]::new($brandText)
            $subtitleBrush=[System.Drawing.SolidBrush]::new($brandAccent)
            $titleFont=[System.Drawing.Font]::new('Segoe UI',[single](19*$scale),[System.Drawing.FontStyle]::Bold,[System.Drawing.GraphicsUnit]::Pixel)
            $subtitleFont=[System.Drawing.Font]::new('Segoe UI',[single](11*$scale),[System.Drawing.FontStyle]::Regular,[System.Drawing.GraphicsUnit]::Pixel)
            try {
                $g.DrawString('RUSTREAM',$titleFont,$titleBrush,[single](181*$scale),[single](51*$scale))
                $g.DrawString('IPTV',$titleFont,$titleBrush,[single](181*$scale),[single](77*$scale))
                $g.DrawString('Powered by Rust',$subtitleFont,$subtitleBrush,[single](182*$scale),[single](113*$scale))
            } finally { $titleBrush.Dispose(); $subtitleBrush.Dispose(); $titleFont.Dispose(); $subtitleFont.Dispose() }
        }
    }
} finally { $logo.Dispose() }
Write-Host 'RUSTREAM IPTV launcher icons, TV banners and in-app logo exported.'
