"""Versioned source-independent acquisition boundary. Tenant authority stays in Java."""

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field

ASSET_TYPES = {
    "FILE",
    "DATASET",
    "TABLE",
    "VIEW",
    "SQL_TRANSFORMATION",
    "SEMANTIC_MODEL",
    "REPORT",
    "VISUAL",
    "MEASURE",
    "WORKSPACE",
}


class Column(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(min_length=1, max_length=255)
    dataType: str = Field(min_length=1, max_length=100)
    description: str | None = Field(default=None, max_length=4000)

    nullable: bool | None = None
    ordinalPosition: int | None = Field(default=None, ge=1)


class Asset(BaseModel):
    model_config = ConfigDict(extra="forbid")
    externalKey: str = Field(min_length=1, max_length=500)
    name: str = Field(min_length=1, max_length=255)
    assetType: Literal[
        "FILE",
        "DATASET",
        "TABLE",
        "VIEW",
        "SQL_TRANSFORMATION",
        "SEMANTIC_MODEL",
        "REPORT",
        "VISUAL",
        "MEASURE",
        "WORKSPACE",
    ] = "DATASET"
    columns: list[Column] = Field(default_factory=list, max_length=2000)
    metadata: dict[str, Any] = Field(default_factory=dict)


class Relationship(BaseModel):
    model_config = ConfigDict(extra="forbid")
    sourceKey: str = Field(min_length=1, max_length=500)
    sourceType: str
    targetKey: str = Field(min_length=1, max_length=500)
    targetType: str
    relationshipType: Literal[
        "REPORT_USES_MODEL",
        "VISUAL_USES_MEASURE",
        "REPORT_CONTAINS_VISUAL",
        "MODEL_CONTAINS_MEASURE",
    ]
    evidenceRef: str = Field(min_length=1, max_length=2000)


class MetadataBatch(BaseModel):
    model_config = ConfigDict(extra="forbid")
    schemaVersion: Literal[1] = 1
    # Only acquisition adapters declare authoritative completeness for their configured scope.
    snapshotComplete: bool = False
    inventoryScope: str | None = Field(default=None, pattern=r"^[a-f0-9]{64}$")
    datasets: list[Asset] = Field(default_factory=list, max_length=1000)
    warnings: list[str] = Field(default_factory=list, max_length=100)
    relationships: list[Relationship] = Field(default_factory=list, max_length=2000)
