# Установка обновления OpenDisk на Windows.
#
# Запускается самим приложением (UpdateInstaller) после того, как скачанный
# установщик сверен по контрольной сумме. Тот же файл гоняет CI на настоящей
# установке (scripts/verify-windows-install.ps1) — поэтому сценарий лежит
# отдельным файлом, а не строкой в коде: проверяется ровно то, что выполнится
# у пользователя.
#
# Порядок здесь главное. Установщик запускается только после того, как
# приложение вышло. Раньше приложение оставалось работать и надеялось, что
# установщик закроет его сам (util:CloseApplication). Он и закрывал — но уже
# после того, как попробовал удалить файлы прошлой версии. У jar-файлов в
# имени хэш, набор файлов у каждой версии свой, и старые занятые файлы
# Windows откладывала «до перезагрузки»: код 3010, недоудалённая прошлая
# версия, а Burn после этого отказывался и от удаления, и от следующего
# обновления до перезагрузки. Нашла это проверка в CI на настоящей Windows.
#
# Поэтому приложение запускает этот сценарий и выходит само — перед этим
# по-хорошему отключив диски и погасив rclone. Сценарий дожидается выхода,
# ставит обновление и запускает приложение обратно.
#
# PowerShell, запускающий установщик, прав администратора не получает, поэтому
# и приложение потом стартует от имени пользователя, а не системы.

param(
    [Parameter(Mandatory = $true)][string]$Installer,
    [string]$Launcher,
    # Номер процесса приложения, запустившего обновление.
    [int]$WaitForPid = 0
)

$ErrorActionPreference = 'Stop'

# Дожидается выхода приложения и добивает оставшееся — только своё: процессы,
# запущенные из каталога установки. Чужой rclone, запущенный человеком
# отдельно, имеет то же имя, и гасить его по имени нельзя.
function Wait-OpenDiskExit([int]$ProcessId, [string]$AppDir) {
    if ($ProcessId -gt 0) {
        $app = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
        # Минуты хватает с запасом: приложение отключает диски и выходит.
        if ($app) { $null = $app.WaitForExit(60000) }
    }
    if ($AppDir) {
        Get-Process OpenDisk, rclone -ErrorAction SilentlyContinue |
            Where-Object { $_.Path -and $_.Path.StartsWith($AppDir, [StringComparison]::OrdinalIgnoreCase) } |
            Stop-Process -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 1
    }
}

$appDir = if ($Launcher) { Split-Path -Parent $Launcher } else { $null }
Wait-OpenDiskExit $WaitForPid $appDir

# /passive — только полоса прогресса, без вопросов: обновление человек уже
# подтвердил кнопкой в приложении. Отказ в правах администратора бросает
# исключение — тогда обновления нет, но приложение всё равно возвращаем:
# оставить человека без программы хуже, чем без обновления.
$code = 1
try {
    $process = Start-Process -FilePath $Installer -ArgumentList '/passive', '/norestart' -Verb RunAs -Wait -PassThru
    $code = $process.ExitCode
} catch {
    $code = 1223
}

if ($Launcher -and (Test-Path -LiteralPath $Launcher)) {
    Start-Process -FilePath $Launcher
}

exit $code
