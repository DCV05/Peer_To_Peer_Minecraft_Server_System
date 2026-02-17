param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ArgsFromCaller
)

$ErrorActionPreference = "SilentlyContinue"

try {
    $projectRoot = (Get-Location).Path
    $logDir = Join-Path $projectRoot "data"
    $logFile = Join-Path $logDir "codex-notify.log"

    if (-not (Test-Path $logDir)) {
        New-Item -ItemType Directory -Path $logDir -Force | Out-Null
    }

    $payloadText = ""
    if ($ArgsFromCaller -and $ArgsFromCaller.Count -gt 0) {
        $payloadText = ($ArgsFromCaller -join " ").Trim()
    }

    # If no argument payload is passed, attempt reading from stdin.
    if ([string]::IsNullOrWhiteSpace($payloadText)) {
        try {
            if (-not [Console]::IsInputRedirected) {
                $payloadText = "{}"
            } else {
                $payloadText = [Console]::In.ReadToEnd()
            }
        } catch {
            $payloadText = "{}"
        }
    }

    if ([string]::IsNullOrWhiteSpace($payloadText)) {
        $payloadText = "{}"
    }

    $timestamp = (Get-Date).ToString("o")
    $payloadObject = $null

    try {
        $payloadObject = $payloadText | ConvertFrom-Json -ErrorAction Stop
    } catch {
        $payloadObject = @{
            raw = $payloadText
        }
    }

    $entry = @{
        timestamp = $timestamp
        event = $payloadObject
    } | ConvertTo-Json -Compress -Depth 20

    Add-Content -Path $logFile -Value $entry
} catch {
    # Never fail the main Codex turn due to notify hook errors.
}
