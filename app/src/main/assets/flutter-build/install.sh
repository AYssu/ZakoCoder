# Run only inside the managed ARM64 Ubuntu, after explicit tool-package approval.
set -eu
[ "$(uname -m)" = aarch64 ] || { printf 'Flutter requires ARM64 Ubuntu.\n' >&2; exit 1; }
base=/opt/zakocoder
target="$base/flutter"
version=flutter3.47.9-arm64-v2
revision=c197ca5199e9ee5661e865596c9c07294dfab51c
engine=89c39edfdfae2949f6806e4a315c0c08522238b7
qemu_digest=bb3ff9cb31b508bf6ce1b3f6bb4afe2b9a4d20a625a5d102ea3d78d753e5d4d0
qemu_binary_digest=90458b484b67436a47c480fbfaad909cda316020dfb514e3ee40726521605d38
mkdir -p "$base"
test ! -L "$base"
test ! -L "$base/.flutter-install.lock"
exec 9>"$base/.flutter-install.lock"
flock -n 9 || { printf 'Flutter installation is already running.\n' >&2; exit 1; }
# Only this fixed, marked staging area is reclaimable. The lock is inherited
# by installer children, so a retry cannot remove a still-running download.
# Preserve unknown directories/symlinks rather than treating a name as ownership.
staging="$base/.flutter-install.staging"
staging_owner=zakocoder-flutter-installer-staging-v1
cleanup_staging() {
  test ! -L "$staging" && test -d "$staging" &&
  test ! -L "$staging/.owner" && test -f "$staging/.owner" &&
  test "$(cat "$staging/.owner")" = "$staging_owner" || return 1
  rm -rf -- "$staging"
}
if [ -e "$staging" ] || [ -L "$staging" ]; then
  cleanup_staging || { printf 'Refusing to remove unknown Flutter staging area.\n' >&2; exit 1; }
fi
# The SDK and its generated caches are managed together. Existing SDKs, even
# damaged managed installations, are never deleted or silently overwritten.
validate() {
  test ! -L "$1" && test -s "$1/LICENSE" &&
  printf '%s  %s\n' "$qemu_binary_digest" "$1/.zakocoder/qemu/qemu-x86_64" | sha256sum -c - &&
  test "$(git -C "$1" rev-parse HEAD)" = "$revision" &&
  test "$(cat "$1/bin/internal/engine.version")" = "$engine" &&
  "$1/bin/flutter" --version && "$1/bin/dart" --version &&
  "$1/bin/cache/artifacts/engine/android-arm64-profile/linux-arm64/gen_snapshot" --version &&
  "$1/bin/cache/artifacts/engine/android-arm64-release/linux-arm64/gen_snapshot" --version
}
if [ -e "$target" ] || [ -L "$target" ]; then
  test ! -L "$target"
  test "$(cat "$target/.ready")" = "$version"
  validate "$target"
  exit 0
fi
# Source + host SDK + engine/web caches + build headroom. Do not start a large
# download that cannot fit. Project dependency/build usage is additional.
available=$(df -Pk "$base" | awk 'END {print $4}')
case "$available" in ''|*[!0-9]*) exit 1 ;; esac
[ "$available" -ge 8388608 ] || { printf 'Flutter installation requires at least 8 GiB free.\n' >&2; exit 1; }
test -d /usr/x86_64-linux-gnu
mkdir -m 700 "$staging"
printf '%s\n' "$staging_owner" > "$staging/.owner"
trap 'cleanup_staging || :' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# Pin the real upstream Git identity, retaining LICENSE, PATENT_GRANT and all
# upstream source declarations. Never fabricate a Git revision for an archive.
git -c protocol.file.allow=never clone --depth 1 --branch 3.47.9 --single-branch \
  https://github.com/flutter/flutter.git "$staging/flutter"
sdk="$staging/flutter"
test "$(git -C "$sdk" rev-parse HEAD)" = "$revision"
test "$(cat "$sdk/bin/internal/engine.version")" = "$engine"
# Noble's system QEMU 8.2 can crash during Dart AOT under PRoot. Isolate the
# fixed static ARM64 helper: no system packages/libraries are replaced.
cache=/var/cache/zakocoder-flutter
mkdir -p "$cache"
test ! -L "$cache"
archive="$cache/$qemu_digest.deb"
test ! -L "$archive" && test ! -L "$archive.partial"
if [ -f "$archive" ] && ! printf '%s  %s\n' "$qemu_digest" "$archive" | sha256sum -c -; then
  rm -f -- "$archive"
fi
if [ ! -f "$archive" ]; then
  curl --fail --location --proto '=https' --proto-redir '=https' --connect-timeout 30 --max-time 1800 --max-filesize 134217728 --retry 1 --continue-at - \
    https://ports.ubuntu.com/ubuntu-ports/pool/universe/q/qemu/qemu-user_9.2.1+ds-1ubuntu5.2_arm64.deb -o "$archive.partial"
  if ! printf '%s  %s\n' "$qemu_digest" "$archive.partial" | sha256sum -c -; then
    rm -f -- "$archive.partial"
    exit 1
  fi
  mv "$archive.partial" "$archive"
fi
printf '%s  %s\n' "$qemu_digest" "$archive" | sha256sum -c -
dpkg-deb --extract "$archive" "$staging/qemu-package"
qemu="$sdk/.zakocoder/qemu"
mkdir -p "$qemu"
cp "$staging/qemu-package/usr/bin/qemu-x86_64" "$qemu/qemu-x86_64"
printf '%s  %s\n' "$qemu_binary_digest" "$qemu/qemu-x86_64" | sha256sum -c -
cp "$staging/qemu-package/usr/share/doc/qemu-user/copyright" "$qemu/copyright"
cat > "$qemu/SOURCE.txt" <<'SOURCES'
QEMU 9.2.1+ds-1ubuntu5.2, Ubuntu ARM64 static qemu-x86_64, unmodified.
Binary package (SHA256 bb3ff9cb31b508bf6ce1b3f6bb4afe2b9a4d20a625a5d102ea3d78d753e5d4d0):
https://ports.ubuntu.com/ubuntu-ports/pool/universe/q/qemu/qemu-user_9.2.1+ds-1ubuntu5.2_arm64.deb
Corresponding source and Ubuntu patches:
https://archive.ubuntu.com/ubuntu/pool/main/q/qemu/qemu_9.2.1+ds-1ubuntu5.2.dsc
https://archive.ubuntu.com/ubuntu/pool/main/q/qemu/qemu_9.2.1+ds.orig.tar.xz
SHA256 3d7e2940fee4ebc21ed1a39667050226aa2292f885115ebdd12c8e390071bdc5
https://archive.ubuntu.com/ubuntu/pool/main/q/qemu/qemu_9.2.1+ds-1ubuntu5.2.debian.tar.xz
SHA256 c41a0537879a711ae0cc0224160ca33c650231a266bae68da51ed8e60868af66
Upstream: https://www.qemu.org/ ; licenses and notices: copyright beside this file.
SOURCES
export FLUTTER_SUPPRESS_ANALYTICS=true DART_SUPPRESS_ANALYTICS=true CI=true
# Official Flutter downloads its matching Dart/engine artifacts over HTTPS.
# SDK updates are explicit future package changes, never an installer upgrade.
"$sdk/bin/flutter" --version
"$sdk/bin/flutter" precache --android --web --no-ios --no-linux --no-macos --no-windows
# Official Android AOT snapshotters are Linux x64. Only these two cross-compile
# helpers run through user-mode QEMU; Dart, Flutter and Gradle remain ARM64.
for mode in profile release; do
  host="$sdk/bin/cache/artifacts/engine/android-arm64-$mode"
  test -x "$host/linux-x64/gen_snapshot"
  mkdir -p "$host/linux-arm64"
  test ! -e "$host/linux-arm64/gen_snapshot"
  test ! -L "$host/linux-arm64/gen_snapshot"
  cat > "$host/linux-arm64/gen_snapshot" <<'WRAPPER'
#!/bin/sh
set -eu
host=$(CDPATH= cd -- "$(dirname -- "$0")/../linux-x64" && pwd)
sdk=$(CDPATH= cd -- "$host/../../../../../.." && pwd)
exec "$sdk/.zakocoder/qemu/qemu-x86_64" -L /usr/x86_64-linux-gnu "$host/gen_snapshot" "$@"
WRAPPER
  chmod 755 "$host/linux-arm64/gen_snapshot"
done
validate "$sdk"
printf '%s\n' "$version" > "$sdk/.ready"
# A concurrent/manual SDK must never be nested into or replaced by mv.
test ! -e "$target" && test ! -L "$target"
mv -T -n "$sdk" "$target"
test ! -e "$sdk"
printf 'Flutter installed at %s\n' "$target"
