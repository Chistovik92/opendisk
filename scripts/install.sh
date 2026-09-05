#!/bin/sh
#
# Установка OpenDisk на Linux.
#
#   curl -fsSL https://raw.githubusercontent.com/Chistovik92/opendisk/main/scripts/install.sh | sh
#
# Скрипт определяет пакетный менеджер, скачивает подходящий пакет из релизов
# GitHub, сверяет контрольную сумму и ставит его. Версию можно задать явно:
#
#   OPENDISK_VERSION=v0.1.11 sh install.sh
#
# Намеренно на POSIX sh, а не на bash: на минимальных установках bash может
# не оказаться, а установщику нужно работать везде.

set -eu

REPO="Chistovik92/opendisk"
VERSION="${OPENDISK_VERSION:-latest}"

say() { printf '%s\n' "$*"; }
fail() { printf 'Ошибка: %s\n' "$*" >&2; exit 1; }

# --- Проверки окружения -----------------------------------------------------

if command -v curl >/dev/null 2>&1; then
    download() { curl -fsSL "$1" -o "$2"; }
    fetch() { curl -fsSL "$1"; }
elif command -v wget >/dev/null 2>&1; then
    download() { wget -qO "$2" "$1"; }
    fetch() { wget -qO - "$1"; }
else
    fail "нужен curl или wget"
fi

arch="$(uname -m)"
case "$arch" in
    x86_64|amd64) ;;
    *) fail "поддерживается только x86_64, а у вас $arch.
Сборок под другие архитектуры пока нет — см. https://github.com/$REPO/releases" ;;
esac

# Формат пакета выбираем по тому, что реально есть в системе, а не по
# названию дистрибутива: производных у Debian и Fedora слишком много.
if command -v apt-get >/dev/null 2>&1 || command -v dpkg >/dev/null 2>&1; then
    format="deb"
elif command -v dnf >/dev/null 2>&1 || command -v zypper >/dev/null 2>&1 ||
     command -v yum >/dev/null 2>&1 || command -v rpm >/dev/null 2>&1; then
    format="rpm"
else
    fail "не нашёл ни apt/dpkg, ни dnf/yum/zypper/rpm.
Скачайте пакет вручную: https://github.com/$REPO/releases"
fi

if [ "$(id -u)" -eq 0 ]; then
    sudo=""
elif command -v sudo >/dev/null 2>&1; then
    sudo="sudo"
else
    fail "нужны права root: запустите от root или установите sudo"
fi

# --- Какую версию ставим ----------------------------------------------------

if [ "$VERSION" = "latest" ]; then
    say "Узнаю последнюю версию..."
    # Не /releases/latest: этот эндпоинт пропускает предварительные релизы,
    # а все сборки OpenDisk пока помечены как pre-release — он вернул бы 404.
    VERSION="$(fetch "https://api.github.com/repos/$REPO/releases?per_page=1" |
        sed -n 's/.*"tag_name" *: *"\([^"]*\)".*/\1/p' | head -n 1)"
    [ -n "$VERSION" ] || fail "не удалось определить последнюю версию.
Укажите её явно: OPENDISK_VERSION=v0.1.11"
fi

number="${VERSION#v}"
case "$format" in
    deb) asset="opendisk_${number}-1_amd64.deb" ;;
    rpm) asset="opendisk-${number}-1.x86_64.rpm" ;;
esac

say "Ставлю OpenDisk $VERSION ($format)"

# --- Скачивание -------------------------------------------------------------

tmp="$(mktemp -d)"
# Каталог убираем в любом случае: пакет весит под сотню мегабайт.
trap 'rm -rf "$tmp"' EXIT INT TERM

base="https://github.com/$REPO/releases/download/$VERSION"
say "Скачиваю $asset..."
download "$base/$asset" "$tmp/$asset" ||
    fail "не удалось скачать $base/$asset
Возможно, для этой версии нет пакета $format. Список файлов:
https://github.com/$REPO/releases/tag/$VERSION"

# Контрольную сумму проверяем, если она опубликована: у старых релизов её нет,
# и это не повод отказываться ставить.
if download "$base/SHA256SUMS-Linux" "$tmp/SHA256SUMS" 2>/dev/null &&
   command -v sha256sum >/dev/null 2>&1; then
    expected="$(sed -n "s/^\([0-9a-f]*\) .*$asset\$/\1/p" "$tmp/SHA256SUMS" | head -n 1)"
    if [ -n "$expected" ]; then
        actual="$(sha256sum "$tmp/$asset" | cut -d' ' -f1)"
        [ "$expected" = "$actual" ] || fail "контрольная сумма не совпала.
  ожидалась: $expected
  получена:  $actual
Скачанный файл повреждён или подменён — установка отменена."
        say "Контрольная сумма совпала."
    fi
else
    say "Контрольная сумма не опубликована для этого релиза — пропускаю проверку."
fi

# --- Установка --------------------------------------------------------------

say "Устанавливаю (потребуются права администратора)..."
if [ "$format" = "deb" ]; then
    if command -v apt-get >/dev/null 2>&1; then
        # apt умеет доставить зависимости, dpkg — нет.
        $sudo apt-get install -y "$tmp/$asset"
    else
        $sudo dpkg -i "$tmp/$asset"
    fi
else
    if command -v dnf >/dev/null 2>&1; then
        $sudo dnf install -y "$tmp/$asset"
    elif command -v zypper >/dev/null 2>&1; then
        $sudo zypper --non-interactive install --allow-unsigned-rpm "$tmp/$asset"
    elif command -v yum >/dev/null 2>&1; then
        $sudo yum install -y "$tmp/$asset"
    else
        $sudo rpm -Uvh "$tmp/$asset"
    fi
fi

say ""
say "OpenDisk $VERSION установлен."

# --- FUSE -------------------------------------------------------------------

# Без FUSE приложение работает и облака добавляются, но подключить их как диск
# нельзя. Встроить его в пакет, как WinFsp на Windows, невозможно: это модуль
# ядра, он ставится только средствами дистрибутива.
if [ ! -e /dev/fuse ]; then
    say ""
    say "Внимание: в системе нет FUSE (/dev/fuse), без него облака не подключить как диск."
    if command -v apt-get >/dev/null 2>&1; then
        say "  sudo apt-get install fuse3"
    elif command -v dnf >/dev/null 2>&1; then
        say "  sudo dnf install fuse3"
    elif command -v zypper >/dev/null 2>&1; then
        say "  sudo zypper install fuse3"
    else
        say "  установите пакет fuse3 средствами вашего дистрибутива"
    fi
fi

if [ "$format" = "deb" ]; then
    remove_command="apt-get remove opendisk"
else
    remove_command="rpm -e opendisk"
fi

say ""
say "Запуск: найдите OpenDisk в меню приложений"
say "        или запустите /opt/opendisk/bin/OpenDisk"
say "Удалить: $sudo $remove_command"
