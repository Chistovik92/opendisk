#!/bin/sh
# Установка обновления OpenDisk на macOS.
#
#   sh install-update.sh <файл .dmg> <путь к OpenDisk.app> <pid приложения>
#
# Запускает приложение (UpdateInstaller) и сразу выходит само. Сценарий ждёт
# его выхода, открывает образ, копирует из него OpenDisk.app на место
# прежнего и запускает приложение обратно — даже если что-то не вышло,
# чтобы человек не остался без приложения.
#
# Mac для проверки у разработчиков нет: этот путь проверен только чтением.
# Поэтому он нарочно осторожен — новая копия собирается рядом (.new) и
# встаёт на место одним переименованием; старая удаляется, только когда
# новая уже скопирована целиком.
set -u

dmg="$1"
app="$2"
pid="${3:-}"

waited=0
while [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && [ "$waited" -lt 60 ]; do
    sleep 1
    waited=$((waited + 1))
done

# $1 — OpenDisk.app из образа, $2 — куда поставить.
replace='rm -rf "$2.new" && ditto "$1" "$2.new" && rm -rf "$2" && mv "$2.new" "$2"'

code=0
mnt="$(mktemp -d /tmp/opendisk-update.XXXXXX)"
if hdiutil attach -nobrowse -readonly -noautoopen -mountpoint "$mnt" "$dmg" >/dev/null; then
    src="$(find "$mnt" -maxdepth 1 -name '*.app' -print | head -n 1)"
    if [ -z "$src" ]; then
        echo "в образе нет приложения" >&2
        code=3
    elif [ -w "$(dirname "$app")" ] && { [ ! -e "$app" ] || [ -w "$app" ]; }; then
        sh -c "$replace" sh "$src" "$app" || code=$?
    else
        # /Applications без прав на запись — пароль спрашивает система своим
        # окном, как при установке из образа руками.
        osascript \
            -e 'on run argv' \
            -e 'do shell script "/bin/sh -c " & quoted form of (item 3 of argv) & " sh " & quoted form of (item 1 of argv) & " " & quoted form of (item 2 of argv) with administrator privileges' \
            -e 'end run' \
            "$src" "$app" "$replace" || code=$?
    fi
    hdiutil detach "$mnt" -quiet || hdiutil detach "$mnt" -force -quiet
else
    echo "образ не открылся: $dmg" >&2
    code=4
fi
rmdir "$mnt" 2>/dev/null
echo "установка: код $code"

# Файл скачан не браузером, и карантинной метки на нём быть не должно; но
# если она есть, Gatekeeper не даст запустить неподписанное приложение.
xattr -dr com.apple.quarantine "$app" 2>/dev/null
open "$app"
exit "$code"
