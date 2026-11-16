package com.trace.backend.dto.response;

public record RegistrationOptions(
    boolean enabled, int minimumPasswordLength, int maximumPasswordBytes) {}
