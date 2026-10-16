param([string]$Ffmpeg)
$ErrorActionPreference = 'Stop'
$tvRoot = Split-Path -Parent $PSScriptRoot
if (!$Ffmpeg) { $Ffmpeg = Join-Path (Split-Path $tvRoot) 'IPTV Player/.tools/ffmpeg/ffmpeg.exe' }
if (!(Test-Path $Ffmpeg)) { throw 'Pass a local ffmpeg executable using -Ffmpeg.' }
& $Ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=640x360:rate=25' -f lavfi -i 'sine=frequency=440:sample_rate=48000' -t 20 -c:v libx264 -preset ultrafast -crf 30 -pix_fmt yuv420p -c:a aac -b:a 64k -movflags +faststart -y "$tvRoot/app/src/androidTest/assets/fixture.mp4"
if ($LASTEXITCODE) { throw 'Fixture generation failed' }

