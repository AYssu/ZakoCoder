# Run only inside the managed Ubuntu, after explicit SDK license acceptance.
set -eu
# SDK manager cannot inherit the Android app's SOCKS Authenticator. Never pass
# userinfo as a host or expose it in SDK manager diagnostics.
case "${ALL_PROXY:-}" in
  socks5://*@*) printf 'Android SDK requires HTTP or SOCKS5 without authentication.\n' >&2; exit 1 ;;
esac
[ "$(uname -m)" = aarch64 ]
base=/opt/zakocoder
target="$base/android"
version=sdk36-gradle8.13-v1
mkdir -p "$base"
# Held by the installer and children; SIGKILL releases the lock when all owned processes exit.
exec 9>"$base/.android-install.lock"
flock -n 9 || { printf 'Android installation is already running.\n' >&2; exit 1; }
for stale in "$base"/.android-install.*; do
  if [ -d "$stale" ] && [ ! -L "$stale" ]; then rm -rf -- "$stale"; fi
done
if [ -e "$target" ] || [ -L "$target" ]; then
  test ! -L "$target"
  # Never replace an unowned directory or an unknown future version.
  test "$(cat "$target/.ready")" = "$version"
  if test -s "$target/sdk/platforms/android-36/android.jar" &&
     test -s "$target/sdk/build-tools/36.0.0/lib/apksigner.jar" &&
     "$target/sdk/build-tools/36.0.0/aapt2" version &&
     GRADLE_OPTS=-Dorg.gradle.native=false "$target/gradle-8.13/bin/gradle" --version; then
    exit 0
  fi
fi
staging=$(mktemp -d "$base/.android-install.XXXXXX")
trap 'rm -rf -- "$staging"' EXIT
mkdir -p "$staging/android/sdk/cmdline-tools"
sdk="$staging/android/sdk"
fetch() {
  cache=/var/cache/zakocoder-android
  mkdir -p "$cache"
  archive="$cache/$2"
  test ! -L "$archive" || return 1
  test ! -L "$archive.partial" || return 1
  if [ -f "$archive" ] && ! printf '%s  %s\n' "$2" "$archive" | sha256sum -c -; then
    rm -f "$archive"
  fi
  if [ ! -f "$archive" ]; then
    curl --fail --location --proto '=https' --proto-redir '=https' --connect-timeout 30 --max-time 1800 --max-filesize 268435456 --retry 1 --continue-at - "$1" -o "$archive.partial"
    if ! printf '%s  %s\n' "$2" "$archive.partial" | sha256sum -c -; then
      rm -f "$archive.partial"
      return 1
    fi
    mv "$archive.partial" "$archive"
  fi
  printf '%s  %s\n' "$2" "$archive" | sha256sum -c -
  cp "$archive" "$3"
}
fetch https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \
  7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee "$staging/cmdline.zip"
unzip -q "$staging/cmdline.zip" -d "$staging/cli"
mv "$staging/cli/cmdline-tools" "$sdk/cmdline-tools/19.0"
manager="$sdk/cmdline-tools/19.0/bin/sdkmanager"
# SDK manager accepts proxy arguments rather than curl's environment variables.
set --
if [ -n "${http_proxy:-}" ]; then
  endpoint=${http_proxy#http://}
  set -- --proxy=http "--proxy_host=${endpoint%:*}" "--proxy_port=${endpoint##*:}"
elif [ -n "${ALL_PROXY:-}" ]; then
  endpoint=${ALL_PROXY#socks5://}
  set -- --proxy=socks "--proxy_host=${endpoint%:*}" "--proxy_port=${endpoint##*:}"
fi
# The caller must have obtained explicit agreement before invoking this installer.
yes | "$manager" --sdk_root="$sdk" "$@" --licenses
"$manager" --sdk_root="$sdk" "$@" 'platforms;android-36' 'build-tools;36.0.0'
origin=https://github.com/Commit451/android-arm-build-tools/releases/download/platform-tools-36.0.0
fetch "$origin/aapt2" 7512ff7e381bea6fd310b6f6e347422c8fda21e07c6e3f0162742e95eb9d7f98 "$sdk/build-tools/36.0.0/aapt2"
fetch "$origin/aidl" 8c97356b8bba8f7aad44cfd408e3ee24c66a1258244b5d08af1c9249f50dd659 "$sdk/build-tools/36.0.0/aidl"
fetch "$origin/zipalign" e8856fb24b10095eb6e940c577ce96d89ddbfc45aa0f7eeaef1597ef68f11a12 "$sdk/build-tools/36.0.0/zipalign"
fetch "$origin/split-select" fb7f0c3c87dbd4243d7e1277ba64ded2399389244058b6a4c969cc615360766c "$sdk/build-tools/36.0.0/split-select"
chmod +x "$sdk/build-tools/36.0.0/"{aapt2,aidl,zipalign,split-select}
"$sdk/build-tools/36.0.0/aapt2" version
fetch https://services.gradle.org/distributions/gradle-8.13-bin.zip \
  20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78 "$staging/gradle.zip"
unzip -q "$staging/gradle.zip" -d "$staging/android"
GRADLE_OPTS=-Dorg.gradle.native=false "$staging/android/gradle-8.13/bin/gradle" --version
printf '%s\n' "$version" > "$staging/android/.ready"
# Licenses are retained in SDK/licenses; only a fully validated install becomes active.
if [ -d "$target" ]; then
  backup=$(mktemp -d "$base/.android-backup.XXXXXX")
  rmdir "$backup"
  mv "$target" "$backup"
  if ! mv "$staging/android" "$target"; then
    mv "$backup" "$target"
    exit 1
  fi
  printf 'Previous managed SDK preserved at %s\n' "$backup"
else
  mv "$staging/android" "$target"
fi
