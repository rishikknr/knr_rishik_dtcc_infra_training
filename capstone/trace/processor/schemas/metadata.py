from pydantic import BaseModel, Field


class Column(BaseModel):
    name: str
    dataType: str
    description: str | None = None


class Dataset(BaseModel):
    externalKey: str
    name: str
    columns: list[Column]


class MetadataPreview(BaseModel):
    datasets: list[Dataset]
    warnings: list[str] = Field(default_factory=list)
