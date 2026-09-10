# Удаление OpenDisk с Windows.
#
# Запускается самим приложением (Cleanup.startSelfUninstall) и тем же файлом —
# в CI на настоящей установке (scripts/verify-windows-install.ps1).
#
# С 0.5.0 в «Программах и компонентах» OpenDisk записан дважды: видимая
# запись обёртки Burn и скрытая (SystemComponent=1) — самого MSI внутри неё.
# Удалять надо через обёртку. Если снести только MSI, приложение пропадёт,
# а видимая запись останется сиротой: человек видит «OpenDisk» в списке
# программ, хотя его уже нет.
#
# Произвольную строку удаления из реестра не выполняем: туда попадают чужие
# программы с ключами тихого удаления. Обёртку находим по своему коду
# обновления (UpgradeCode в wix/Bundle.wxs) и запускаем её кэшированную копию,
# которую Burn хранит ровно для удаления.
#
# Удаление запускается только после выхода приложения — по той же причине,
# что и обновление (см. install-update.ps1): иначе его файлы заняты, Windows
# откладывает их удаление до перезагрузки, и от приложения остаются следы.
#
# Коды выхода: 0 — удаление прошло (или запущено, без -Wait), 2 — удалять
# нечего, 1223 — отказ в правах администратора, прочие — ошибка удаления.

param(
    # Дождаться конца удаления. Приложению это не нужно — оно к тому моменту
    # уже вышло, — а проверке в CI нужно.
    [switch]$Wait,
    # Номер процесса приложения, попросившего удаление, и каталог установки.
    [int]$WaitForPid = 0,
    [string]$AppDir
)

$ErrorActionPreference = 'Stop'

$BundleUpgradeCode = '{C9C80990-757B-4949-B2F7-7192E6281B4F}'

# 32-битный Burn записывает себя в WOW6432Node, 64-битный MSI — в основную
# ветку. Смотрим обе.
$roots = @(
    'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*'
)
$entries = @($roots | ForEach-Object { Get-ItemProperty $_ -ErrorAction SilentlyContinue })

# Сначала — что удалять. Если нечего, сказать это надо сразу, пока
# приложение ещё работает и может показать сообщение.
$command = $null
$bundle = $entries |
    Where-Object { $_.BundleUpgradeCode -contains $BundleUpgradeCode } |
    Select-Object -First 1
if ($bundle -and $bundle.BundleCachePath -and (Test-Path -LiteralPath $bundle.BundleCachePath)) {
    # Установка из .exe (0.5.0 и новее).
    $command = @{ File = $bundle.BundleCachePath; Arguments = @('/uninstall', '/passive', '/norestart') }
} else {
    # Установка из .msi (0.4.x и раньше). Берём только код продукта — видимая
    # запись MSI такие версии и оставляли.
    $msi = $entries |
        Where-Object {
            $_.DisplayName -eq 'OpenDisk' -and
            $_.SystemComponent -ne 1 -and
            $_.UninstallString -match '\{[0-9A-Fa-f-]{36}\}'
        } |
        Select-Object -First 1
    if ($msi) {
        $productCode = [regex]::Match($msi.UninstallString, '\{[0-9A-Fa-f-]{36}\}').Value
        $command = @{ File = 'msiexec'; Arguments = @('/x', $productCode, '/qb', '/norestart') }
    }
}
if (-not $command) { exit 2 }

# Дожидаемся выхода приложения и добиваем оставшееся — только своё, по пути
# в каталоге установки: чужой rclone с тем же именем трогать нельзя.
if ($WaitForPid -gt 0) {
    $app = Get-Process -Id $WaitForPid -ErrorAction SilentlyContinue
    if ($app) { $null = $app.WaitForExit(60000) }
}
if ($AppDir) {
    Get-Process OpenDisk, rclone -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -and $_.Path.StartsWith($AppDir, [StringComparison]::OrdinalIgnoreCase) } |
        Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 1
}

try {
    $process = Start-Process -FilePath $command.File -ArgumentList $command.Arguments `
        -Verb RunAs -PassThru -Wait:$Wait
} catch {
    # Отказ в правах: удаления не будет. Приложение к этому моменту уже вышло
    # — возвращаем его, чтобы человек не остался ни с программой, ни без неё.
    $launcher = if ($AppDir) { Join-Path $AppDir 'OpenDisk.exe' } else { $null }
    if ($launcher -and (Test-Path -LiteralPath $launcher)) { Start-Process -FilePath $launcher }
    exit 1223
}

if ($Wait) { exit $process.ExitCode }
exit 0
