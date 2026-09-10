# Запускается ли приложение вообще — отдельно от установщика.
#
#   pwsh scripts/probe-windows-launch.ps1 -Msi <OpenDisk-*.msi> -JavaHome <JDK> [-Strict $false]
#
# MSI распаковывается административной установкой (только разворачивает файлы,
# в систему ничего не ставит), и код приложения запускается с ключом --cleanup
# — он не открывает окон: поднять JVM, убрать свои следы и выйти. Тремя
# способами, и по тому, какие из них работают, видно, где поломка:
#
#   java.exe  из JDK раннера — консольная программа. Не работает — сломан
#             сам код приложения.
#   javaw.exe из JDK раннера — графическая подсистема, но без окон.
#   OpenDisk.exe — лаунчер и JVM из нашей сборки, то, что запускает человек
#             (и установщик при удалении старой версии).
#
# Работает javaw, а лаунчер нет — сломан образ приложения, выпускать нельзя.
# Не работают ни javaw, ни лаунчер — на этой машине не стартуют программы
# графической подсистемы вообще.
#
# Написана, когда на ARM-раннере GitHub лаунчер не завершался даже с --cleanup,
# и было непонятно, виновата сборка или машина. Ответ дала она же: лаунчер
# 0.5.2 там запускается всеми тремя способами — не запускалась сборка 0.5.0.

param(
    [Parameter(Mandatory = $true)][string]$Msi,
    [Parameter(Mandatory = $true)][string]$JavaHome,
    # Строго — там, где программы графической подсистемы запускаются.
    [bool]$Strict = $true
)

$ErrorActionPreference = 'Stop'

$root = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { $env:TEMP }
$out = Join-Path $root ('opendisk-unpacked-' + [guid]::NewGuid().ToString('N'))
$unpack = Start-Process msiexec -ArgumentList "/a `"$Msi`" /qn TARGETDIR=`"$out`"" -Wait -PassThru
if ($unpack.ExitCode -ne 0) { throw "msiexec /a не распаковал MSI, код $($unpack.ExitCode)" }
$appDir = Join-Path $out 'OpenDisk'
Write-Host "Приложение распаковано в $appDir"

function Invoke-Probe([string]$title, [string]$file, [string]$arguments) {
    $log = Join-Path $out ('probe-' + [guid]::NewGuid().ToString('N'))
    $process = Start-Process -FilePath $file -ArgumentList $arguments -PassThru `
        -RedirectStandardOutput "$log.out" -RedirectStandardError "$log.err"
    # Без этого у Process из Start-Process код выхода бывает пустым.
    $null = $process.Handle
    $finished = $process.WaitForExit(90000)
    if (-not $finished) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
    $ok = $finished -and $process.ExitCode -eq 0
    $result = if ($finished) { "код $($process.ExitCode)" } else { 'не завершился за 90 секунд' }
    Write-Host ''
    Write-Host "$title — $result"
    foreach ($f in "$log.out", "$log.err") {
        if (Test-Path -LiteralPath $f) { Get-Content -LiteralPath $f -Tail 15 | ForEach-Object { Write-Host "    $_" } }
    }
    return $ok
}

$classpath = "-cp `"$appDir\app\*`" com.opendisk.app.MainKt --cleanup"

$console = Invoke-Probe 'java.exe из JDK раннера, код приложения' (Join-Path $JavaHome 'bin\java.exe') $classpath
$gui = Invoke-Probe 'javaw.exe из JDK раннера, графическая подсистема без окон' (Join-Path $JavaHome 'bin\javaw.exe') $classpath
$launcher = Invoke-Probe 'OpenDisk.exe — лаунчер и JVM из сборки' (Join-Path $appDir 'OpenDisk.exe') '--cleanup'

Write-Host ''
if (-not $console) { throw 'код приложения не запускается даже обычной JVM — сборка сломана' }
if ($launcher) { Write-Host 'Приложение из сборки запускается.'; exit 0 }
if ($gui) { throw 'обычная JVM запускается и в графической подсистеме, а лаунчер сборки — нет: сломан образ приложения' }

$message = 'на этой машине не стартуют программы графической подсистемы вообще — лаунчер здесь не проверить'
if ($Strict) { throw $message }
Write-Host "::warning::$message"
