package io.github.tieo.taghistory.apple.account

/**
 * Where an [AppleAccount] sits in the login state machine.
 *
 * Ported from the Java `LoginState` enum, which itself mirrored
 * `findmy/reports/state.py`. Numeric values are part of the persisted
 * export format — do NOT reorder without a schema migration.
 */
enum class LoginState(val value: Int) {
    LOGGED_OUT(0),
    REQUIRE_2FA(1),
    AUTHENTICATED(2),
    LOGGED_IN(3);

    companion object {
        fun fromValue(v: Int): LoginState = entries.firstOrNull { it.value == v }
            ?: throw IllegalArgumentException("Unknown login state value: $v")
    }
}
