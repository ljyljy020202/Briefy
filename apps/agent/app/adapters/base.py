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


@dataclass
class AdapterResult:
    postings: list[RawJobPosting] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)


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
