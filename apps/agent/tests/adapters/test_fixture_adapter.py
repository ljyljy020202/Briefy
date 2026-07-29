from datetime import date

from app.adapters.base import AdapterResult, JobBoardAdapter, RawJobPosting
from app.adapters.fixture import FixtureAdapter
from app.schemas.collection import CollectionOptions, SeedKeywords

_COLLECT_DATE = date(2026, 7, 2)

_FULL_KEYWORDS = SeedKeywords(
    roles=["백엔드 개발자", "풀스택 개발자"],
    companies=["네이버", "카카오"],
    skills=["Spring Boot", "Java"],
    locations=["서울"],
    experience_levels=["신입"],
    employment_types=["정규직"],
)

_EMPTY_KEYWORDS = SeedKeywords()

_DEFAULT_OPTIONS = CollectionOptions()


# ── RawJobPosting ─────────────────────────────────────────────────────────────


def test_raw_job_posting_required_fields():
    p = RawJobPosting(
        source="test", source_url="https://x.com/1", company_name="A", title="T"
    )
    assert p.source == "test"
    assert p.source_url == "https://x.com/1"
    assert p.company_name == "A"
    assert p.title == "T"


def test_raw_job_posting_optional_fields_default_to_none_or_empty():
    p = RawJobPosting(
        source="test", source_url="https://x.com/1", company_name="A", title="T"
    )
    assert p.position is None
    assert p.employment_type is None
    assert p.experience_level is None
    assert p.location is None
    assert p.deadline is None
    assert p.skills == []
    assert p.roles == []
    assert p.description is None
    assert p.posted_at is None


# ── AdapterResult ─────────────────────────────────────────────────────────────


def test_adapter_result_defaults_empty():
    result = AdapterResult()
    assert result.postings == []
    assert result.warnings == []


def test_adapter_result_carries_warnings():
    result = AdapterResult(warnings=["source timed out"])
    assert result.warnings == ["source timed out"]


# ── JobBoardAdapter interface ─────────────────────────────────────────────────


def test_fixture_adapter_implements_job_board_adapter():
    adapter = FixtureAdapter()
    assert isinstance(adapter, JobBoardAdapter)


def test_fixture_adapter_source_name():
    adapter = FixtureAdapter()
    assert adapter.source_name == "fixture"


# ── FixtureAdapter.fetch ──────────────────────────────────────────────────────


async def test_fixture_adapter_returns_adapter_result(monkeypatch):
    monkeypatch.setattr("app.adapters.fixture.date", _MockDate)
    adapter = FixtureAdapter()
    result = await adapter.fetch(_FULL_KEYWORDS, _DEFAULT_OPTIONS)
    assert isinstance(result, AdapterResult)
    assert isinstance(result.postings, list)
    assert result.warnings == []


async def test_fixture_adapter_returns_at_least_3_postings(monkeypatch):
    monkeypatch.setattr("app.adapters.fixture.date", _MockDate)
    adapter = FixtureAdapter()
    result = await adapter.fetch(_FULL_KEYWORDS, _DEFAULT_OPTIONS)
    assert len(result.postings) >= 3


async def test_fixture_adapter_postings_are_raw_job_postings(monkeypatch):
    monkeypatch.setattr("app.adapters.fixture.date", _MockDate)
    adapter = FixtureAdapter()
    result = await adapter.fetch(_FULL_KEYWORDS, _DEFAULT_OPTIONS)
    for posting in result.postings:
        assert isinstance(posting, RawJobPosting)


async def test_fixture_adapter_source_is_fixture(monkeypatch):
    monkeypatch.setattr("app.adapters.fixture.date", _MockDate)
    adapter = FixtureAdapter()
    result = await adapter.fetch(_FULL_KEYWORDS, _DEFAULT_OPTIONS)
    for posting in result.postings:
        assert posting.source == "fixture"


async def test_fixture_adapter_required_fields_are_non_empty(monkeypatch):
    monkeypatch.setattr("app.adapters.fixture.date", _MockDate)
    adapter = FixtureAdapter()
    result = await adapter.fetch(_FULL_KEYWORDS, _DEFAULT_OPTIONS)
    for posting in result.postings:
        assert posting.source_url
        assert posting.company_name
        assert posting.title


async def test_fixture_adapter_reflects_seed_companies(monkeypatch):
    monkeypatch.setattr("app.adapters.fixture.date", _MockDate)
    adapter = FixtureAdapter()
    result = await adapter.fetch(_FULL_KEYWORDS, _DEFAULT_OPTIONS)
    companies = {p.company_name for p in result.postings}
    assert companies & {"네이버", "카카오"}


async def test_fixture_adapter_reflects_seed_roles(monkeypatch):
    monkeypatch.setattr("app.adapters.fixture.date", _MockDate)
    adapter = FixtureAdapter()
    result = await adapter.fetch(_FULL_KEYWORDS, _DEFAULT_OPTIONS)
    positions = {p.position for p in result.postings}
    assert positions & {"백엔드 개발자", "풀스택 개발자"}


async def test_fixture_adapter_output_is_deterministic(monkeypatch):
    monkeypatch.setattr("app.adapters.fixture.date", _MockDate)
    adapter = FixtureAdapter()
    r1 = await adapter.fetch(_FULL_KEYWORDS, _DEFAULT_OPTIONS)
    r2 = await adapter.fetch(_FULL_KEYWORDS, _DEFAULT_OPTIONS)
    urls_1 = [p.source_url for p in r1.postings]
    urls_2 = [p.source_url for p in r2.postings]
    assert urls_1 == urls_2


async def test_fixture_adapter_works_with_empty_seed_keywords(monkeypatch):
    monkeypatch.setattr("app.adapters.fixture.date", _MockDate)
    adapter = FixtureAdapter()
    result = await adapter.fetch(_EMPTY_KEYWORDS, _DEFAULT_OPTIONS)
    assert len(result.postings) >= 3
    for posting in result.postings:
        assert posting.company_name
        assert posting.title


async def test_fixture_adapter_respects_discovery_limit_per_source(monkeypatch):
    monkeypatch.setattr("app.adapters.fixture.date", _MockDate)
    adapter = FixtureAdapter()
    options = CollectionOptions(discovery_limit_per_source=2)
    result = await adapter.fetch(_FULL_KEYWORDS, options)
    assert len(result.postings) <= 2


async def test_fixture_adapter_deadlines_are_after_collect_date(monkeypatch):
    monkeypatch.setattr("app.adapters.fixture.date", _MockDate)
    adapter = FixtureAdapter()
    result = await adapter.fetch(_FULL_KEYWORDS, _DEFAULT_OPTIONS)
    for posting in result.postings:
        if posting.deadline is not None:
            assert posting.deadline > _COLLECT_DATE


# ── Config defaults ───────────────────────────────────────────────────────────


def test_config_fixture_disabled_by_default(monkeypatch):
    from app.core.config import Settings

    monkeypatch.delenv("JOB_COLLECTION_USE_FIXTURE", raising=False)
    s = Settings(_env_file=None)
    assert s.job_collection_use_fixture is False


def test_config_real_sources_disabled_by_default(monkeypatch):
    from app.core.config import Settings

    monkeypatch.delenv("JOB_COLLECTION_ENABLE_JASOSEOL", raising=False)
    s = Settings(_env_file=None)
    assert s.job_collection_enable_jasoseol is False


def test_config_timeout_default():
    from app.core.config import Settings

    s = Settings()
    assert s.job_collection_timeout_seconds == 10


def test_config_jasoseol_base_url_default():
    from app.core.config import Settings

    s = Settings()
    assert s.jasoseol_base_url == "https://jasoseol.com"


# ── Helpers ───────────────────────────────────────────────────────────────────


class _MockDate:
    """Replaces date.today() with a fixed date for deterministic tests."""

    @staticmethod
    def today() -> date:
        return _COLLECT_DATE

    @classmethod
    def fromisoformat(cls, s: str) -> date:
        return date.fromisoformat(s)

    def __init__(self, *args, **kwargs):
        self._d = date(*args, **kwargs)

    def __add__(self, other):
        return self._d + other

    def __gt__(self, other):
        return self._d > other

    @property
    def year(self):
        return self._d.year

    @property
    def month(self):
        return self._d.month

    @property
    def day(self):
        return self._d.day

    def toordinal(self):
        return self._d.toordinal()
