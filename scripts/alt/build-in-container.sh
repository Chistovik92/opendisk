#!/bin/sh
#
# Сборка пакета OpenDisk для ALT — внутри контейнера самого ALT.
#
# Запускается из scripts/build-alt-rpm.sh (репозиторий смонтирован в /src,
# версия — в переменной VERSION). Отдельным файлом, а не строкой внутри
# `docker run ... sh -c '...'`: в одной строке с тремя уровнями кавычек
# сценарии spec с `$1` и одинарными кавычками было бы не записать.

set -eu

: "${VERSION:?нужна версия в переменной VERSION}"

# rpm-build в базовом образе ALT не стоит. sudo — ради сборки не от root.
apt-get update -y >/dev/null
apt-get install -y rpm-build sudo >/dev/null

# ALT принципиально не даёт собирать пакеты от root: «current site policy
# disallows root to build packages». Это его политика, и обходить её ключами
# не нужно — собираем от обычного пользователя, как и задумано.
useradd -m builder 2>/dev/null || true

# В ALT sudo настроен строго, и root в sudoers не входит: «root is not in the
# sudoers file». Разрешаем явно — контейнер одноразовый и живёт ровно одну
# сборку. Через su было бы проще, но его в минимальном образе нет вовсе.
echo "root ALL=(ALL) NOPASSWD: ALL" >> /etc/sudoers

top=/home/builder/rpm
image=$top/BUILD/opendisk
mkdir -p "$top/BUILD" "$top/RPMS" "$top/SOURCES" "$top/SPECS" "$top/SRPMS" "$image"

# Образ приложения кладём в дерево сборки как есть: он самодостаточен, внутри
# своя JRE, собирать нечего — только разложить по путям.
cp -a /src/composeApp/build/compose/binaries/main/app/OpenDisk/. "$image/"

# Дата в журнале изменений — с правильным днём недели: rpm проверяет, что
# «пн» и число совпадают, и на выдуманной дате ругается.
sed -e "s|@VERSION@|$VERSION|g" \
    -e "s|@IMAGE@|$image|g" \
    -e "s|@DATE@|$(LC_ALL=C date '+%a %b %d %Y')|g" \
    /src/scripts/alt/opendisk.spec > "$top/SPECS/opendisk.spec"

chown -R builder:builder /home/builder
sudo -u builder rpmbuild --define "_topdir $top" -bb "$top/SPECS/opendisk.spec"

built=$(find "$top/RPMS" -name '*.rpm' | head -n 1)
[ -n "$built" ] || { echo "rpmbuild не выдал пакет" >&2; exit 1; }

# Проверяем то, ради чего правили, — как и scripts/repack-rpm.sh для Fedora.
# Молча собрать сломанный пакет хуже, чем упасть: неисполняемый rclone
# обнаружится только у пользователя. Ровно так и вышло в 0.5.0.
rclone_path=/opt/opendisk/lib/app/resources/rclone
rpm -qlvp "$built" | grep "$rclone_path\$" | grep -q '^-rwx' ||
    { echo "rclone в пакете ALT не исполняемый" >&2; exit 1; }
rpm -qp --scripts "$built" | grep -q 'pkill -TERM' ||
    { echo "сценарий обновления не останавливает приложение" >&2; exit 1; }
rpm -qp --requires "$built" | grep -qx 'fuse3' ||
    { echo "пакет не требует fuse3 — без fusermount3 диски не подключить" >&2; exit 1; }

mkdir -p /src/composeApp/build/distributions
cp "$built" /src/composeApp/build/distributions/
rpm -qlvp "$built" | grep "$rclone_path\$"
