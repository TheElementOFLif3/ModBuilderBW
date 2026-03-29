param(
    [Parameter(Mandatory = $true)]
    [string[]]$Files,
    [string]$PfxPath = $env:WINDOWS_CERTIFICATE_PFX_PATH,
    [string]$PfxPassword = $env:WINDOWS_CERTIFICATE_PASSWORD,
    [string]$Thumbprint = $env:WINDOWS_CERTIFICATE_THUMBPRINT,
    [string]$TimestampUrl = $(if ($env:WINDOWS_TIMESTAMP_URL) { $env:WINDOWS_TIMESTAMP_URL } else { 'http://timestamp.digicert.com' })
)

$ErrorActionPreference = 'Stop'

function Find-SignTool {
    $candidates = @(
        "$env:ProgramFiles (x86)\Windows Kits\10\bin\x64\signtool.exe",
        "$env:ProgramFiles\Windows Kits\10\bin\x64\signtool.exe"
    )

    $dynamic = Get-ChildItem "$env:ProgramFiles (x86)\Windows Kits\10\bin" -Filter signtool.exe -Recurse -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending |
        Select-Object -ExpandProperty FullName
    $candidates += $dynamic

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) {
            return $candidate
        }
    }

    throw 'signtool.exe not found. Install Windows SDK or use a GitHub runner that includes signtool.'
}

$signtool = Find-SignTool

foreach ($file in $Files) {
    if (-not (Test-Path $file)) {
        throw "File not found: $file"
    }

    $args = @('sign', '/fd', 'SHA256', '/td', 'SHA256', '/tr', $TimestampUrl)
    if ($PfxPath) {
        $args += @('/f', $PfxPath)
        if ($PfxPassword) {
            $args += @('/p', $PfxPassword)
        }
    }
    elseif ($Thumbprint) {
        $args += @('/sha1', $Thumbprint)
    }
    else {
        throw 'Provide either WINDOWS_CERTIFICATE_PFX_PATH or WINDOWS_CERTIFICATE_THUMBPRINT.'
    }

    $args += $file
    & $signtool @args
    if ($LASTEXITCODE -ne 0) {
        throw "signtool failed for $file"
    }

    & $signtool verify /pa /v $file
    if ($LASTEXITCODE -ne 0) {
        throw "Signature verification failed for $file"
    }
}
