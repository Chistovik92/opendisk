#!/bin/sh
#
# Собирает пакет OpenDisk для ALT Linux и того, что на нём построено —
# Simply Linux, «Альт Рабочая станция», «Альт Образование».
#
#   sh scripts/build-alt-rpm.sh <версия>
#
# Зачем отдельный пакет, если rpm уже собирается.
#
# У ALT своя система именования пакетов и зависимостей: он не производная
# Fedora и не совместим с ней по именам. Пакет, собранный jpackage под Fedora,
# на ALT либо не ставится, либо тянет не то — а выглядит это для человека как
# «установщик сломан». Ровно поэтому install.sh определяет ALT отдельно от
# прочих rpm-дистрибутивов и просит именно этот файл.
#
# Собираем в контейнере самого ALT: только у него правильные rpmbuild,
# макросы и генератор зависимостей. Никакой Java внутри не нужно — образ
# приложения со встроенной JRE уже собран снаружи.

set -eu

version="${1:?укажите версию, например 0.5.0}"

root="$(cd "$(dirname "$0")/.." && pwd)"
image_dir="$root/composeApp/build/compose/binaries/main/app/OpenDisk"
out_dir="$root/composeApp/build/distributions"

[ -d "$image_dir" ] || {
    echo "Образ приложения не собран: $image_dir" >&2
    echo "Сначала нужна задача createDistributable." >&2
    exit 1
}

mkdir -p "$out_dir"

# p10 — стабильная ветка ALT, на ней же построены актуальные Simply Linux
# и «Альт Рабочая станция». Собирать в Sisyphus (нестабильной) незачем:
# пакет от этого совместимее не станет, а сломаться может в любой день.
alt_image="${OPENDISK_ALT_IMAGE:-alt:p10}"

echo "Собираю пакет для ALT в контейнере $alt_image"

docker run --rm \
    -v "$root:/src" \
    -e VERSION="$version" \
    "$alt_image" \
    /bin/sh -eu -c '
        # rpm-build в базовом образе ALT не стоит.
        apt-get update -y >/dev/null
        apt-get install -y rpm-build sudo >/dev/null

        # ALT принципиально не даёт собирать пакеты от root:
        # «current site policy disallows root to build packages». Это его
        # политика, а не случайность, и обходить её ключами не нужно —
        # достаточно собирать от обычного пользователя, как и задумано.
        useradd -m builder 2>/dev/null || true

        # В ALT sudo настроен строго и root в sudoers не входит: «root is not
        # in the sudoers file». Разрешаем явно — контейнер одноразовый и живёт
        # ровно одну сборку, наружу это не выходит. Через su было бы проще,
        # но его в минимальном образе нет вовсе.
        echo "root ALL=(ALL) NOPASSWD: ALL" >> /etc/sudoers

        top=/home/builder/rpm
        mkdir -p "$top/BUILD" "$top/RPMS" "$top/SOURCES" "$top/SPECS" "$top/SRPMS"

        # Образ приложения кладём в дерево сборки как есть: он самодостаточен,
        # внутри своя JRE, собирать нечего — только разложить по путям.
        mkdir -p "$top/BUILD/opendisk"
        cp -a /src/composeApp/build/compose/binaries/main/app/OpenDisk/. "$top/BUILD/opendisk/"

        cat > "$top/SPECS/opendisk.spec" <<SPEC
Name: opendisk
Version: $VERSION
Release: alt1
Summary: Open cross-platform client for cloud drives
License: MIT
Group: Networking/File transfer
URL: https://github.com/Chistovik92/opendisk
BuildArch: x86_64

# The app ships its own JRE and rclone, so the only thing it needs from the
# system is FUSE. The package name is the ALT one: fuse3 in Debian, libfuse3
# here.
Requires: libfuse3

# Inside is a ready JVM image: files without sources, with foreign RPATHs and
# without debug info. ALT checks meant for packages built from sources in the
# same system fire on it for nothing.
%define _unpackaged_files_terminate_build 0
%define __find_requires %nil
%set_verify_elf_method unresolved=relaxed,rpath=relaxed
%define optflags_lto %nil
%brp_strip_none /opt/opendisk/*

%description
OpenDisk mounts cloud storage as a local virtual drive using rclone.

%install
mkdir -p %buildroot/opt/opendisk
cp -a $top/BUILD/opendisk/. %buildroot/opt/opendisk/

mkdir -p %buildroot%_bindir
ln -s /opt/opendisk/bin/OpenDisk %buildroot%_bindir/opendisk

mkdir -p %buildroot%_datadir/applications
cat > %buildroot%_datadir/applications/opendisk.desktop <<DESKTOP
[Desktop Entry]
Type=Application
Name=OpenDisk
Comment=Cloud drives
Exec=/opt/opendisk/bin/OpenDisk
Icon=opendisk
Terminal=false
Categories=Utility;FileTools;
DESKTOP

# Take the icon from the image itself: jpackage puts it next to the app.
icon=\$(find %buildroot/opt/opendisk -name "*.png" | head -n 1)
if [ -n "\$icon" ]; then
    mkdir -p %buildroot%_datadir/pixmaps
    cp "\$icon" %buildroot%_datadir/pixmaps/opendisk.png
fi

%files
/opt/opendisk
%_bindir/opendisk
%_datadir/applications/opendisk.desktop
%_datadir/pixmaps/opendisk.png

%changelog
* Mon Jan 01 2026 OpenDisk contributors <noreply@example.com> $VERSION-alt1
- Build for ALT Linux and Simply Linux
SPEC

        # Диагностика на будущее: если rpmbuild снова скажет «это не spec»,
        # первым делом надо знать, что в файле вообще оказалось.
        echo "spec: $(wc -c < "$top/SPECS/opendisk.spec") байт, первые строки:"
        head -4 "$top/SPECS/opendisk.spec"

        # Всё дерево сборки должно принадлежать тому, кто собирает: rpmbuild
        # пишет и в BUILD, и в RPMS.
        chown -R builder:builder /home/builder

        sudo -u builder rpmbuild --define "_topdir $top" -bb "$top/SPECS/opendisk.spec"

        mkdir -p /src/composeApp/build/distributions
        find "$top/RPMS" -name "*.rpm" -exec cp {} /src/composeApp/build/distributions/ \;
    '

echo "Готово:"
ls -la "$out_dir"/*alt1*.rpm
