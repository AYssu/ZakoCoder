package dev.zakocoder.app.ui

/** UI hints mirror the persisted configuration contract and never coerce invalid drafts. */
internal object ModelInputValidation {
    fun penalty(value: String) = value.isBlank() || value.toDoubleOrNull()?.let { it.isFinite() && it in -2.0..2.0 } == true
    fun seed(value: String) = value.isBlank() || value.toIntOrNull() != null
    fun window(value: String) = value.toIntOrNull()?.let { it in 8192..2097152 } == true
    fun threshold(value: String) = value.toIntOrNull()?.let { it in 50..95 } == true
    fun summary(value: String) = value.toIntOrNull()?.let { it in 2000..12000 } == true
}
