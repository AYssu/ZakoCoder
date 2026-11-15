package dev.zakocoder.app.ssh

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.GeneralSecurityException

/** Only fixed codes leave this classifier. Never expose remote exception text or credentials. */
internal object SshConnectionFailure {
    /** Return only known protocol identifiers, never raw server/error text. */
    fun authenticationMethods(error: Throwable): String {
        val known = setOf("publickey", "password", "keyboard-interactive", "gssapi-with-mic")
        return generateSequence(error) { it.cause }.take(12).firstNotNullOfOrNull { cause ->
            Regex("^Auth (?:fail|cancel) for methods '([^']{0,256})'$").matchEntire(cause.message.orEmpty())
                ?.groupValues?.get(1)?.split(',')?.filter { it in known }?.distinct()?.joinToString(",")
        }?.takeIf { it.isNotEmpty() } ?: "unknown"
    }

    fun code(error: Throwable, stage: String): String {
        val causes = generateSequence(error) { it.cause }.take(12).toList()
        return when {
            causes.any { it is UnknownHostException } -> "dns_failed"
            causes.any { it is NoRouteToHostException } -> "network_unreachable"
            causes.any { it is ConnectException } -> "tcp_failed"
            causes.any { it is SocketTimeoutException } -> "connection_timeout"
            causes.any { it is com.jcraft.jsch.JSchAlgoNegoFailException } -> "algorithm_negotiation_failed"
            causes.any { it is GeneralSecurityException } -> "crypto_unavailable"
            causes.any { it.message?.startsWith("Auth fail") == true || it.message?.startsWith("Auth cancel") == true } -> "authentication_failed"
            stage == "authentication" -> "connection_or_auth_failed"
            else -> "handshake_failed"
        }
    }
}
