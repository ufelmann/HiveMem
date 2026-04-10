"""Pydantic models for MCP tool inputs and outputs."""

from datetime import datetime
from pydantic import BaseModel, Field


class Drawer(BaseModel):
    id: str | None = None
    content: str
    wing: str | None = None
    room: str | None = None
    hall: str | None = None
    source: str | None = None
    tags: list[str] = Field(default_factory=list)
    valid_from: datetime | None = None
    valid_until: datetime | None = None


class Fact(BaseModel):
    id: str | None = None
    subject: str
    predicate: str
    object: str
    confidence: float = 1.0
    source_id: str | None = None
    valid_from: datetime | None = None
    valid_until: datetime | None = None


class Edge(BaseModel):
    from_entity: str
    to_entity: str
    relation: str
    weight: float = 1.0


class SearchResult(BaseModel):
    id: str
    content: str
    wing: str | None = None
    room: str | None = None
    similarity: float


class IdentityLayer(BaseModel):
    key: str
    content: str
    token_count: int | None = None
