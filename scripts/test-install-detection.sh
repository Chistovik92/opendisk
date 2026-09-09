#!/bin/sh
#
# Проверка выбора пакета в install.sh на файлах настоящих дистрибутивов.
#
#   sh scripts/test-install-detection.sh
#
# Зачем отдельным тестом: ошибка тут не падает, а ставит не тот пакет — на ALT
# приезжает сборка для Fedora, зависимости не сходятся, и человек остаётся с
# наполовину установленным приложением. Проверять это на живых системах нечем:
# ни ALT, ни Astra, ни RED OS под рукой нет и не будет. А вот содержимое их
# /etc/os-release известно и меняется редко — по нему и проверяем.
#
# Значения ID/ID_LIKE взяты из документации systemd и из os-release самих
# дистрибутивов.

set -eu

here="$(dirname "$0")"
installer="$here/install.sh"
[ -r "$installer" ] || { echo "не найден $installer" >&2; exit 1; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT INT TERM

passed=0
failed=0

# Прогоняет install.sh в режиме «ничего не делать, только сказать выбор».
#
# Версия задаётся явно, иначе скрипт полез бы в сеть за последней — тест
# должен работать без интернета.
detect() {
    os_release_file="$1"
    alt_marker_file="$2"
    OPENDISK_DRY_RUN=1 \
    OPENDISK_VERSION=v9.9.9 \
    OPENDISK_OS_RELEASE="$os_release_file" \
    OPENDISK_ALT_MARKER="$alt_marker_file" \
        sh "$installer" 2>/dev/null | sed -n 's/.*format=\([^ ]*\).*/\1/p'
}

# check <название> <ожидаемый формат> <содержимое os-release>
check() {
    name="$1"
    expected="$2"
    content="$3"

    file="$tmp/os-release"
    printf '%s\n' "$content" > "$file"

    actual="$(detect "$file" "$tmp/нет-такого-файла")"

    if [ "$actual" = "$expected" ]; then
        passed=$((passed + 1))
        printf '  ok    %-28s -> %s\n' "$name" "$actual"
    else
        failed=$((failed + 1))
        printf '  ПЛОХО %-28s -> %s, а ожидалось %s\n' "$name" "${actual:-пусто}" "$expected"
    fi
}

echo "Выбор пакета по дистрибутиву:"

# --- Семейство Debian: всем подходит один и тот же .deb ---------------------
check "Debian 12"        deb 'ID=debian
VERSION_ID="12"
PRETTY_NAME="Debian GNU/Linux 12 (bookworm)"'

check "Ubuntu 22.04"     deb 'ID=ubuntu
ID_LIKE=debian
PRETTY_NAME="Ubuntu 22.04.3 LTS"'

check "Linux Mint"       deb 'ID=linuxmint
ID_LIKE="ubuntu debian"
PRETTY_NAME="Linux Mint 21.2"'

check "Astra Linux"      deb 'ID=astra
ID_LIKE=debian
PRETTY_NAME="Astra Linux 1.7"'

# Незнакомый потомок Debian опознаётся по ID_LIKE.
check "неизвестный, но debian" deb 'ID=какой-то-новый
ID_LIKE="debian"
PRETTY_NAME="Новый дистрибутив"'

# --- ALT и то, что на нём построено -----------------------------------------
#
# Главный случай ради которого всё: у ALT свои имена зависимостей, и пакет,
# собранный для Fedora, туда ставить нельзя.
check "ALT Workstation"  rpm-alt 'ID=altlinux
PRETTY_NAME="ALT Workstation 10.2"'

check "Simply Linux"     rpm-alt 'ID=altlinux
PRETTY_NAME="Simply Linux 10.2"'

check "ALT по ID_LIKE"   rpm-alt 'ID=что-то-своё
ID_LIKE="altlinux"
PRETTY_NAME="Сборка на ALT"'

# --- Прочие rpm-дистрибутивы ------------------------------------------------
check "Fedora 40"        rpm 'ID=fedora
VERSION_ID=40
PRETTY_NAME="Fedora Linux 40"'

check "Rocky Linux 9"    rpm 'ID=rocky
ID_LIKE="rhel centos fedora"
PRETTY_NAME="Rocky Linux 9.3"'

check "RED OS"           rpm 'ID=redos
ID_LIKE="rhel fedora"
PRETTY_NAME="RED OS 7.3"'

check "openSUSE Leap"    rpm 'ID=opensuse-leap
ID_LIKE="suse opensuse"
PRETTY_NAME="openSUSE Leap 15.5"'

# --- ALT опознаётся и без os-release ----------------------------------------
#
# У части сборок ALT в ID стоит что угодно, но /etc/altlinux-release есть
# всегда. Проверяем, что одной этой приметы достаточно.
printf 'ID=непонятно\nPRETTY_NAME="Что-то своё"\n' > "$tmp/os-release-alt"
: > "$tmp/altlinux-release"
actual="$(detect "$tmp/os-release-alt" "$tmp/altlinux-release")"
if [ "$actual" = "rpm-alt" ]; then
    passed=$((passed + 1))
    printf '  ok    %-28s -> %s\n' "ALT по /etc/altlinux-release" "$actual"
else
    failed=$((failed + 1))
    printf '  ПЛОХО %-28s -> %s, а ожидалось rpm-alt\n' "ALT по /etc/altlinux-release" "${actual:-пусто}"
fi

# --- Дистрибутив, о котором мы ничего не знаем ------------------------------
#
# Точного ожидания тут нет намеренно: выбор падает на то, что реально стоит в
# системе, и на разных машинах он разный. Проверяем, что скрипт не падает и
# выбирает что-то осмысленное — в том числе AppImage, который не зависит ни от
# какого пакетного менеджера.
actual="$(detect "$tmp/нет-такого" "$tmp/нет-такого")"
case "$actual" in
    deb|rpm|rpm-alt|appimage)
        passed=$((passed + 1))
        printf '  ok    %-28s -> %s\n' "без os-release" "$actual"
        ;;
    *)
        failed=$((failed + 1))
        printf '  ПЛОХО %-28s -> %s\n' "без os-release" "${actual:-пусто}"
        ;;
esac

echo
echo "Прошло: $passed, не прошло: $failed"
[ "$failed" -eq 0 ] || exit 1
