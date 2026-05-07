package io.github.tieo.taghistory.apple.account

/**
 * Every failure mode the login orchestration can produce. Callers pattern
 * on [kind] so UI code can decide between "re-prompt for password" vs
 * "show a generic error" without string-matching.
 */
class AppleLoginException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    enum class Kind {
        INVALID_CREDENTIALS,
        UNHANDLED_PROTOCOL,
        INVALID_STATE,
        UNAUTHORIZED,
    }
}
