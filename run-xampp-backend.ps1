param(
  [switch]$ValidateOnly
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$envPath = Join-Path $repoRoot '.env'
$templatePath = Join-Path $repoRoot '.env.example'

if (-not (Test-Path -LiteralPath $envPath -PathType Leaf)) {
  throw 'Missing .env. Run run-aegis-x.bat to create it from .env.example.'
}
if (-not (Test-Path -LiteralPath $templatePath -PathType Leaf)) {
  throw 'Missing .env.example; cannot validate supported setting names.'
}

# Treat .env as data, never as shell code. Only accept keys documented by .env.example.
$allowedNames = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
foreach ($templateLine in [System.IO.File]::ReadLines($templatePath)) {
  $templateLine = $templateLine.Trim().TrimStart([char]0xFEFF)
  if ($templateLine -match '^([A-Za-z_][A-Za-z0-9_]*)=') {
    [void]$allowedNames.Add($Matches[1])
  }
}
$loadedNames = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)

foreach ($rawLine in [System.IO.File]::ReadLines($envPath)) {
  $line = $rawLine.Trim().TrimStart([char]0xFEFF)
  if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith('#')) {
    continue
  }

  $separator = $line.IndexOf('=')
  if ($separator -lt 1) {
    continue
  }

  $name = $line.Substring(0, $separator).Trim()
  if ($name.StartsWith('export ')) {
    $name = $name.Substring(7).Trim()
  }
  if (-not $allowedNames.Contains($name)) {
    continue
  }

  $value = $line.Substring($separator + 1).Trim()
  if ($value.Length -ge 2) {
    $first = $value.Substring(0, 1)
    $last = $value.Substring($value.Length - 1, 1)
    if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
      $value = $value.Substring(1, $value.Length - 2)
    }
  }

  [System.Environment]::SetEnvironmentVariable($name, $value, [System.EnvironmentVariableTarget]::Process)
  [void]$loadedNames.Add($name)
}

# The batch launcher selected this script only when the .env profile is xampp.
$env:SPRING_PROFILES_ACTIVE = 'xampp'

if ($ValidateOnly) {
  Write-Output 'XAMPP profile selected; .env parsed in memory (values hidden).'
  $databaseSettingsPresent = $loadedNames.Contains('DB_URL') -and $loadedNames.Contains('DB_USERNAME') -and $loadedNames.Contains('DB_PASSWORD')
  Write-Output "Database settings present: $databaseSettingsPresent."
  exit 0
}

Push-Location $repoRoot
try {
  & $env:ComSpec /d /s /c 'mvn -f backend\pom.xml spring-boot:run'
  $exitCode = $LASTEXITCODE
}
finally {
  Pop-Location
}

exit $exitCode
