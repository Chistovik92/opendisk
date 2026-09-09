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
    x86_64|amd64) arch_deb="amd64"; arch_rpm="x86_64"; arch_appimage="x86_64" ;;
    *) fail "под Linux пока собирается только x86_64, а у вас $arch.
Сборки под ARM есть для Windows и macOS — см. https://github.com/$REPO/releases" ;;
esac

# --- Какой дистрибутив ------------------------------------------------------
#
# Пакетного менеджера мало. У ALT Linux (и Simply Linux, который на нём
# построен) есть и rpm, и своя система имён зависимостей: пакет, собранный для
# Fedora, там либо не ставится, либо тянет не то. Ровно поэтому дистрибутив
# определяется отдельно от формата пакета.
#
# /etc/os-release — стандарт systemd, есть во всех живых дистрибутивах.
# ID_LIKE перечисляет предков: у Ubuntu это debian, у Rocky — rhel fedora.
#
# Пути вынесены в переменные, чтобы определение дистрибутива можно было
# прогнать на файлах настоящих систем, не имея самих систем под рукой:
# scripts/test-install-detection.sh делает ровно это.
os_release="${OPENDISK_OS_RELEASE:-/etc/os-release}"
alt_marker="${OPENDISK_ALT_MARKER:-/etc/altlinux-release}"

# Читаем в подоболочке, а не через обычный `.` в текущей.
#
# os-release определяет среди прочего VERSION — а у нас это уже версия
# OpenDisk, выбранная выше. Обычное сование затёрло бы её версией
# дистрибутива, и скрипт пошёл бы искать релиз «22.04.3 LTS».
read_os_release() {
    [ -r "$os_release" ] || return 0
    # shellcheck disable=SC1090,SC1091
    ( . "$os_release" >/dev/null 2>&1; eval "printf '%s' \"\${$1:-}\"" )
}

distro_id="$(read_os_release ID)"
distro_like="$(read_os_release ID_LIKE)"
distro_name="$(read_os_release PRETTY_NAME)"
[ -n "$distro_name" ] || distro_name="$(read_os_release NAME)"

# ALT определяется первым и по нескольким приметам сразу: у продуктов на его
# основе ID бывает и altlinux, и собственный (Simply Linux, Альт Рабочая
# станция), а /etc/altlinux-release есть у всех.
is_alt=no
case "$distro_id $distro_like" in
    *altlinux*|*alt-*|*simply*) is_alt=yes ;;
esac
if [ -e "$alt_marker" ]; then is_alt=yes; fi

if [ "$is_alt" = yes ]; then
    family="altlinux"
else
    case "$distro_id" in
        debian|ubuntu|linuxmint|astra|elementary|pop|devuan|kali|raspbian) family="debian" ;;
        fedora|rhel|centos|rocky|almalinux|ol|redos|rosa) family="rpm" ;;
        opensuse*|sles|suse) family="rpm" ;;
        *)
            # Незнакомый дистрибутив — смотрим, на что он похож.
            case "$distro_like" in
                *debian*|*ubuntu*) family="debian" ;;
                *rhel*|*fedora*|*suse*|*mandriva*) family="rpm" ;;
                *)
                    # os-release не помог: последний рубеж — что стоит в системе.
                    if command -v apt-get >/dev/null 2>&1 || command -v dpkg >/dev/null 2>&1; then
                        family="debian"
                    elif command -v dnf >/dev/null 2>&1 || command -v zypper >/dev/null 2>&1 ||
                         command -v yum >/dev/null 2>&1 || command -v rpm >/dev/null 2>&1; then
                        family="rpm"
                    else
                        family="unknown"
                    fi
                    ;;
            esac
            ;;
    esac
fi

case "$family" in
    debian)   format="deb" ;;
    altlinux) format="rpm-alt" ;;
    rpm)      format="rpm" ;;
    *)
        # Ни deb, ни rpm — но приложение всё равно можно запустить: AppImage
        # ни от какого пакетного менеджера не зависит.
        format="appimage"
        ;;
esac

say "Система: ${distro_name:-неизвестная} ($arch) — ставлю вариант «$format»"

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
    #
    # И не один самый свежий релиз: в том же репозитории лежат выпуски
    # встроенной библиотеки для мобильных платформ (librclone-v..., собирается
    # отдельно и по требованию). Самым свежим релизом запросто оказывается
    # один из них, и тогда скрипт пошёл бы качать несуществующий
    # opendisk_librclone-....deb. Поэтому берём страницу и первый тег вида
    # vЧИСЛО — выпуски приложения помечаются только так.
    #
    # tr по запятым: без него жадная .* в sed на компактном JSON забрала бы
    # последний tag_name на строке, то есть самый старый выпуск.
    VERSION="$(fetch "https://api.github.com/repos/$REPO/releases?per_page=20" |
        tr ',' '\n' |
        sed -n 's/.*"tag_name" *: *"\([^"]*\)".*/\1/p' |
        grep -E '^v[0-9]' | head -n 1)"
    [ -n "$VERSION" ] || fail "не удалось определить последнюю версию.
Укажите её явно: OPENDISK_VERSION=v0.1.11"
fi

number="${VERSION#v}"
case "$format" in
    deb)      asset="opendisk_${number}-1_${arch_deb}.deb" ;;
    # Своя сборка под ALT: у неё другие имена зависимостей, и пакет для Fedora
    # там либо не ставится, либо тянет не то. Суффикс alt1 — принятая в ALT
    # пометка релиза сборки.
    rpm-alt)  asset="opendisk-${number}-alt1.${arch_rpm}.rpm" ;;
    rpm)      asset="opendisk-${number}-1.${arch_rpm}.rpm" ;;
    appimage) asset="OpenDisk-${number}-${arch_appimage}.AppImage" ;;
esac

# Посмотреть, что скрипт выберет, ничего не устанавливая:
#
#   OPENDISK_DRY_RUN=1 OPENDISK_VERSION=v0.5.0 sh install.sh
#
# Нужно и само по себе — «а что вообще поставится на эту машину?» — и для
# проверки выбора пакета на файлах чужих дистрибутивов, см.
# scripts/test-install-detection.sh.
if [ -n "${OPENDISK_DRY_RUN:-}" ]; then
    printf 'family=%s format=%s asset=%s\n' "$family" "$format" "$asset"
    exit 0
fi

say "Ставлю OpenDisk $VERSION ($asset)"

# --- Скачивание -------------------------------------------------------------

tmp="$(mktemp -d)"
# Каталог убираем в любом случае: пакет весит под сотню мегабайт.
trap 'rm -rf "$tmp"' EXIT INT TERM

base="https://github.com/$REPO/releases/download/$VERSION"
say "Скачиваю $asset..."
if ! download "$base/$asset" "$tmp/$asset"; then
    # Подходящего пакета в этом релизе нет.
    #
    # Так бывает у старых версий, где сборки под этот дистрибутив ещё не было.
    # Сдаваться рано: AppImage не зависит ни от какого пакетного менеджера и
    # работает везде — это честный запасной вариант, а не заглушка. Но сказать
    # об этом надо прямо, чтобы человек понимал, что получил.
    if [ "$format" != "appimage" ]; then
        say "В релизе $VERSION нет пакета «$format» — пробую AppImage."
        format="appimage"
        asset="OpenDisk-${number}-${arch_appimage}.AppImage"
        download "$base/$asset" "$tmp/$asset" ||
            fail "не удалось скачать ни пакет для вашего дистрибутива, ни AppImage.
Посмотрите, что есть в релизе:
https://github.com/$REPO/releases/tag/$VERSION"
    else
        fail "не удалось скачать $base/$asset
Список файлов релиза:
https://github.com/$REPO/releases/tag/$VERSION"
    fi
fi

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
if [ "$format" = "appimage" ]; then
    # AppImage — не пакет: ставить нечего, надо просто положить файл и сделать
    # его исполняемым. Пакетный менеджер здесь не участвует вовсе, поэтому этот
    # путь и работает там, где ни deb, ни rpm не подходят.
    $sudo install -m 0755 "$tmp/$asset" /usr/local/bin/OpenDisk
elif [ "$format" = "deb" ]; then
    if command -v apt-get >/dev/null 2>&1; then
        # apt умеет доставить зависимости, dpkg — нет.
        $sudo apt-get install -y "$tmp/$asset"
    else
        $sudo dpkg -i "$tmp/$asset"
    fi
elif [ "$format" = "rpm-alt" ]; then
    # В ALT пакетный менеджер — apt поверх rpm, и зависимости доставляет он же.
    if command -v apt-get >/dev/null 2>&1; then
        $sudo apt-get install -y "$tmp/$asset"
    else
        $sudo rpm -Uvh "$tmp/$asset"
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

case "$format" in
    deb|rpm-alt) remove_command="apt-get remove opendisk" ;;
    appimage)    remove_command="rm /usr/local/bin/OpenDisk" ;;
    *)           remove_command="rpm -e opendisk" ;;
esac

say ""
if [ "$format" = "appimage" ]; then
    say "Запуск: OpenDisk (файл лежит в /usr/local/bin)"
    say "        ярлыка в меню нет: AppImage ставится мимо пакетного менеджера"
else
    say "Запуск: найдите OpenDisk в меню приложений"
    say "        или запустите /opt/opendisk/bin/OpenDisk"
fi
say "Удалить: $sudo $remove_command"
