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
#
# Сама сборка — scripts/alt/build-in-container.sh, описание пакета —
# scripts/alt/opendisk.spec.

set -eu

version="${1:?укажите версию, например 0.5.2}"

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
    sh /src/scripts/alt/build-in-container.sh

echo "Готово:"
ls -la "$out_dir"/*alt1*.rpm
