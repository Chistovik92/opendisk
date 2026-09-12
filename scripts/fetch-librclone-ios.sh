#!/bin/sh
# Кладёт Librclone.xcframework в ios/Frameworks.
#
# Библиотека не хранится в репозитории: это 80 МБ архивом и около 150 МБ
# распакованной. Собирает её .github/workflows/librclone-ios.yml и публикует
# отдельным выпуском — ровно так же, как .aar для Android и бинарник rclone
# для десктопа. Здесь она только скачивается и сверяется по SHA-256: сборка
# должна упасть, а не молча собрать приложение с подменённой библиотекой.
#
# Версия обязана совпадать с rcloneVersion в composeApp/build.gradle.kts:
# расхождение означало бы разные формы ответов RC API на телефоне и на
# компьютере при одном и том же коде разбора.
set -eu

VERSION="${LIBRCLONE_IOS_VERSION:-1.75.1}"
SHA256="75b4290693f046616945e7567c657824813f85bf2c6f9685c88e627cc61bc771"
REPO="Chistovik92/opendisk"

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
target="$root/ios/Frameworks"
archive="$target/Librclone.xcframework.zip"

if [ -d "$target/Librclone.xcframework" ] && [ -f "$archive" ]; then
    echo "Librclone.xcframework уже на месте"
    exit 0
fi

mkdir -p "$target"
url="https://github.com/$REPO/releases/download/librclone-ios-v$VERSION/Librclone.xcframework.zip"
echo "Скачиваю librclone для iOS: $url (80 МБ, это надолго)"
curl -fsSL "$url" -o "$archive"

actual=$(shasum -a 256 "$archive" | cut -d' ' -f1)
if [ "$actual" != "$SHA256" ]; then
    echo "SHA-256 Librclone.xcframework.zip не совпала." >&2
    echo "  ожидалась: $SHA256" >&2
    echo "  получена:  $actual" >&2
    exit 1
fi

rm -rf "$target/Librclone.xcframework"
unzip -q "$archive" -d "$target"
echo "Готово: $target/Librclone.xcframework"
