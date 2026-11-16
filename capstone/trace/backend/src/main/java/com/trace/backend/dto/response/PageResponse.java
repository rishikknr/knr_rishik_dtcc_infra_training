package com.trace.backend.dto.response;

import java.util.List;

public record PageResponse<T>(List<T> items, long total, int limit, int offset, boolean hasMore) {}
