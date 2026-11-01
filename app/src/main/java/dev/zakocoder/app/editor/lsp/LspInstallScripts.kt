package dev.zakocoder.app.editor.lsp

/** Fixed upstream downloads. Apt prerequisites are installed by Workbench after user confirmation. */
internal object LspInstallScripts {
    private fun staged(name: String, version: String, body: String): String = """
        set -eu
        umask 022
        base=/opt/zakocoder/lsp/@NAME@
        for parent in /opt /opt/zakocoder /opt/zakocoder/lsp "${'$'}base"; do test ! -L "${'$'}parent"; done
        mkdir -p "${'$'}base"
        test ! -L "${'$'}base/.install.lock"
        exec 9>>"${'$'}base/.install.lock"
        flock -x 9
        target="${'$'}base/@VERSION@"
        # Recover only stages created by this installer, while holding its lock.
        for previous in "${'$'}base"/.stage.*; do
          if [ -d "${'$'}previous" ] && [ ! -L "${'$'}previous" ] && [ ! -L "${'$'}previous/.zakocoder-stage" ] &&
             [ -f "${'$'}previous/.zakocoder-stage" ] && [ "${'$'}(cat "${'$'}previous/.zakocoder-stage")" = lsp-stage-v1 ]; then
            rm -rf -- "${'$'}previous"
          fi
        done
        if [ -e "${'$'}target" ] || [ -L "${'$'}target" ]; then
          test ! -L "${'$'}target"
          test "${'$'}(cat "${'$'}target/.zakocoder-verified")" = '@VERSION@'
          exit 0
        fi
        stage=${'$'}(mktemp -d "${'$'}base/.stage.XXXXXXXX")
        trap 'rm -rf -- "${'$'}stage"' EXIT
        trap 'exit 130' INT
        trap 'exit 143' HUP TERM
        printf '%s\n' lsp-stage-v1 > "${'$'}stage/.zakocoder-stage"
        mkdir "${'$'}stage/server"
        @BODY@
        printf '%s\n' '@VERSION@' > "${'$'}stage/server/.zakocoder-verified"
        mv -T "${'$'}stage/server" "${'$'}target"
    """.trimIndent().replace("@NAME@", name).replace("@VERSION@", version).replace("@BODY@", body)

    private fun download(url: String, hash: String, file: String = "archive.tar.gz") = """
        curl --fail --location --retry 3 --connect-timeout 20 --max-time 1200 --proto '=https' --proto-redir '=https' '$url' -o "${'$'}stage/$file"
        printf '%s  %s\n' '$hash' "${'$'}stage/$file" | sha256sum -c -
    """.trimIndent()

    val java: String = staged("java", "1.46.1", download(
        "https://download.eclipse.org/jdtls/milestones/1.46.1/jdt-language-server-1.46.1-202504011455.tar.gz",
        "f435fdf6db3aa0d1598ef0f11f80bb20e09e67009001382719c313eff07de02c") + "\n" + """
        tar --no-same-owner --no-same-permissions -xzf "${'$'}stage/archive.tar.gz" -C "${'$'}stage/server"
        test -f "${'$'}stage/server/config_linux/config.ini"
        test -f "${'$'}stage/server/plugins/org.eclipse.equinox.launcher_1.7.0.v20250331-1702.jar"
    """.trimIndent())

    val kotlin: String = staged("kotlin", "263.7084.0", """
        case "${'$'}(dpkg --print-architecture)" in
          arm64) url='https://download.jetbrains.com/language-server/kotlin-server/263.7084.0/kotlin-server-263.7084.0-aarch64.tar.gz'
                 hash='450ab7c1d80e48106aa429b48cb4226d82ca12a8a2400447ae02386794780cac' ;;
          *) printf '%s\n' 'Kotlin language service currently requires Ubuntu ARM64' >&2; exit 1 ;;
        esac
        curl --fail --location --retry 3 --connect-timeout 20 --max-time 1200 --proto '=https' --proto-redir '=https' "${'$'}url" -o "${'$'}stage/archive.tar.gz"
        printf '%s  %s\n' "${'$'}hash" "${'$'}stage/archive.tar.gz" | sha256sum -c -
        tar --no-same-owner --no-same-permissions -xzf "${'$'}stage/archive.tar.gz" --strip-components=1 -C "${'$'}stage/server"
        test -x "${'$'}stage/server/bin/intellij-server"
        test -x "${'$'}stage/server/jbr/bin/java"
        # Adjust only this verified staged distribution; retain its license and JBR.
        sed -i -e 's/^-Xmx2048m$/-Xmx1024m/' -e 's/ReservedCodeCacheSize=512m/ReservedCodeCacheSize=256m/' -e 's/^-XX:+HeapDumpOnOutOfMemoryError$/-XX:-HeapDumpOnOutOfMemoryError/' "${'$'}stage/server/bin/intellij-server.vmoptions"
    """.trimIndent())

    val rust: String = staged("rust", "2026-10-26", """
        case "${'$'}(dpkg --print-architecture)" in
          arm64) triple=aarch64-unknown-linux-gnu; hash=1b225c4c9ae329ca96e8a75a5bbcb5562a7f1d25c57c8b55d3e02299a3dbe836 ;;
          amd64) triple=x86_64-unknown-linux-gnu; hash=4c83773525f93b636ed79761389568b4d2a89c05fa7ecc440245032d49ad80ce ;;
          *) printf '%s\n' 'Unsupported rust-analyzer architecture' >&2; exit 1 ;;
        esac
        curl --fail --location --retry 3 --connect-timeout 20 --max-time 1200 --proto '=https' --proto-redir '=https' "https://github.com/rust-lang/rust-analyzer/releases/download/2026-10-26/rust-analyzer-${'$'}triple.gz" -o "${'$'}stage/server.gz"
        printf '%s  %s\n' "${'$'}hash" "${'$'}stage/server.gz" | sha256sum -c -
        gzip -dc "${'$'}stage/server.gz" > "${'$'}stage/server/rust-analyzer"
        chmod 755 "${'$'}stage/server/rust-analyzer"
        "${'$'}stage/server/rust-analyzer" --version
    """.trimIndent())

    val javaCommand: String = """
        set -eu
        server=/opt/zakocoder/lsp/java/1.46.1
        cache=/root/.cache/zakocoder/lsp/java/${'$'}(printf '%s' "${'$'}PWD" | sha256sum | cut -d ' ' -f 1)
        mkdir -p "${'$'}cache"
        config=config_linux
        [ "${'$'}(dpkg --print-architecture)" != arm64 ] || config=config_linux_arm
        java=/usr/lib/jvm/java-21-openjdk-${'$'}(dpkg --print-architecture)/bin/java
        exec "${'$'}java" -Declipse.application=org.eclipse.jdt.ls.core.id1 -Dosgi.bundles.defaultStartLevel=4 -Declipse.product=org.eclipse.jdt.ls.core.product -Dlog.protocol=false -Xms64m -Xmx768m --add-modules=ALL-SYSTEM --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED -jar "${'$'}server/plugins/org.eclipse.equinox.launcher_1.7.0.v20250331-1702.jar" -configuration "${'$'}server/${'$'}config" -data "${'$'}cache"
    """.trimIndent()
}
