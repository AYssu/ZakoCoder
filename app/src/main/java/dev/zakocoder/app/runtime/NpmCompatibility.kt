package dev.zakocoder.app.runtime

/** npm 9's cacache hard-link promotion is unreliable with the bundled PRoot link2symlink. */
object NpmCompatibility {
    const val version = "10.9.4"
    const val sha256 = "4bfba8a0c823024d1926ec9d97a37a00eb60fd2adf44b3d34a686fc32e8f51e4"
    fun supported(value: String): Boolean = value.trim().removePrefix("v").substringBefore('.').toIntOrNull()?.let { it >= 10 } == true

    /** Explicit tool installation only. Never runs on startup and never overwrites a user's launcher. */
    fun installScript(): String = """
        set -eu
        base=/usr/local/lib/zakocoder
        target="${'$'}base/npm-$version"
        mkdir -p "${'$'}base" /usr/local/bin
        for name in npm npx; do
          launcher="/usr/local/bin/${'$'}name"
          if [ -e "${'$'}launcher" ] || [ -L "${'$'}launcher" ]; then
            test -L "${'$'}launcher"
            case "${'$'}(readlink "${'$'}launcher")" in
              /usr/local/lib/zakocoder/npm-*/bin/npm-cli.js|/usr/local/lib/zakocoder/npm-*/bin/npx-cli.js) ;;
              *) printf 'Refusing to overwrite custom launcher: %s\n' "${'$'}launcher" >&2; exit 1 ;;
            esac
          fi
        done
        staging=${'$'}(mktemp -d "${'$'}base/.npm-install.XXXXXX")
        trap 'rm -rf "${'$'}staging"' EXIT
        curl --fail --location --connect-timeout 15 --max-time 180 --retry 1 \
          https://registry.npmjs.org/npm/-/npm-$version.tgz -o "${'$'}staging/npm.tgz"
        printf '%s  %s\n' '$sha256' "${'$'}staging/npm.tgz" | sha256sum -c -
        tar -xzf "${'$'}staging/npm.tgz" -C "${'$'}staging"
        test "${'$'}(node "${'$'}staging/package/bin/npm-cli.js" --version)" = '$version'
        test ! -e "${'$'}target" || test "${'$'}(node "${'$'}target/bin/npm-cli.js" --version)" = '$version'
        if [ ! -e "${'$'}target" ]; then mv "${'$'}staging/package" "${'$'}target"; fi
        ln -sfn "${'$'}target/bin/npm-cli.js" /usr/local/bin/npm
        ln -sfn "${'$'}target/bin/npx-cli.js" /usr/local/bin/npx
        hash -r
        npm --version
    """.trimIndent()
}
