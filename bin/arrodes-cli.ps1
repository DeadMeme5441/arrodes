param([Parameter(ValueFromRemainingArguments=$true)][string[]]$ArrodesArgs)
$ErrorActionPreference = 'Stop'
$launchCwd = (Get-Location).Path
$root = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $root 'target/arrodes-cli.jar'
$previousLaunchCwd = $env:ARRODES_LAUNCH_CWD
$env:ARRODES_LAUNCH_CWD = $launchCwd
try {
  if (Test-Path $jar) {
    & java -jar $jar --cwd $launchCwd @ArrodesArgs
    $result = $LASTEXITCODE
  } else {
    Push-Location $root
    try {
      & clojure -Srepro -M:run --cwd $launchCwd @ArrodesArgs
      $result = $LASTEXITCODE
    } finally {
      Pop-Location
    }
  }
} finally {
  if ($null -eq $previousLaunchCwd) {
    Remove-Item Env:ARRODES_LAUNCH_CWD -ErrorAction SilentlyContinue
  } else {
    $env:ARRODES_LAUNCH_CWD = $previousLaunchCwd
  }
}
exit $result
