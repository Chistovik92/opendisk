# Проверка установщика OpenDisk для Windows на настоящей системе — до выпуска.
#
#   pwsh scripts/verify-windows-install.ps1 -Installer <новый .exe> -ExpectedVersion 0.5.2 `
#        [-PreviousMsi <старый .msi>] [-PreviousExe <прошлый .exe>]
#
# Проходит путь, которым идут пользователи, от начала до конца:
#
#   1. стоит старая версия из .msi (0.4.x — так ставили до 0.5.0);
#   2. поверх неё — прошлый .exe;
#   3. приложение запущено, и обновление приходит к нему изнутри — тем самым
#      сценарием, который запускает приложение (install-update.ps1);
#   4. старая копия закрыта, новая запущена и подняла свой rclone;
#   5. удаление — тоже тем сценарием, который запускает приложение
#      (uninstall-opendisk.ps1), и после него не остаётся ни файлов, ни
#      записей в «Программах и компонентах».
#
# До 0.5.2 этот путь не проходил никто: .exe появился в 0.5.0 и проверялся
# только распаковкой. Встроенное обновление при этом было сломано, а удаление
# оставляло запись-сироту — узнать об этом было неоткуда.

param(
    [Parameter(Mandatory = $true)][string]$Installer,
    [Parameter(Mandatory = $true)][string]$ExpectedVersion,
    [string]$PreviousMsi,
    [string]$PreviousExe
)

$ErrorActionPreference = 'Stop'

$scripts = Join-Path $PSScriptRoot '..\composeApp\src\desktopMain\resources\windows'
$launcher = Join-Path $env:ProgramFiles 'OpenDisk\OpenDisk.exe'

function Get-Entries {
    @(
        'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
        'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*'
    ) | ForEach-Object { Get-ItemProperty $_ -ErrorAction SilentlyContinue } |
        Where-Object { $_.DisplayName -eq 'OpenDisk' }
}

function Show-Entries {
    $rows = @(Get-Entries | Select-Object PSChildName, DisplayVersion, SystemComponent, BundleCachePath)
    if ($rows.Count -eq 0) { Write-Host '  (записей OpenDisk в реестре нет)'; return }
    $rows | Format-Table -AutoSize | Out-String | Write-Host
}

function Fail([string]$message) {
    Write-Host ''
    Write-Host 'Записи OpenDisk в реестре:'
    Show-Entries
    Get-Process OpenDisk, rclone -ErrorAction SilentlyContinue |
        Format-Table Name, Id, StartTime, Path -AutoSize | Out-String | Write-Host
    throw "ПРОВЕРКА НЕ ПРОШЛА: $message"
}

# Видимая запись — та, что человек видит в «Программах и компонентах». У
# установки из .exe их две: обёртки (видимая) и MSI внутри неё (скрытая).
function Assert-SingleVisible([string]$version) {
    $visible = @(Get-Entries | Where-Object { $_.SystemComponent -ne 1 })
    if ($visible.Count -ne 1) {
        Fail "в «Программах и компонентах» OpenDisk записан $($visible.Count) раз(а), а должен ровно один"
    }
    if ($version -and -not ($visible[0].DisplayVersion -like "$version*")) {
        Fail "записана версия $($visible[0].DisplayVersion), а ждали $version"
    }
    Show-Entries
}

function Wait-For([scriptblock]$condition, [int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if (& $condition) { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

if ($PreviousMsi) {
    Write-Host "=== 1. Старая установка из MSI: $PreviousMsi ==="
    $p = Start-Process msiexec -ArgumentList '/i', "`"$PreviousMsi`"", '/qn', '/norestart' -Wait -PassThru
    if ($p.ExitCode -notin 0, 3010) { Fail "старый MSI не встал, код $($p.ExitCode)" }
    Assert-SingleVisible $null
}

if ($PreviousExe) {
    Write-Host "=== 2. Прошлый .exe поверх: $PreviousExe ==="
    $p = Start-Process $PreviousExe -ArgumentList '/quiet', '/norestart' -Wait -PassThru
    if ($p.ExitCode -notin 0, 3010) { Fail "прошлый .exe не встал, код $($p.ExitCode)" }
    Assert-SingleVisible $null
}

Write-Host '=== 3. Приложение работает — обновление приходит к запущенной копии ==='
$old = $null
if (Test-Path -LiteralPath $launcher) {
    $old = Start-Process -FilePath $launcher -PassThru
    if (-not (Wait-For { Get-Process rclone -ErrorAction SilentlyContinue } 120)) {
        Fail 'прошлая версия не запустилась — проверять обновление не на чем'
    }
}
$beforeUpdate = Get-Date

Write-Host "=== 4. Обновление тем же сценарием, что и в приложении: $Installer ==="
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $scripts 'install-update.ps1') `
    -Installer $Installer -Launcher $launcher
$code = $LASTEXITCODE
if ($code -notin 0, 3010) { Fail "установщик завершился с кодом $code" }
Assert-SingleVisible $ExpectedVersion

if ($old) {
    $old.Refresh()
    if (-not $old.HasExited) { Fail 'старая копия приложения пережила обновление' }
}

Write-Host '=== 5. Новая версия запущена и подняла свой rclone ==='
$fresh = {
    $app = Get-Process OpenDisk -ErrorAction SilentlyContinue | Where-Object { $_.StartTime -gt $beforeUpdate }
    $rclone = Get-Process rclone -ErrorAction SilentlyContinue | Where-Object { $_.StartTime -gt $beforeUpdate }
    $app -and $rclone
}
if (-not (Wait-For $fresh 120)) {
    Fail 'после обновления приложение не запустилось само или не подняло rclone'
}
# Живо ли оно через несколько секунд — падение при старте тоже падение.
Start-Sleep -Seconds 10
if (-not (& $fresh)) { Fail 'приложение упало вскоре после запуска' }
Get-Process OpenDisk, rclone | Format-Table Name, Id, StartTime, Path -AutoSize | Out-String | Write-Host

Write-Host '=== 6. Удаление тем же сценарием, что и в приложении ==='
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $scripts 'uninstall-opendisk.ps1') -Wait
$code = $LASTEXITCODE
if ($code -notin 0, 3010) { Fail "удаление завершилось с кодом $code" }

if (-not (Wait-For { @(Get-Entries).Count -eq 0 } 60)) {
    Fail 'после удаления в «Программах и компонентах» осталась запись'
}
if (Test-Path -LiteralPath $launcher) { Fail "после удаления на месте $launcher" }
if (Get-Process OpenDisk -ErrorAction SilentlyContinue) { Fail 'приложение пережило удаление' }

Write-Host ''
Write-Host "Всё в порядке: установка, обновление до $ExpectedVersion, запуск и удаление."
