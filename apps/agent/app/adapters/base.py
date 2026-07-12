from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import date, datetime

from app.schemas.collection import CollectionOptions, SeedKeywords


@dataclass
class RawJobPosting:
    source: str
    source_url: str
    company_name: str
    title: str
    position: str | None = None
    employment_type: str | None = None
    experience_level: str | None = None
    location: str | None = None
    deadline: date | None = None
    skills: list[str] = field(default_factory=list)
    roles: list[str] = field(default_factory=list)
    description: str | None = None
    posted_at: datetime | None = None
    source_external_id: str | None = None


@dataclass
class AdapterSourceStats:
    """Per-source collection counts for pipeline diagnostics."""

    discovered: int = 0  # sitemap URLs passing the lookback filter
    fetched: int = 0  # page HTTP requests attempted
    parsed: int = 0  # pages that yielded a valid RawJobPosting
    selected: int = 0  # postings returned after budget/relevance selection


@dataclass
class AdapterResult:
    postings: list[RawJobPosting] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    source_stats: AdapterSourceStats | None = None


class JobBoardAdapter(ABC):
    @property
    @abstractmethod
    def source_name(self) -> str: ...

    @abstractmethod
    async def fetch(
        self,
        seed_keywords: SeedKeywords,
        options: CollectionOptions,
        collect_date: date | None = None,
    ) -> AdapterResult: ...
