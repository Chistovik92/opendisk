#!/bin/sh
#
# Проверка установки OpenDisk на Linux — внутри чистого контейнера
# дистрибутива, до выпуска релиза.
#
#   sh scripts/verify-linux-install.sh <ожидаемый вариант> <каталог новой версии> [<каталог прошлой версии>]
#
# Ставит тем же install.sh, которым ставят пользователи, — в режиме «из
# каталога» (OPENDISK_PACKAGE_DIR): релиз к моменту проверки ещё черновик,
# и скачивать неоткуда.
#
# Проверяется то, что раньше выяснялось только у пользователя:
#   - install.sh выбирает для этого дистрибутива правильный пакет;
#   - пакет вообще ставится — зависимости находятся в репозиториях;
#   - обновление с прошлой версии проходит, а не только чистая установка;
#   - встроенный rclone исполняемый и отвечает;
#   - сама JVM из пакета поднимается на этой системе.

set -eu

expected="${1:?укажите ожидаемый вариант: deb, rpm, rpm-alt}"
new_dir="${2:?укажите каталог с пакетами новой версии}"
prev_dir="${3:-}"

here=$(cd "$(dirname "$0")" && pwd)
installer="$here/install.sh"

fail() { printf '\nПРОВЕРКА НЕ ПРОШЛА: %s\n' "$*" >&2; exit 1; }
step() { printf '\n=== %s ===\n' "$*"; }

# Версия установленного пакета вместе с релизом сборки: у ALT это alt1, и
# только по нему видно, что встал именно ALT-овский пакет.
installed() {
    if command -v dpkg-query >/dev/null 2>&1 && dpkg-query -W opendisk >/dev/null 2>&1; then
        dpkg-query -W -f '${Version}' opendisk
    elif command -v rpm >/dev/null 2>&1 && rpm -q opendisk >/dev/null 2>&1; then
        rpm -q --qf '%{VERSION}-%{RELEASE}' opendisk
    fi
}

new_version=$(ls "$new_dir" | sed -n 's/^opendisk_\([0-9][0-9.]*\)-1_amd64\.deb$/\1/p' | head -n 1)
[ -n "$new_version" ] || fail "в $new_dir нет пакетов новой версии"

step "Что выберет install.sh"
choice=$(OPENDISK_DRY_RUN=1 OPENDISK_PACKAGE_DIR="$new_dir" sh "$installer" |
    sed -n 's/.*format=\([^ ]*\).*/\1/p')
echo "выбран вариант: $choice"
[ "$choice" = "$expected" ] ||
    fail "install.sh выбрал «$choice», а для этого дистрибутива нужен «$expected»"

if [ -n "$prev_dir" ]; then
    step "Прошлая версия — чтобы проверить обновление, а не только чистую установку"
    OPENDISK_PACKAGE_DIR="$prev_dir" sh "$installer"
    echo "стоит: $(installed)"
fi

step "Новая версия $new_version"
OPENDISK_PACKAGE_DIR="$new_dir" sh "$installer"

got=$(installed)
echo "стоит: $got"
case "$got" in
    "$new_version"-*) ;;
    *) fail "после установки стоит «$got», а ждали $new_version" ;;
esac
if [ "$expected" = rpm-alt ]; then
    case "$got" in
        *-alt1) ;;
        *) fail "на ALT встал не ALT-овский пакет: $got" ;;
    esac
fi

step "Встроенный rclone"
rclone=/opt/opendisk/lib/app/resources/rclone
[ -x "$rclone" ] || fail "rclone в пакете не исполняемый — приложение не сможет его запустить"
"$rclone" version | head -n 1
"$rclone" version | head -n 1 | grep -q '^rclone v' || fail "rclone не отвечает"

step "Приложение запускается"
# С ключом --cleanup приложение стартует, убирает свои следы и выходит, окна
# не нужно. Этого хватает, чтобы проверить, что JVM из пакета поднимается на
# этой системе, — без экрана большего от контейнера не добиться.
/opt/opendisk/bin/OpenDisk --cleanup || fail "приложение не запустилось"

printf '\nВсё в порядке: %s\n' "$got"
