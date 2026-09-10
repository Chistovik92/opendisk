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
    [string]$PreviousExe,
    # Требовать, чтобы приложение после обновления запустилось и подняло rclone.
    # На ARM-раннере GitHub интерактивный сеанс висит на экране первой настройки
    # Windows («Choose privacy settings», видно на снимке), и программы с окнами
    # там толком не работают — установщик в режиме с окном прогресса повисал
    # бесконечно. Установка, обновление и удаление от окон не зависят и
    # проверяются строго везде; запуск приложения там — предупреждение.
    [bool]$RequireAppStart = $true,
    # Проверять удаление. На ARM-раннере его не пройти: удаление вызывает
    # лаунчер приложения с --cleanup и ждёт его, а программы графической
    # подсистемы там не завершаются (см. scripts/probe-windows-launch.ps1).
    # MSI у обеих архитектур один и тот же, удаление проверяется на x64.
    [bool]$TestUninstall = $true
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

# Журналы установщика — главное, что нужно при разборе провала. Burn пишет их
# в %TEMP% сам, без ключей: и свой (OpenDisk_<время>.log), и журнал MSI внутри
# (OpenDisk_<время>_000_OpenDiskMsi.log). Без них причину пришлось бы угадывать:
# на раннере они остаются, а раннер после работы исчезает.
function Show-InstallerLogs {
    $logs = @(Get-ChildItem $env:TEMP -Filter 'OpenDisk_*.log' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime | Select-Object -Last 4)
    if ($logs.Count -eq 0) { Write-Host '  (журналов установщика в %TEMP% нет)' }
    foreach ($log in $logs) {
        Write-Host ''
        Write-Host "--- $($log.Name), последние строки ---"
        Get-Content -LiteralPath $log.FullName -Tail 80 | Write-Host
    }

    # Замены, отложенные до перезагрузки. Если они есть, установщик считал
    # файлы занятыми и вернул 3010, а Burn после этого может отказываться
    # от следующей операции до перезагрузки.
    $pending = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager' `
        -Name PendingFileRenameOperations -ErrorAction SilentlyContinue).PendingFileRenameOperations
    if ($pending) {
        Write-Host ''
        Write-Host 'Отложенные до перезагрузки замены файлов:'
        $pending | Where-Object { $_ } | Write-Host
    }
}

# Запускается ли встроенный rclone вообще. Если приложение живо, а rclone нет,
# первым делом надо понять: не стартовал он или стартовал и сразу умер.
function Show-BundledRclone {
    $rclone = Join-Path $env:ProgramFiles 'OpenDisk\app\resources\rclone.exe'
    if (-not (Test-Path -LiteralPath $rclone)) { Write-Host "  встроенного rclone нет: $rclone"; return }
    # Сначала весь вывод, потом код: Select-Object -First прерывает конвейер,
    # и $LASTEXITCODE оставался бы от предыдущей команды.
    $out = @(& $rclone version 2>&1)
    $rcloneCode = $LASTEXITCODE
    Write-Host "  встроенный rclone, код $rcloneCode`:"
    $out | Select-Object -First 4 | ForEach-Object { Write-Host "    $_" }
}

# Снимок экрана. Приложение показывает причину сбоя в своём окне — например,
# «rclone не запустился» с его выводом, — а в лог CI окно не попадает. Снимок
# уезжает артефактом работы.
function Save-Screenshot([string]$name) {
    try {
        Add-Type -AssemblyName System.Windows.Forms, System.Drawing
        $bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
        $bitmap = New-Object System.Drawing.Bitmap $bounds.Width, $bounds.Height
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        $graphics.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)
        $dir = Join-Path $PSScriptRoot '..\verify-screenshots'
        New-Item -ItemType Directory -Force $dir | Out-Null
        $path = Join-Path $dir "$name.png"
        $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
        Write-Host "  снимок экрана: $path"
    } catch {
        Write-Host "  снимок экрана не получился: $($_.Exception.Message)"
    }
}

function Fail([string]$message) {
    Write-Host ''
    Write-Host 'Записи OpenDisk в реестре:'
    Show-Entries
    Get-Process OpenDisk, rclone -ErrorAction SilentlyContinue |
        Format-Table Name, Id, StartTime, Responding, Path -AutoSize | Out-String | Write-Host
    Show-BundledRclone
    Save-Screenshot 'failure'
    Show-InstallerLogs
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
        # Не повод бросать проверку: обновление ставится и без работающей
        # копии, и главное — посмотреть, запускается ли новая версия. Но это
        # находка про уже выпущенную версию, поэтому выкладываем всё, что
        # поможет её разобрать.
        Write-Host '::warning::прошлая версия запустилась, но за две минуты не подняла rclone'
        Get-Process OpenDisk -ErrorAction SilentlyContinue |
            Format-Table Name, Id, StartTime, Responding, Path -AutoSize | Out-String | Write-Host
        Show-BundledRclone
        Save-Screenshot 'previous-version-without-rclone'
    }
}
$beforeUpdate = Get-Date

# Запуск сценария так, как это делает приложение: он получает номер процесса
# приложения и ждёт его выхода. Приложение в жизни выходит само сразу после
# запуска сценария; здесь его закрываем мы — старая версия этого не умеет.
function Invoke-AsTheApp([string]$script, [string]$arguments, $app) {
    $runner = Start-Process powershell -PassThru -ArgumentList `
        "-NoProfile -ExecutionPolicy Bypass -File `"$(Join-Path $scripts $script)`" $arguments"
    Start-Sleep -Seconds 3
    if ($app -and -not $app.HasExited) { Stop-Process -Id $app.Id -Force -ErrorAction SilentlyContinue }
    # Предел, а не бесконечное ожидание: зависший установщик должен ронять
    # проверку, а не держать раннер до конца его жизни.
    if (-not $runner.WaitForExit(600000)) { Fail "сценарий $script не завершился за 10 минут" }
    return $runner.ExitCode
}

Write-Host "=== 4. Обновление тем же сценарием, что и в приложении: $Installer ==="
$appPid = if ($old) { $old.Id } else { 0 }
$code = Invoke-AsTheApp 'install-update.ps1' `
    "-Installer `"$Installer`" -Launcher `"$launcher`" -WaitForPid $appPid" $old
Write-Host "код установщика: $code"
# 3010 больше не годится: он и означал, что файлы прошлой версии были заняты
# и остались до перезагрузки, — ровно то, что исправлялось.
if ($code -ne 0) { Fail "установщик завершился с кодом $code" }
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
    if ($RequireAppStart) { Fail 'после обновления приложение не запустилось само или не подняло rclone' }
    Write-Host '::warning::после обновления приложение не подняло rclone — на этом раннере запуск программ с окном проверить нельзя, см. снимок экрана'
    Get-Process OpenDisk -ErrorAction SilentlyContinue |
        Format-Table Name, Id, StartTime, Responding, Path -AutoSize | Out-String | Write-Host
    Save-Screenshot 'after-update-without-rclone'
} else {
    # Живо ли оно через несколько секунд — падение при старте тоже падение.
    Start-Sleep -Seconds 10
    if (-not (& $fresh)) { Fail 'приложение упало вскоре после запуска' }
    Get-Process OpenDisk, rclone | Format-Table Name, Id, StartTime, Path -AutoSize | Out-String | Write-Host
}

if (-not $TestUninstall) {
    Write-Host '::warning::удаление на этой машине не проверяется — оно проверено на x64 с тем же MSI'
    Write-Host ''
    Write-Host "Проверено: установка $ExpectedVersion."
    exit 0
}

Write-Host '=== 6. Удаление тем же сценарием, что и в приложении ==='
$app = Get-Process OpenDisk -ErrorAction SilentlyContinue |
    Where-Object { $_.StartTime -gt $beforeUpdate } | Sort-Object StartTime | Select-Object -First 1
$appDir = Split-Path -Parent $launcher
# Приложения может и не быть (на ARM-раннере) — тогда ждать нечего, а
# добивать остатки по каталогу установки сценарий будет всё равно.
$appId = if ($app) { $app.Id } else { 0 }
$code = Invoke-AsTheApp 'uninstall-opendisk.ps1' `
    "-Wait -WaitForPid $appId -AppDir `"$appDir`"" $app
Write-Host "код удаления: $code"
if ($code -ne 0) { Fail "удаление завершилось с кодом $code" }

if (-not (Wait-For { @(Get-Entries).Count -eq 0 } 60)) {
    Fail 'после удаления в «Программах и компонентах» осталась запись'
}
if (Test-Path -LiteralPath $launcher) { Fail "после удаления на месте $launcher" }
if (Get-Process OpenDisk -ErrorAction SilentlyContinue) { Fail 'приложение пережило удаление' }

Write-Host ''
Write-Host "Всё в порядке: установка, обновление до $ExpectedVersion, запуск и удаление."
