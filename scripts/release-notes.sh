#!/bin/sh
# Текст выпуска: таблица «что качать» и заметки к версии.
#
#   sh scripts/release-notes.sh v0.5.5 > body.md
#
# Таблица строится из файлов, которые действительно лежат в выпуске, а не
# из списка «что должно быть»: ссылка на файл, которого нет, хуже пустой
# клетки. Заметки — из docs/releases/<версия>.md, если такой файл есть.
#
# Устроено по образцу выпусков RustDesk: сверху таблица архитектура × система,
# ниже подсказки, как понять, что подходит, и только потом — что нового.
set -eu

tag="${1:?укажите тег, например v0.5.5}"
repo="${GH_REPO:-Chistovik92/opendisk}"
version="${tag#v}"
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

assets=$(gh release view "$tag" --repo "$repo" --json assets --jq '.assets[].name')
base="https://github.com/$repo/releases/download/$tag"

# Ссылка на файл с этим окончанием имени — или пусто, если его в выпуске нет.
link() {
    name=$(printf '%s\n' "$assets" | grep -- "$1\$" | head -n 1 || true)
    [ -n "$name" ] && printf '[%s](%s/%s)' "$2" "$base" "$name"
    return 0
}

x64_exe=$(link -x64.exe EXE)
arm_exe=$(link -arm64.exe EXE)
x64_dmg=$(link -x64.dmg DMG)
arm_dmg=$(link -arm64.dmg DMG)
deb=$(link _amd64.deb DEB)
rpm=$(link -1.x86_64.rpm RPM)
alt=$(link alt1.x86_64.rpm RPM)
appimage=$(link -x86_64.AppImage AppImage)
apk_arm=$(link -arm64.apk APK)
apk_all=$(link -universal.apk 'APK (универсальный)')
ipa=$(link .ipa 'IPA ¹')

cat <<EOF
## Скачать

| Процессор | Windows | macOS | Debian, Ubuntu, Mint, Astra | Fedora, RHEL, RED OS, openSUSE | ALT, Simply Linux | Любой Linux | Android | iPhone, iPad |
|---|---|---|---|---|---|---|---|---|
| **x86-64** — Intel, AMD | $x64_exe | $x64_dmg | $deb | $rpm | $alt | $appimage | $apk_all | |
| **ARM64** — Snapdragon, Apple Silicon, телефоны | $arm_exe | $arm_dmg | | | | | $apk_arm | $ipa |

**Какой у меня процессор.** Windows: «Параметры» → «Система» → «О системе»,
строка «Тип системы». Mac: меню Apple → «Об этом Mac» — «Чип Apple M…» значит
Apple Silicon, «Процессор Intel» — Intel. Телефоны на Android почти все ARM64;
если apk не встал, берите универсальный — он вдвое больше, но подходит любому.

**Linux одной командой** — скрипт сам определит дистрибутив, возьмёт пакет
именно под него и сверит контрольную сумму:

\`\`\`bash
curl -fsSL https://raw.githubusercontent.com/$repo/main/scripts/install.sh | sh
\`\`\`

¹ Без подписи Apple: ставится через AltStore, SideStore или Sideloadly, подпись
живёт семь дней. Как и чем — [docs/IOS.md](https://github.com/$repo/blob/main/docs/IOS.md).

Установщики для Windows, Linux и Android перед выпуском ставятся на настоящие
системы. \`.dmg\` и \`.ipa\` только собираются — ни Mac, ни iPhone для проверки
у разработчиков нет. Контрольные суммы — в файлах \`SHA256SUMS-*\`.

EOF

notes="$root/docs/releases/$version.md"
if [ -f "$notes" ]; then
    cat "$notes"
fi
