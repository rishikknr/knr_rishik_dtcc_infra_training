package com.trace.backend.dto.response;

import java.util.UUID;

public record RegistrationResponse(UUID id, String displayName, String email, String message) {}
