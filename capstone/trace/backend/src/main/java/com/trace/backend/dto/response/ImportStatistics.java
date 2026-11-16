package com.trace.backend.dto.response;

public record ImportStatistics(
    int datasets,
    int columns,
    int newDatasets,
    int updatedDatasets,
    int unchangedDatasets,
    int newColumns,
    int updatedColumns,
    int unchangedColumns) {}
