# Run only inside the managed Ubuntu. The caller confirms community NDK installation.
set -eu
[ "$(uname -m)" = aarch64 ]
base=/opt/zakocoder
target="$base/android-ndk-r28c"
version=ndk28c-arm64-v1
sdk="$base/android/sdk"
mkdir -p "$base"
# Share the SDK installer lock: SDK repair and NDK registration cannot race.
exec 9>"$base/.android-install.lock"
flock -n 9 || { printf 'Android installation is already running.\n' >&2; exit 1; }
test "$(cat "$base/android/.ready")" = sdk36-gradle8.13-v1
test -s "$sdk/platforms/android-36/android.jar"
test ! -L "$sdk/ndk"
if [ -e "$sdk/ndk" ]; then test -d "$sdk/ndk"; fi
for stale in "$base"/.ndk-install.*; do
  if [ -d "$stale" ] && [ ! -L "$stale" ]; then rm -rf -- "$stale"; fi
done
register_ndk() {
  test ! -L "$sdk/ndk"
  mkdir -p "$sdk/ndk"
  link="$sdk/ndk/28.2.13676358"
  if [ -L "$link" ]; then
    test "$(readlink "$link")" = "$target"
  elif [ -e "$link" ]; then
    printf 'Refusing to replace an existing NDK.\n' >&2
    return 1
  else
    ln -s "$target" "$link"
  fi
}
validate() {
  test -s "$1/NOTICE" &&
  test -s "$1/build/cmake/android.toolchain.cmake" &&
  grep -qx 'Pkg.Revision = 28.2.13676358' "$1/source.properties" &&
  "$1/toolchains/llvm/prebuilt/linux-arm64/bin/clang" --version &&
  "$1/toolchains/llvm/prebuilt/linux-arm64/bin/ld.lld" --version &&
  cmake --version && ninja --version
}
if [ -e "$target" ] || [ -L "$target" ]; then
  test ! -L "$target"
  test "$(cat "$target/.ready")" = "$version"
  if validate "$target"; then register_ndk; exit 0; fi
fi
# Check registration ownership before a large download or replacing a damaged install.
if [ -e "$sdk/ndk/28.2.13676358" ] || [ -L "$sdk/ndk/28.2.13676358" ]; then
  test -L "$sdk/ndk/28.2.13676358"
  test "$(readlink "$sdk/ndk/28.2.13676358")" = "$target"
fi
staging=$(mktemp -d "$base/.ndk-install.XXXXXX")
trap 'rm -rf -- "$staging"' EXIT
cache=/var/cache/zakocoder-android
mkdir -p "$cache"
digest=3e961b2dac764b0e326b271307e7059c2ef1ce3dac5223e75302400727cc046b
archive="$cache/$digest"
test ! -L "$archive"
test ! -L "$archive.partial"
if [ -f "$archive" ] && ! printf '%s  %s\n' "$digest" "$archive" | sha256sum -c -; then rm -f "$archive"; fi
if [ ! -f "$archive" ]; then
  curl --fail --location --proto '=https' --proto-redir '=https' --connect-timeout 30 --max-time 1800 --max-filesize 134217728 --retry 1 --continue-at - \
    https://github.com/HomuHomu833/android-ndk-custom/releases/download/r28/android-ndk-r28c-aarch64-linux-gnu.tar.xz -o "$archive.partial"
  if ! printf '%s  %s\n' "$digest" "$archive.partial" | sha256sum -c -; then rm -f "$archive.partial"; exit 1; fi
  mv "$archive.partial" "$archive"
fi
printf '%s  %s\n' "$digest" "$archive" | sha256sum -c -
# Python's data filter rejects escaping links, special files and unsafe paths.
python3 - "$archive" "$staging" <<'PYTHON'
import pathlib, sys, tarfile
with tarfile.open(sys.argv[1], 'r:xz') as archive:
    members = archive.getmembers()
    if len(members) > 20000 or sum(m.size for m in members) > 2 * 1024**3:
        raise ValueError('NDK archive exceeds extraction limits')
    for member in members:
        path = pathlib.PurePosixPath(member.name)
        if path.is_absolute() or '..' in path.parts or not path.parts or path.parts[0] != 'android-ndk-r28c':
            raise ValueError('Invalid NDK archive path')
    archive.extractall(sys.argv[2], members=members, filter='data')
PYTHON
validate "$staging/android-ndk-r28c"
printf '%s\n' "$version" > "$staging/android-ndk-r28c/.ready"
if [ -d "$target" ]; then
  backup=$(mktemp -d "$base/.ndk-backup.XXXXXX")
  rmdir "$backup"
  mv "$target" "$backup"
  if ! mv "$staging/android-ndk-r28c" "$target"; then mv "$backup" "$target"; exit 1; fi
  printf 'Previous managed NDK preserved at %s\n' "$backup"
else
  mv "$staging/android-ndk-r28c" "$target"
fi
register_ndk
