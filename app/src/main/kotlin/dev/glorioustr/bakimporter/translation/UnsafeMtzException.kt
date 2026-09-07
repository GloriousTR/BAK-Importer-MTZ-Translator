package dev.glorioustr.bakimporter.translation

class UnsafeMtzException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Reason {
        NOT_A_REGULAR_FILE,
        ARCHIVE_TOO_LARGE,
        INVALID_ZIP,
        UNSUPPORTED_ZIP64,
        TOO_MANY_ENTRIES,
        UNSAFE_PATH,
        DUPLICATE_PATH,
        SYMLINK,
        ENTRY_TOO_LARGE,
        TOTAL_SIZE_EXCEEDED,
        SUSPICIOUS_COMPRESSION_RATIO,
        TRUNCATED_ENTRY,
        METADATA_TOO_LARGE,
        UNSAFE_XML,
    }
}
