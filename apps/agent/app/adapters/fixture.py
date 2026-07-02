"""FixtureAdapter — local development and test fixture source.

Returns deterministic job postings derived from seed keywords.
This adapter never calls any external service.
source = "fixture" makes it easy to identify in responses.
"""

from datetime import date, datetime, timedelta

from app.adapters.base import AdapterResult, JobBoardAdapter, RawJobPosting
from app.schemas.collection import CollectionOptions, SeedKeywords

_MIN_POSTINGS = 3
_MAX_POSTINGS = 5

_FIXTURE_COMPANIES = ["네이버", "카카오", "라인플러스", "쿠팡", "당근마켓"]
_FIXTURE_ROLES = [
    "백엔드 개발자", "프론트엔드 개발자", "풀스택 개발자", "DevOps 엔지니어",
]
_FIXTURE_SKILLS = [
    "Java", "Spring Boot", "Python", "FastAPI", "TypeScript", "React", "Docker",
]
_FIXTURE_EMP_TYPES = ["정규직", "인턴"]
_FIXTURE_EXP_LEVELS = ["신입", "경력 무관", "3년 이상"]
_FIXTURE_LOCATIONS = ["서울", "판교", "강남"]


def _stable_seed(collect_date: date) -> int:
    return collect_date.toordinal()


class FixtureAdapter(JobBoardAdapter):
    """Local fixture adapter for development and CI testing.

    Produces deterministic postings so test assertions are stable.
    Seed keyword values are reflected in the output when provided.
    """

    @property
    def source_name(self) -> str:
        return "fixture"

    async def fetch(
        self,
        seed_keywords: SeedKeywords,
        options: CollectionOptions,
    ) -> AdapterResult:
        collect_date = date.today()
        postings = _build_fixture_postings(seed_keywords, options, collect_date)
        return AdapterResult(postings=postings)


def _build_fixture_postings(
    seed_keywords: SeedKeywords,
    options: CollectionOptions,
    collect_date: date,
) -> list[RawJobPosting]:
    roles = seed_keywords.roles or _FIXTURE_ROLES
    companies = seed_keywords.companies or _FIXTURE_COMPANIES
    skills = seed_keywords.skills or _FIXTURE_SKILLS
    locations = seed_keywords.locations or _FIXTURE_LOCATIONS
    exp_levels = seed_keywords.experience_levels or _FIXTURE_EXP_LEVELS
    emp_types = seed_keywords.employment_types or _FIXTURE_EMP_TYPES

    total_kw = (
        len(seed_keywords.roles)
        + len(seed_keywords.companies)
        + len(seed_keywords.skills)
    )
    count = _MIN_POSTINGS + (total_kw % (_MAX_POSTINGS - _MIN_POSTINGS + 1))
    count = min(count, options.max_items_per_source)

    seed = _stable_seed(collect_date)
    postings: list[RawJobPosting] = []

    for i in range(count):
        company = companies[i % len(companies)]
        role = roles[i % len(roles)]
        skill_a = skills[i % len(skills)]
        skill_b = skills[(i + 1) % len(skills)]
        skill_list = [skill_a] if skill_a == skill_b else [skill_a, skill_b]
        location = locations[i % len(locations)]
        exp = exp_levels[i % len(exp_levels)]
        emp = emp_types[i % len(emp_types)]

        # stable URL ID so content_hash is deterministic per date
        url_id = (seed + i * 7919) % 99999
        source_url = f"https://fixture.local/jobs/{url_id:05d}"
        title = f"{company} — {role}"
        deadline = collect_date + timedelta(days=7 + i * 3)
        posted_at = datetime(
            collect_date.year, collect_date.month, collect_date.day, 9, 0, 0
        )
        description = (
            f"[픽스처 데이터] {company}에서 {exp} {emp} {role}을 채용합니다. "
            f"주요 기술: {', '.join(skill_list)}. 근무지: {location}."
        )

        postings.append(
            RawJobPosting(
                source="fixture",
                source_url=source_url,
                company_name=company,
                title=title,
                position=role,
                employment_type=emp,
                experience_level=exp,
                location=location,
                deadline=deadline,
                skills=skill_list,
                roles=[role],
                description=description,
                posted_at=posted_at,
            )
        )

    return postings
