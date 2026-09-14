param([Parameter(ValueFromRemainingArguments=$true)][string[]]$ArrodesArgs)
$ErrorActionPreference = 'Stop'
$launchCwd = (Get-Location).Path
$root = Split-Path -Parent $PSScriptRoot
& bun (Join-Path $root 'scripts/tui.ts') --cwd $launchCwd @ArrodesArgs
exit $LASTEXITCODE
