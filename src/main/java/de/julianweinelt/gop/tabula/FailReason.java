package de.julianweinelt.gop.authmodule.auth;

public enum FailReason {
    TOKEN_INVALID,
    TOKEN_EXPIRED,
    WRONG_CREDENTIALS,
    USERNAME_ALREADY_GIVEN
}
