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
# Коды выхода: 0 — удаление запущено (или завершено, с -Wait), 2 — удалять
# нечего, прочие — ошибка самого удаления.

param(
    # Дождаться конца удаления. Приложению это не нужно — установщик сам
    # закроет его, — а проверке в CI нужно.
    [switch]$Wait
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

# Установка из .exe (0.5.0 и новее).
$bundle = $entries |
    Where-Object { $_.BundleUpgradeCode -contains $BundleUpgradeCode } |
    Select-Object -First 1

if ($bundle -and $bundle.BundleCachePath -and (Test-Path -LiteralPath $bundle.BundleCachePath)) {
    $process = Start-Process -FilePath $bundle.BundleCachePath `
        -ArgumentList '/uninstall', '/passive', '/norestart' `
        -Verb RunAs -PassThru -Wait:$Wait
    if ($Wait) { exit $process.ExitCode }
    exit 0
}

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
    $process = Start-Process -FilePath msiexec `
        -ArgumentList '/x', $productCode, '/qb', '/norestart' `
        -Verb RunAs -PassThru -Wait:$Wait
    if ($Wait) { exit $process.ExitCode }
    exit 0
}

exit 2
