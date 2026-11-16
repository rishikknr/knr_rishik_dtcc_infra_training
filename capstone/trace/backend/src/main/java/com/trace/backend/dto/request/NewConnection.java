package com.trace.backend.dto.request;

import java.util.UUID;

public record NewConnection(String name, UUID ownerTeamId) {}
