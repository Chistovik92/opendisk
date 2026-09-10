# OpenDisk package for ALT Linux and distributions built on it
# (Simply Linux, Alt Workstation, Alt Education).
#
# ASCII only, on purpose. ALT ships an old rpm branch whose "is this a spec
# file at all?" check reads the head of the file and rejects any byte that is
# not printable in the C locale. Cyrillic there makes rpmbuild answer
# "does not appear to be a specfile" before parsing even starts.
#
# @VERSION@, @IMAGE@ and @DATE@ are filled in by scripts/alt/build-in-container.sh.

Name: opendisk
Version: @VERSION@
Release: alt1
Summary: Open cross-platform client for cloud drives
License: MIT
Group: Networking/File transfer
URL: https://github.com/Chistovik92/opendisk
BuildArch: x86_64

# The app ships its own JRE and rclone. The only thing it needs from the
# system is FUSE, and specifically fusermount3: rclone mounts through it as
# an ordinary user. In ALT that binary lives in the fuse3 package; libfuse3
# alone (what 0.5.0 required) carries only the library.
Requires: fuse3

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
cp -a @IMAGE@/. %buildroot/opt/opendisk/

# jpackage leaves the bundled rclone with mode 644, i.e. not executable. The
# app cannot fix that in place: /opt belongs to root. The deb and the Fedora
# rpm get the same fix in their own build steps.
chmod 755 %buildroot/opt/opendisk/lib/app/resources/rclone

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
icon=$(find %buildroot/opt/opendisk -name "*.png" | head -n 1)
if [ -n "$icon" ]; then
    mkdir -p %buildroot%_datadir/pixmaps
    cp "$icon" %buildroot%_datadir/pixmaps/opendisk.png
fi

%pre
# Same as the Fedora package (scripts/repack-rpm.sh): on upgrade, stop the
# running copy, or it keeps running the old code - files in Linux are
# replaced under a running process, and to the user the upgrade simply does
# not happen. Remember whose copy it was, to bring it back afterwards.
if [ "$1" -ge 2 ]; then
    rm -f /run/opendisk-restart.user /run/opendisk-restart.env
    for pid in $(pgrep -f "^/opt/opendisk/bin/OpenDisk" 2>/dev/null); do
        # %%U, not %U: rpm expands macros inside scriptlets too.
        stat -c %%U "/proc/$pid" > /run/opendisk-restart.user 2>/dev/null || continue
        tr '\0' '\n' < "/proc/$pid/environ" 2>/dev/null |
            grep -E '^(DISPLAY|WAYLAND_DISPLAY|XAUTHORITY|DBUS_SESSION_BUS_ADDRESS|XDG_RUNTIME_DIR)=' \
            > /run/opendisk-restart.env 2>/dev/null || true
        break
    done

    # Gently first: on TERM the app unmounts its drives and stops its rclone.
    pkill -TERM -f "^/opt/opendisk/bin/OpenDisk" 2>/dev/null || true
    i=0
    while [ $i -lt 15 ] && pgrep -f "^/opt/opendisk/bin/OpenDisk" >/dev/null 2>&1; do
        sleep 1
        i=$((i + 1))
    done
    pkill -KILL -f "^/opt/opendisk/bin/OpenDisk" 2>/dev/null || true

    # A child rclone may outlive a crashed parent and keep drives mounted.
    pkill -TERM -f "^/opt/opendisk/lib/app/resources/rclone" 2>/dev/null || true
fi

%post
# Bring the app back if we closed it for the upgrade: the new files are in
# place by now. Same user, same session - otherwise there is nowhere to open
# the window. Best effort: if it fails, the user starts the app by hand, which
# is unpleasant but not broken.
#
# %post, not %posttrans as in the Fedora package: ALT's rpm branch has no
# %posttrans at all ("Macro %posttrans not found"). Fedora needs it only
# because the old jpackage package's %preun removes the menu entry after the
# new %post; the ALT package ships its .desktop file directly and has no
# such %preun, so %post is enough.
if [ -s /run/opendisk-restart.user ]; then
    od_user=$(cat /run/opendisk-restart.user)
    {
        echo '#!/bin/sh'
        echo 'set -a'
        echo '[ -f /run/opendisk-restart.env ] && . /run/opendisk-restart.env'
        echo 'set +a'
        echo 'nohup /opt/opendisk/bin/OpenDisk >/dev/null 2>&1 &'
    } > /run/opendisk-restart.sh
    chmod 755 /run/opendisk-restart.sh
    su "$od_user" -c /run/opendisk-restart.sh >/dev/null 2>&1 || true
    rm -f /run/opendisk-restart.user /run/opendisk-restart.env /run/opendisk-restart.sh
fi

%files
/opt/opendisk
%_bindir/opendisk
%_datadir/applications/opendisk.desktop
%_datadir/pixmaps/opendisk.png

%changelog
* @DATE@ OpenDisk contributors <noreply@example.com> @VERSION@-alt1
- Build for ALT Linux and Simply Linux
