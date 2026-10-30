package dev.zakocoder.app.mcp

import android.content.Context
import dev.zakocoder.app.R
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Only fixed categories and numeric HTTP status leave the transport. Never retain exception text. */
enum class McpFailureReason {
    JsonSyntax, DuplicateJsonKey, ConfigShape, ConfigSize, ServerCount, UnknownField,
    Transport, Fields, Headers, Url, UrlSecrets, HttpNotAllowed, DuplicateServer,
    Dns, Connect, Tls, Network, HttpStatus, Redirect,
}

internal object McpDiagnostics {
    fun network(error: IOException): McpException = when (error) {
        is UnknownHostException -> McpException(McpProblem.Unavailable, McpFailureReason.Dns)
        is SSLException -> McpException(McpProblem.Unavailable, McpFailureReason.Tls)
        is InterruptedIOException -> McpException(McpProblem.Timeout)
        is ConnectException, is NoRouteToHostException -> McpException(McpProblem.Unavailable, McpFailureReason.Connect)
        else -> McpException(McpProblem.Unavailable, McpFailureReason.Network)
    }

    fun message(context: Context, failure: Exception): String {
        val typed = failure as? McpException
        val reason = typed?.reason
        val resource = when (reason) {
            McpFailureReason.JsonSyntax -> R.string.mcp_error_json
            McpFailureReason.DuplicateJsonKey -> R.string.mcp_error_json_duplicate
            McpFailureReason.ConfigShape -> R.string.mcp_error_shape
            McpFailureReason.ConfigSize -> R.string.mcp_error_size
            McpFailureReason.ServerCount -> R.string.mcp_error_count
            McpFailureReason.UnknownField -> R.string.mcp_error_unknown_field
            McpFailureReason.Transport -> R.string.mcp_error_transport
            McpFailureReason.Fields -> R.string.mcp_error_fields
            McpFailureReason.Headers -> R.string.mcp_error_headers
            McpFailureReason.Url -> R.string.mcp_error_url
            McpFailureReason.UrlSecrets -> R.string.mcp_error_url_secrets
            McpFailureReason.HttpNotAllowed -> R.string.mcp_error_http_blocked
            McpFailureReason.DuplicateServer -> R.string.mcp_error_duplicate_server
            McpFailureReason.Dns -> R.string.mcp_error_dns
            McpFailureReason.Connect -> R.string.mcp_error_connect
            McpFailureReason.Tls -> R.string.mcp_error_tls
            McpFailureReason.Network -> R.string.mcp_error_network
            McpFailureReason.HttpStatus -> when (typed?.httpStatus) {
                401, 403 -> R.string.mcp_error_auth
                404 -> R.string.mcp_error_not_found
                405 -> R.string.mcp_error_method
                429 -> R.string.mcp_error_rate_limit
                else -> R.string.mcp_error_http_status
            }
            McpFailureReason.Redirect -> R.string.mcp_error_redirect
            null -> when (typed?.problem) {
                McpProblem.InvalidConfig -> R.string.mcp_config_invalid
                McpProblem.Storage -> R.string.mcp_storage_error
                McpProblem.Disabled -> R.string.mcp_disabled
                McpProblem.Timeout -> R.string.mcp_timeout
                McpProblem.Protocol -> R.string.mcp_error_protocol
                McpProblem.Limit -> R.string.mcp_error_limit
                McpProblem.Closed -> R.string.mcp_error_closed
                else -> R.string.mcp_operation_failed
            }
        }
        val description = if (reason == McpFailureReason.HttpStatus) context.getString(resource, typed?.httpStatus ?: 0)
            else context.getString(resource)
        val code = reason?.name ?: typed?.problem?.name ?: "Unknown"
        return description + "\n" + context.getString(R.string.mcp_error_code, "MCP_$code")
    }
}
