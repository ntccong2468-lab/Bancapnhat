package com.vncode.app.integration.znack.registration;

/** Raised before the feed POST: a rejected local validation is a known unsent request. */
public final class RegistrationValidationException extends IllegalArgumentException {
    public RegistrationValidationException(String reason){super(reason);}
}
