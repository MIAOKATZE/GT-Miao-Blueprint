param([Parameter(Mandatory=$true)][string]$Launch, [Parameter(Mandatory=$true)][string]$Log)
$ErrorActionPreference = 'Stop'
$specification = Get-Content -LiteralPath $Launch -Raw -Encoding UTF8 | ConvertFrom-Json
$taskRepository = [IO.DirectoryInfo]$PSScriptRoot
while ($null -ne $taskRepository -and -not ((Test-Path -LiteralPath (Join-Path $taskRepository.FullName 'gradle.properties')) -and (Test-Path -LiteralPath (Join-Path $taskRepository.FullName 'src') -PathType Container))) {
    $taskRepository = $taskRepository.Parent
}
if ($null -eq $taskRepository) { throw 'Cannot locate the repository containing gradle.properties and src.' }
$repository = $taskRepository.FullName
$fixtureRoot = [IO.Path]::GetFullPath((Join-Path $repository 'build/hologram-followup'))
$workingDirectory = [IO.Path]::GetFullPath($specification.workingDirectory)
if (-not $workingDirectory.StartsWith($fixtureRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Launch working directory escaped the isolated follow-up fixture.'
}
if ($specification.release_version -ne '0.1.1') { throw 'Current v0.1.1 descriptor required.' }
if ($specification.role -eq 'client') {
    $taskGameDirectoryIndex = [Array]::IndexOf([object[]]$specification.arguments, '--gameDir')
    if ($taskGameDirectoryIndex -lt 0 -or $taskGameDirectoryIndex + 1 -ge $specification.arguments.Count) {
        throw 'Client launch must explicitly use its fresh game directory.'
    }
    $taskGameDirectory = [IO.Path]::GetFullPath($specification.arguments[$taskGameDirectoryIndex + 1])
    if (-not [String]::Equals($taskGameDirectory, $workingDirectory, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Client game directory differs from its isolated working directory.'
    }
}
if (-not (Test-Path -LiteralPath $specification.java -PathType Leaf)) { throw 'Configured Java is missing.' }
foreach ($property in $specification.source_hashes.PSObject.Properties) {
    $actual = (Get-FileHash -LiteralPath (Join-Path $repository $property.Name) -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $property.Value) { throw ('Source changed after launch preparation: ' + $property.Name) }
}
foreach ($property in $specification.engine_jar_hashes.PSObject.Properties) {
    $actual = (Get-FileHash -LiteralPath $property.Name -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $property.Value) { throw ('Engine jar changed after launch preparation: ' + $property.Name) }
}
foreach ($property in $specification.reviewed_runtime_hashes.PSObject.Properties) {
    $actual = (Get-FileHash -LiteralPath $property.Name -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $property.Value) { throw ('Reviewed runtime dependency changed after launch preparation: ' + $property.Name) }
}
$taskArguments = @($specification.arguments)
$logFile = [IO.Path]::GetFullPath($Log)
New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($logFile)) -Force | Out-Null
if (Test-Path -LiteralPath $logFile) { throw 'Use a fresh log path.' }
$started = [DateTimeOffset]::UtcNow
$actualExitCode = -1
Push-Location -LiteralPath $workingDirectory
try {
    $ErrorActionPreference = 'Continue'
    if ($specification.role -eq 'client') { $env:ALSOFT_DRIVERS = 'null' }
    & $specification.java $taskArguments 2>&1 | Tee-Object -FilePath $logFile
    $actualExitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
} finally { Pop-Location }
@{ started_utc=$started.ToString('o'); finished_utc=[DateTimeOffset]::UtcNow.ToString('o');
   exit_code=$actualExitCode; launch_sha256=(Get-FileHash -LiteralPath $Launch -Algorithm SHA256).Hash.ToLowerInvariant();
   log_sha256=(Get-FileHash -LiteralPath $logFile -Algorithm SHA256).Hash.ToLowerInvariant();
   working_directory=$workingDirectory; profile=$specification.profile; role=$specification.role } |
   ConvertTo-Json | Set-Content -LiteralPath ($logFile + '.receipt.json') -Encoding UTF8
exit $actualExitCode
