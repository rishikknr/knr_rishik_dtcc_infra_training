package com.trace.backend.dto.response;

public record DatasetChange(
    String externalKey, String status, int newColumns, int updatedColumns, int unchangedColumns) {}
