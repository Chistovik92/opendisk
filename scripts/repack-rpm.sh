#!/bin/sh
# Пересобирает rpm, который сделал jpackage, чиня три вещи.
#
# 1. Встроенный rclone приезжает с правами 644, то есть неисполняемым.
#    Приложение не может его запустить, а исправить права на месте не может:
#    /opt принадлежит root. Для deb это чинится тем же способом.
#
# 2. Сценарий удаления не отличает удаление от обновления. Порядок у rpm
#    такой (проверено на пробном пакете):
#
#        %pre новой (arg=2) -> %post новой (arg=2) -> %preun старой (arg=1)
#
#    То есть `xdg-desktop-menu uninstall` из старой версии срабатывает уже
#    после того, как новая создала ярлык, и стирает его. После каждого
#    обновления ярлык пропадал.
#
# 3. При обновлении работающее приложение не останавливалось. Файлы в Linux
#    заменяются и под работающим процессом, поэтому старая версия продолжала
#    работать как ни в чём не бывало — с точки зрения пользователя обновление
#    просто не происходило.
#
# Проще было бы поправить сценарии на месте, но менять их у собранного rpm
# нечем: они лежат в заголовке пакета. Поэтому распаковываем и собираем заново
# со своим описанием.
set -eu

rpm_in=${1:?укажите путь к rpm}
# Путь дальше используется из другого каталога — приводим к абсолютному.
rpm_in=$(cd "$(dirname "$rpm_in")" && pwd)/$(basename "$rpm_in")
desktop_entry=/opt/opendisk/lib/opendisk-OpenDisk.desktop
launcher=/opt/opendisk/bin/OpenDisk
bundled_rclone=/opt/opendisk/lib/app/resources/rclone

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/stage" "$work/top/SPECS" "$work/top/BUILD" "$work/top/RPMS"

# rpm2archive, а не rpm2cpio: cpio в новых дистрибутивах уже не ставят
# по умолчанию, а rpm2archive идёт вместе с самим rpm.
rpm2archive - < "$rpm_in" | tar -xz -C "$work/stage"

chmod 755 "$work/stage$bundled_rclone"

version=$(rpm -qp --qf '%{VERSION}' "$rpm_in")
release=$(rpm -qp --qf '%{RELEASE}' "$rpm_in")

# Кавычки вокруг SPEC обязательны: внутри есть $1 сценариев rpm, и подставлять
# в него аргументы этого скрипта нельзя.
cat > "$work/top/SPECS/opendisk.spec" <<'SPEC'
# Обработку файлов после установки отключаем целиком. Иначе rpmbuild попробует
# обрезать символы у бинарников JVM и у rclone, а rclone он этим ломает.
%global __os_install_post %{nil}
%global __brp_check_rpaths %{nil}
%define _build_id_links none

Name:           opendisk
Version:        @VERSION@
Release:        @RELEASE@
Summary:        OpenDisk
License:        MIT
BuildArch:      x86_64
# Зависимости не вычисляем по файлам: внутри своя JVM, она самодостаточна.
# Список тот же, что был у пакета от jpackage.
AutoReqProv:    no
Requires:       /bin/sh
Requires:       xdg-utils

%description
OpenDisk — открытый клиент облачных дисков.

%install
# Содержимое кладём отсюда, а не из --buildroot: rpmbuild очищает buildroot
# перед сборкой, и заранее наполнить его нельзя.
mkdir -p %{buildroot}
cp -a @STAGE@/opt %{buildroot}/

%files
/opt/opendisk

%pre
if [ "$1" -ge 2 ]; then
    # Обновление. Останавливаем работающую копию, иначе она продолжит работать
    # со старым кодом: в Linux файлы заменяются и под запущенным процессом.
    #
    # Сначала по-хорошему: по TERM приложение успевает отключить диски и
    # погасить свой rclone.
    pkill -TERM -f "^/opt/opendisk/bin/OpenDisk" 2>/dev/null || true
    i=0
    while [ $i -lt 15 ] && pgrep -f "^/opt/opendisk/bin/OpenDisk" >/dev/null 2>&1; do
        sleep 1
        i=$((i + 1))
    done
    pkill -KILL -f "^/opt/opendisk/bin/OpenDisk" 2>/dev/null || true

    # Дочерний rclone мог пережить родителя, если тот завершился аварийно.
    # Он держит смонтированные диски, и без него их нечем отключить.
    pkill -TERM -f "^/opt/opendisk/lib/app/resources/rclone" 2>/dev/null || true
fi

%posttrans
# Ярлык создаётся именно здесь, а не в %post.
#
# При обновлении с версии, собранной jpackage, её %preun выполняется уже после
# нашего %post и безусловно удаляет ярлык — порядок проверен на пробных
# пакетах. %posttrans выполняется последним во всей операции, после всех
# %preun, поэтому созданное здесь переживает удаление старой версии.
xdg-desktop-menu install /opt/opendisk/lib/opendisk-OpenDisk.desktop || true

%preun
# Только при настоящем удалении. При обновлении этот сценарий выполняется уже
# после того, как новая версия создала ярлык, и без проверки стирал бы его.
if [ "$1" = 0 ]; then
    xdg-desktop-menu uninstall /opt/opendisk/lib/opendisk-OpenDisk.desktop || true
fi
SPEC

sed -i "s|@VERSION@|$version|; s|@RELEASE@|$release|; s|@STAGE@|$work/stage|" \
    "$work/top/SPECS/opendisk.spec"

rpmbuild --define "_topdir $work/top" \
         --define "_buildrootdir $work/top/BUILDROOT" \
         -bb "$work/top/SPECS/opendisk.spec" >/dev/null

built=$(find "$work/top/RPMS" -name '*.rpm' | head -1)
[ -n "$built" ] || { echo 'rpmbuild не выдал пакет' >&2; exit 1; }

cp "$built" "$rpm_in"

# Проверяем то, ради чего всё затевалось. Молча собрать сломанный пакет хуже,
# чем упасть: неисполняемый rclone обнаружится только у пользователя.
rpm -qlvp "$rpm_in" | grep "$bundled_rclone\$" | grep -q '^-rwx' ||
    { echo 'rclone так и не стал исполняемым' >&2; exit 1; }
rpm -qp --scripts "$rpm_in" | grep -q 'if \[ "\$1" = 0 \]' ||
    { echo 'сценарий удаления не отличает удаление от обновления' >&2; exit 1; }
rpm -qp --scripts "$rpm_in" | grep -q 'pkill -TERM' ||
    { echo 'сценарий обновления не останавливает приложение' >&2; exit 1; }
rpm -qp --scripts "$rpm_in" | grep -q 'posttrans' ||
    { echo 'ярлык создаётся не в posttrans — обновление его сотрёт' >&2; exit 1; }

echo "rpm пересобран: $rpm_in"
rpm -qlvp "$rpm_in" | grep "$bundled_rclone\$"
