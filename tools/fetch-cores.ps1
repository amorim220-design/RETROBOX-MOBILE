# Baixa os núcleos libretro do buildbot oficial e coloca em app/src/main/jniLibs/<abi>.
# Uso: powershell -ExecutionPolicy Bypass -File tools\fetch-cores.ps1 -Abi arm64-v8a
param(
    [ValidateSet("arm64-v8a", "x86_64", "armeabi-v7a")]
    [string]$Abi = "arm64-v8a"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$dest = Join-Path $root "app\src\main\jniLibs\$Abi"
$tmp = Join-Path $env:TEMP "retrobox-cores-$Abi"
New-Item -ItemType Directory -Force -Path $dest, $tmp | Out-Null

# apk name -> library name inside the APK
$cores = [ordered]@{
    "fceumm"                  = "libfceumm_libretro.so"
    "snes9x2005_plus"         = "libsnes9x2005_plus_libretro.so"
    "gambatte"                = "libgambatte_libretro.so"
    "mgba"                    = "libmgba_libretro.so"
    "genesis_plus_gx"         = "libgenesis_plus_gx_libretro.so"
    "pcsx_rearmed"            = "libpcsx_rearmed_libretro.so"
    "mupen64plus_next_gles3"  = "libmupen64plus_next_gles3_libretro.so"
    "melonds"                 = "libmelonds_libretro.so"
    "ppsspp"                  = "libppsspp_libretro.so"
}

$base = "https://buildbot.libretro.com/nightly/android/latest/$Abi"

foreach ($core in $cores.Keys) {
    $zip = Join-Path $tmp "$core.zip"
    $url = "$base/${core}_libretro_android.so.zip"
    Write-Host "Baixando $core..."
    Invoke-WebRequest -Uri $url -OutFile $zip
    $out = Join-Path $tmp $core
    Expand-Archive -Path $zip -DestinationPath $out -Force
    $so = Get-ChildItem $out -Filter *.so | Select-Object -First 1
    if (-not $so) { throw "Nenhum .so em $zip" }
    Copy-Item $so.FullName (Join-Path $dest $cores[$core]) -Force
}

Get-ChildItem $dest | Select-Object Name, @{n = "MB"; e = { [math]::Round($_.Length / 1MB, 1) } }
