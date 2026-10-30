package dev.zakocoder.app.ssh

import android.content.Context
import dev.zakocoder.app.R

fun sshError(context: Context, code: String?): String? = code?.let {
    context.getString(when (it) {
        "invalid_profile", "invalid_host", "invalid_port", "invalid_username", "invalid_directory", "invalid_argument" -> R.string.ssh_error_profile
        "host_key_untrusted", "host_key_missing", "host_key_probe_required" -> R.string.ssh_error_untrusted
        "host_key_changed" -> R.string.ssh_error_changed
        "host_key_probe_expired", "configuration_changed" -> R.string.ssh_error_probe_expired
        "credential_missing" -> R.string.ssh_error_credential
        "private_key_invalid" -> R.string.ssh_error_key
        "connection_failed", "connection_or_auth_failed" -> R.string.ssh_error_connect
        "command_timeout" -> R.string.ssh_error_timeout
        "profile_missing" -> R.string.ssh_error_missing
        "invalid_path", "unsafe_path", "not_regular_file", "not_directory" -> R.string.ssh_error_path
        "file_changed" -> R.string.ssh_error_conflict
        "file_missing" -> R.string.ssh_error_file_missing
        "file_too_large", "credential_limit", "storage_limit", "profile_limit", "task_limit" -> R.string.ssh_error_limit
        "not_utf8" -> R.string.ssh_error_utf8
        "empty_match", "ambiguous_match" -> R.string.ssh_error_match
        "atomic_replace_unavailable" -> R.string.ssh_error_atomic
        "tmux_disabled" -> R.string.ssh_error_tmux_disabled
        "task_not_owned", "invalid_task" -> R.string.ssh_error_task
        "invalid_command", "invalid_timeout", "invalid_range", "invalid_input", "invalid_digest", "unsupported_action" -> R.string.ssh_error_arguments
        "storage_invalid" -> R.string.ssh_error_storage
        else -> R.string.ssh_error_generic
    })
}
