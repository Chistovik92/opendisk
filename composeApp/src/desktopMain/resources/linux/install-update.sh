#!/bin/sh
# Установка обновления OpenDisk на Linux.
#
#   sh install-update.sh <deb|rpm|rpm-alt|appimage> <файл> <лаунчер> <pid приложения>
#
# Запускает приложение (UpdateInstaller) и сразу выходит само — так же, как
# на Windows: сначала отключаются диски и гасится rclone, потом ставится
# пакет. Сценарий ждёт выхода приложения, ставит скачанный и уже сверенный
# файл и запускает OpenDisk обратно — даже если в правах отказали, чтобы
# человек не остался без приложения.
#
# Пакеты ставятся теми же командами, что и scripts/install.sh: apt-get берёт
# зависимости сам, а без него остаётся dpkg/rpm. Права спрашивает pkexec —
# окном системы, а не в терминале, которого у приложения нет.
set -u

kind="$1"
file="$2"
launcher="$3"
pid="${4:-}"

# Приложение выходит само, но не мгновенно: отключает диски. Минуты хватает
# с запасом; дольше ждать нельзя — человек смотрит на пустой экран.
waited=0
while [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && [ "$waited" -lt 60 ]; do
    sleep 1
    waited=$((waited + 1))
done

as_root() {
    if [ "$(id -u)" -eq 0 ]; then "$@"; else pkexec "$@"; fi
}

code=0
case "$kind" in
    appimage)
        # AppImage — один файл: кладём новый рядом и подменяем переименованием,
        # чтобы оборвавшееся копирование не оставило половину программы.
        # Лежит в системном каталоге (install.sh кладёт в /usr/local/bin) —
        # тогда через pkexec.
        if cp "$file" "$launcher.new" 2>/dev/null &&
           chmod 0755 "$launcher.new" &&
           mv -f "$launcher.new" "$launcher"; then
            :
        else
            rm -f "$launcher.new" 2>/dev/null
            as_root install -m 0755 "$file" "$launcher" || code=$?
        fi
        ;;
    deb)
        as_root sh -c 'if command -v apt-get >/dev/null 2>&1; then apt-get install -y "$1"; else dpkg -i "$1"; fi' \
            sh "$file" || code=$?
        ;;
    rpm-alt)
        as_root sh -c 'if command -v apt-get >/dev/null 2>&1; then apt-get install -y "$1"; else rpm -Uvh "$1"; fi' \
            sh "$file" || code=$?
        ;;
    rpm)
        as_root sh -c '
            if command -v dnf >/dev/null 2>&1; then dnf install -y "$1"
            elif command -v zypper >/dev/null 2>&1; then zypper --non-interactive install --allow-unsigned-rpm "$1"
            elif command -v yum >/dev/null 2>&1; then yum install -y "$1"
            else rpm -Uvh "$1"
            fi' sh "$file" || code=$?
        ;;
    *)
        echo "неизвестный вид установки: $kind" >&2
        code=2
        ;;
esac
echo "установка ($kind): код $code"

if [ -n "$launcher" ] && [ -x "$launcher" ]; then
    if command -v setsid >/dev/null 2>&1; then
        setsid "$launcher" >/dev/null 2>&1 </dev/null &
    else
        nohup "$launcher" >/dev/null 2>&1 </dev/null &
    fi
fi
exit "$code"
