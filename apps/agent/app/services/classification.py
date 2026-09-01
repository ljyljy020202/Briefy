"""Job posting batch classification service.

Architecture:
- Splits postings into batches of `batch_size`.
- Processes batches concurrently up to `max_concurrency` via asyncio.Semaphore.
- Each batch: up to `max_attempts_per_item` LLM call attempts
  (initial + transient retry).
- Validation failures per item → rule-based fallback (no per-item LLM retry).
- Entire processing bounded by `request_budget_seconds`.
- Observability: LLM call count, token usage, per-category result counts, timing.

Retry policy:
- 429 / timeout → limited backoff, retry once.
- Invalid JSON → retry once.
- Configuration / fatal error → immediate fallback, no retry.
- SDK and HTTP retries are disabled: total LLM calls per item ≤ max_attempts_per_item.
"""

from __future__ import annotations

import asyncio
import logging
import re
import time
from dataclasses import dataclass, field

from app.core.config import settings
from app.core.llm_client import (
    LLMClientError,
    LLMTokenUsage,
    LLMUnavailableError,
)
from app.core.llm_client import (
    llm_client as _global_llm_client,
)
from app.prompts import classification as _prompts
from app.schemas.classification import (
    ClassificationResult,
    ClassifyPostingInput,
    ClassifyRequest,
    ClassifyResponse,
    ClassifyTokenUsage,
    ClassifyTrack,
)

logger = logging.getLogger(__name__)

# ── Enum allow-lists ──────────────────────────────────────────────────────────

_VALID_JOB_DOMAIN = frozenset({"IT", "NON_IT", "MIXED", "UNKNOWN"})
_VALID_POSTING_SCOPE = frozenset(
    {"ROLE_SPECIFIC", "MULTI_ROLE", "OPEN_RECRUITMENT", "UNKNOWN"}
)
_VALID_ROLE_GROUPS = frozenset(
    {
        "BACKEND",
        "FRONTEND",
        "FULLSTACK",
        "DATA",
        "AI_ML",
        "MOBILE",
        "DEVOPS_INFRA",
        "GENERAL_IT",
        "OTHER_IT",
        "NON_DEV",
    }
)
_DEV_ROLE_GROUPS = frozenset(
    {
        "BACKEND",
        "FRONTEND",
        "FULLSTACK",
        "DATA",
        "AI_ML",
        "MOBILE",
        "DEVOPS_INFRA",
        "GENERAL_IT",
        "OTHER_IT",
    }
)
_VALID_RECRUITMENT_TYPE = frozenset(
    {
        "EXPERIENCED_HIRE",
        "NEW_GRAD_HIRE",
        "OPEN_HIRE",
        "INTERNSHIP",
        "UNKNOWN",
    }
)
_VALID_EXP_REQ_TYPE = frozenset({"REQUIRED", "PREFERRED", "NONE", "UNKNOWN"})
_MULTI_TRACK_SCOPES = frozenset({"MULTI_ROLE", "OPEN_RECRUITMENT"})

# ── Rule-based fallback keyword sets ─────────────────────────────────────────

# Matched as substrings in lowercased title + roles concatenation.
_KW_BACKEND = {"backend", "백엔드", "server-side", "서버 개발"}
_KW_FRONTEND = {"frontend", "프론트엔드", "front-end"}
_KW_FULLSTACK = {"fullstack", "풀스택", "full-stack"}
_KW_DATA = {"data engineer", "데이터 엔지니어", "data pipeline", "데이터 파이프라인"}
_KW_AI_ML = {
    "machine learning",
    "머신러닝",
    "ml engineer",
    "ai engineer",
    "deep learning",
    "딥러닝",
    "nlp engineer",
}
_KW_DEVOPS = {
    "devops",
    "sre",
    "site reliability",
    "platform engineer",
    "cloud engineer",
    "인프라 엔지니어",
    "infra engineer",
}
_KW_MOBILE = {"android", "ios", "mobile", "앱 개발", "flutter", "react native"}
_KW_OTHER_IT = {
    "qa engineer",
    "qa 엔지니어",
    "quality engineer",
    "security engineer",
    "보안 엔지니어",
    "automation engineer",
    "테스트 엔지니어",
}
_KW_GENERAL_IT = {
    "software engineer",
    "소프트웨어 엔지니어",
    "developer",
    "개발자",
    "programmer",
    "프로그래머",
    "engineer",
    "엔지니어",
}
_KW_NON_IT = {
    "sales",
    "영업",
    "operations manager",
    "operations specialist",
    "운영",
    "총무",
    "경리",
    "회계",
    "재무",
    "marketing",
    "마케팅",
    "hr manager",
    "인사",
    "보험",
    "insurance",
    "법무",
    "legal",
    "manager",
    "매니저",  # only matched when no dev keywords present
}
# Dev keywords that take priority over NON_IT keywords
_KW_DEV_ANY = (
    _KW_BACKEND
    | _KW_FRONTEND
    | _KW_FULLSTACK
    | _KW_DATA
    | _KW_AI_ML
    | _KW_DEVOPS
    | _KW_MOBILE
    | _KW_OTHER_IT
    | _KW_GENERAL_IT
)

# Prompt injection detection patterns
_INJECTION_PATTERNS = [
    re.compile(r"ignore\s+(all\s+)?(previous|prior|above)\s+instructions", re.I),
    re.compile(r"system\s+prompt", re.I),
    re.compile(r"you\s+are\s+now\s+(a\s+)?", re.I),
    re.compile(r"disregard\s+(your\s+)?(previous|prior)\s+", re.I),
    re.compile(r"</?(system|instruction|prompt)>", re.I),
    re.compile(r"\[INST\]|\[/INST\]"),
]


# ── Internal helpers ──────────────────────────────────────────────────────────


@dataclass
class _BatchResult:
    results: list[ClassificationResult] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    llm_calls: int = 0
    usage: LLMTokenUsage = field(default_factory=LLMTokenUsage)


class _ItemValidationError(Exception):
    def __init__(self, codes: list[str]) -> None:
        self.codes = codes
        super().__init__(", ".join(codes))


def _is_transient_llm_error(msg: str) -> bool:
    """True for 429 / rate-limit / network errors that warrant a retry."""
    low = msg.lower()
    return (
        "429" in msg
        or "rate limit" in low
        or "rate_limit" in low
        or "too many requests" in low
        or "connection" in low
        or "timeout" in low
    )


def _is_config_error(msg: str) -> bool:
    """True for authentication / bad-request errors: no retry."""
    low = msg.lower()
    return (
        "401" in msg
        or "403" in msg
        or "invalid api key" in low
        or "model not found" in low
        or "400" in msg
    )


def _add_usage(a: LLMTokenUsage, b: LLMTokenUsage) -> LLMTokenUsage:
    return LLMTokenUsage(
        input_tokens=a.input_tokens + b.input_tokens,
        output_tokens=a.output_tokens + b.output_tokens,
        total_tokens=a.total_tokens + b.total_tokens,
    )


# ── Output validation ─────────────────────────────────────────────────────────


def _validate_evidence_quotes(
    evidence: str, posting: ClassifyPostingInput
) -> list[str]:
    """Check that short quoted excerpts in evidence exist in the posting input."""
    if not evidence:
        return []
    # Extract quoted substrings 5–80 chars
    quotes = re.findall(r'"([^"]{5,80})"', evidence)
    if not quotes:
        return []
    searchable = " ".join(
        filter(
            None,
            [
                posting.title,
                posting.company,
                posting.description or "",
                " ".join(posting.parsed_roles),
                posting.parsed_experience_level or "",
                posting.parsed_employment_type or "",
            ],
        )
    ).lower()
    errors = []
    for q in quotes:
        if q.lower() not in searchable:
            errors.append(f"EVIDENCE_QUOTE_NOT_FOUND:{q[:50]!r}")
    return errors


def _validate_track(
    raw_track: dict, posting_id: int
) -> tuple[ClassifyTrack | None, list[str]]:
    """Validate a single track object. Returns (track, errors)."""
    errors: list[str] = []
    jd = raw_track.get("jobDomain", "UNKNOWN")
    if jd not in _VALID_JOB_DOMAIN:
        errors.append(f"TRACK_INVALID_JOB_DOMAIN:{jd}")
        jd = "UNKNOWN"
    rgs = raw_track.get("roleGroups", [])
    valid_rgs = [r for r in rgs if r in _VALID_ROLE_GROUPS]
    if len(valid_rgs) != len(rgs):
        errors.append(f"TRACK_INVALID_ROLE_GROUPS:{set(rgs)-_VALID_ROLE_GROUPS}")
    ert = raw_track.get("experienceRequirementType", "UNKNOWN")
    if ert not in _VALID_EXP_REQ_TYPE:
        errors.append(f"TRACK_INVALID_EXP_REQ_TYPE:{ert}")
        ert = "UNKNOWN"
    rt = raw_track.get("recruitmentType", "UNKNOWN")
    if rt not in _VALID_RECRUITMENT_TYPE:
        errors.append(f"TRACK_INVALID_RECRUITMENT_TYPE:{rt}")
        rt = "UNKNOWN"
    # Contradiction: NON_IT + dev role groups
    if jd == "NON_IT" and any(r in _DEV_ROLE_GROUPS for r in valid_rgs):
        errors.append(f"TRACK_CONTRADICTION_NONIT_DEV_ROLES:{valid_rgs}")
    accepts = raw_track.get("acceptsNewGrad")
    if accepts is not None and not isinstance(accepts, bool):
        errors.append(f"TRACK_INVALID_ACCEPTS_NEW_GRAD_TYPE:{type(accepts).__name__}")
        accepts = None
    min_y = raw_track.get("minRequiredYears")
    max_y = raw_track.get("maxRequiredYears")
    if min_y is not None and (not isinstance(min_y, int) or min_y < 0):
        errors.append(f"TRACK_INVALID_MIN_YEARS:{min_y}")
        min_y = None
    if max_y is not None and (not isinstance(max_y, int) or max_y < 0):
        errors.append(f"TRACK_INVALID_MAX_YEARS:{max_y}")
        max_y = None
    if min_y is not None and max_y is not None and max_y < min_y:
        errors.append(f"TRACK_YEAR_RANGE_INVALID:{min_y}-{max_y}")
    track = ClassifyTrack(
        track_label=raw_track.get("trackLabel"),
        job_domain=jd,
        role_groups=valid_rgs,
        accepts_new_grad=accepts,
        min_required_years=min_y,
        max_required_years=max_y,
        experience_requirement_type=ert,
        recruitment_type=rt,
        employment_type=raw_track.get("employmentType"),
        evidence=raw_track.get("evidence"),
        unknown=bool(raw_track.get("unknown", False)),
    )
    return track, errors


def _validate_item(
    raw: dict,
    posting: ClassifyPostingInput,
) -> tuple[ClassificationResult, list[str]]:
    """Parse and validate one LLM result entry.

    Returns (result, warnings). Raises _ItemValidationError on fatal issues.
    """
    errors: list[str] = []
    warnings: list[str] = []

    # ID must match
    got_id = raw.get("jobPostingId")
    if got_id != posting.job_posting_id:
        raise _ItemValidationError(
            [f"ID_MISMATCH:got={got_id},expected={posting.job_posting_id}"]
        )

    # jobDomain
    job_domain = raw.get("jobDomain", "UNKNOWN")
    if job_domain not in _VALID_JOB_DOMAIN:
        errors.append(f"INVALID_JOB_DOMAIN:{job_domain}")
        job_domain = "UNKNOWN"

    # postingScope
    posting_scope = raw.get("postingScope", "UNKNOWN")
    if posting_scope not in _VALID_POSTING_SCOPE:
        errors.append(f"INVALID_POSTING_SCOPE:{posting_scope}")
        posting_scope = "UNKNOWN"

    # roleGroups
    raw_rgs = raw.get("roleGroups") or []
    role_groups = [r for r in raw_rgs if r in _VALID_ROLE_GROUPS]
    if len(role_groups) != len(raw_rgs):
        errors.append(f"INVALID_ROLE_GROUPS:{set(raw_rgs) - _VALID_ROLE_GROUPS}")

    # Contradiction: NON_IT + IT dev role groups
    if job_domain == "NON_IT" and any(r in _DEV_ROLE_GROUPS for r in role_groups):
        errors.append(f"CONTRADICTION_NONIT_WITH_DEV_ROLES:{role_groups}")

    # recruitmentType
    recruitment_type = raw.get("recruitmentType", "UNKNOWN")
    if recruitment_type not in _VALID_RECRUITMENT_TYPE:
        errors.append(f"INVALID_RECRUITMENT_TYPE:{recruitment_type}")
        recruitment_type = "UNKNOWN"

    # experienceRequirementType
    exp_req_type = raw.get("experienceRequirementType", "UNKNOWN")
    if exp_req_type not in _VALID_EXP_REQ_TYPE:
        errors.append(f"INVALID_EXP_REQ_TYPE:{exp_req_type}")
        exp_req_type = "UNKNOWN"

    # acceptsNewGrad — must remain nullable boolean
    accepts_new_grad = raw.get("acceptsNewGrad")
    if accepts_new_grad is not None and not isinstance(accepts_new_grad, bool):
        errors.append(
            f"INVALID_ACCEPTS_NEW_GRAD_TYPE:{type(accepts_new_grad).__name__}"
        )
        accepts_new_grad = None

    # minRequiredYears / maxRequiredYears
    min_y = raw.get("minRequiredYears")
    max_y = raw.get("maxRequiredYears")
    if min_y is not None and (not isinstance(min_y, int) or min_y < 0):
        errors.append(f"INVALID_MIN_YEARS:{min_y}")
        min_y = None
    if max_y is not None and (not isinstance(max_y, int) or max_y < 0):
        errors.append(f"INVALID_MAX_YEARS:{max_y}")
        max_y = None
    if min_y is not None and max_y is not None and max_y < min_y:
        errors.append(f"YEAR_RANGE_INVALID:{min_y}-{max_y}")

    # tracks
    raw_tracks = raw.get("tracks") or []
    tracks: list[ClassifyTrack] = []
    for rt in raw_tracks:
        track, track_errs = _validate_track(rt, posting.job_posting_id)
        tracks.append(track)
        errors.extend(track_errs)

    # tracks non-empty requirement for MULTI_ROLE / OPEN_RECRUITMENT
    if posting_scope in _MULTI_TRACK_SCOPES and not tracks:
        errors.append(f"TRACKS_EMPTY_FOR_SCOPE:{posting_scope}")

    # tracks vs. root contradiction: root NON_IT but a track is IT dev
    if job_domain == "NON_IT" and any(
        t.job_domain == "IT" and any(r in _DEV_ROLE_GROUPS for r in t.role_groups)
        for t in tracks
    ):
        errors.append("CONTRADICTION_ROOT_NONIT_TRACK_IT_DEV")

    # confidence
    raw_conf = raw.get("confidence")
    confidence: float | None = None
    if isinstance(raw_conf, (int, float)) and 0.0 <= float(raw_conf) <= 1.0:
        confidence = float(raw_conf)

    # evidence quotes check (non-fatal: demote to warning)
    evidence = raw.get("evidence") or ""
    ev_warnings = _validate_evidence_quotes(evidence, posting)
    if ev_warnings:
        warnings.extend([f"WARN:{w}" for w in ev_warnings])

    if errors:
        raise _ItemValidationError(errors)

    result = ClassificationResult(
        job_posting_id=posting.job_posting_id,
        analysis_input_hash=posting.analysis_input_hash,  # always echo from request
        job_domain=job_domain,
        posting_scope=posting_scope,
        role_groups=role_groups,
        recruitment_type=recruitment_type,
        tracks=tracks,
        accepts_new_grad=accepts_new_grad,
        min_required_years=min_y,
        max_required_years=max_y,
        experience_requirement_type=exp_req_type,
        preferred_experience=raw.get("preferredExperience"),
        method="LLM",
        status="SUCCEEDED",
        evidence=evidence or None,
        uncertainty_reasons=list(raw.get("uncertaintyReasons") or []),
        confidence=confidence,
        input_completeness=None,
        description_truncated=posting.input_quality.description_truncated
        if not posting.input_quality.description_truncated
        else True,
    )
    return result, warnings


# ── Rule-based fallback ───────────────────────────────────────────────────────


def _contains_any(text: str, keywords: set[str]) -> bool:
    return any(kw in text for kw in keywords)


def _apply_rule_fallback(
    posting: ClassifyPostingInput, error_code: str = ""
) -> ClassificationResult:
    """Generate a rule-based FALLBACK result without calling LLM."""
    text = f"{posting.title} {' '.join(posting.parsed_roles)}".lower()

    # Determine domain and role groups from title + parsed_roles
    # Priority: check for dev keywords first (so "AML Backend Developer" → IT)
    if _contains_any(text, _KW_BACKEND):
        job_domain, role_groups = "IT", ["BACKEND"]
    elif _contains_any(text, _KW_FRONTEND):
        job_domain, role_groups = "IT", ["FRONTEND"]
    elif _contains_any(text, _KW_FULLSTACK):
        job_domain, role_groups = "IT", ["FULLSTACK"]
    elif _contains_any(text, _KW_DATA):
        job_domain, role_groups = "IT", ["DATA"]
    elif _contains_any(text, _KW_AI_ML):
        job_domain, role_groups = "IT", ["AI_ML"]
    elif _contains_any(text, _KW_DEVOPS):
        job_domain, role_groups = "IT", ["DEVOPS_INFRA"]
    elif _contains_any(text, _KW_MOBILE):
        job_domain, role_groups = "IT", ["MOBILE"]
    elif _contains_any(text, _KW_OTHER_IT):
        job_domain, role_groups = "IT", ["OTHER_IT"]
    elif _contains_any(text, _KW_GENERAL_IT):
        job_domain, role_groups = "IT", ["GENERAL_IT"]
    elif _contains_any(text, _KW_NON_IT):
        job_domain, role_groups = "NON_IT", ["NON_DEV"]
    else:
        # Insufficient signal: do not guess
        job_domain, role_groups = "UNKNOWN", []

    # Parse experience from parsedExperienceLevel (simple numeric extraction)
    min_y: int | None = None
    exp_req_type = "UNKNOWN"
    if posting.parsed_experience_level:
        exp_text = posting.parsed_experience_level
        m = re.search(r"(\d+)\s*년", exp_text)
        if m:
            min_y = int(m.group(1))
            # Conservative: only use REQUIRED if no "우대" near the number
            exp_req_type = "REQUIRED" if "우대" not in exp_text else "PREFERRED"

    uncertainty_reasons: list[str] = []
    if job_domain == "UNKNOWN":
        uncertainty_reasons.append("INSUFFICIENT_ROLE_SIGNAL")
    if not posting.input_quality.has_description:
        uncertainty_reasons.append("NO_DESCRIPTION")
    elif posting.input_quality.description_truncated:
        uncertainty_reasons.append("DESCRIPTION_TRUNCATED")
    if error_code:
        uncertainty_reasons.append(error_code)

    return ClassificationResult(
        job_posting_id=posting.job_posting_id,
        analysis_input_hash=posting.analysis_input_hash,
        job_domain=job_domain,
        posting_scope="ROLE_SPECIFIC" if job_domain != "UNKNOWN" else "UNKNOWN",
        role_groups=role_groups,
        recruitment_type="UNKNOWN",
        tracks=[],
        accepts_new_grad=None,
        min_required_years=min_y,
        max_required_years=None,
        experience_requirement_type=exp_req_type if min_y is not None else "UNKNOWN",
        preferred_experience=None,
        method="RULE_BASED",
        status="FALLBACK",
        evidence=None,
        uncertainty_reasons=uncertainty_reasons,
        confidence=0.5 if job_domain != "UNKNOWN" else None,
        input_completeness=None,
        description_truncated=(
            posting.input_quality.description_truncated
            if posting.input_quality.description_truncated
            else None
        ),
    )


# ── ClassificationService ─────────────────────────────────────────────────────


class ClassificationService:
    """Batch job posting classifier.

    Inject ``llm_client`` in tests to avoid real API calls.
    All concurrency and timeout limits are configurable via constructor kwargs.
    """

    def __init__(
        self,
        llm_client=None,
        *,
        batch_size: int | None = None,
        max_concurrency: int | None = None,
        llm_timeout_seconds: int | None = None,
        max_attempts_per_item: int | None = None,
        request_budget_seconds: int | None = None,
        max_items_per_request: int | None = None,
        max_description_length: int | None = None,
    ) -> None:
        self._llm = llm_client if llm_client is not None else _global_llm_client
        self._batch_size = (
            batch_size if batch_size is not None else settings.classify_batch_size
        )
        self._max_concurrency = (
            max_concurrency
            if max_concurrency is not None
            else settings.classify_max_concurrency
        )
        self._llm_timeout = (
            llm_timeout_seconds
            if llm_timeout_seconds is not None
            else settings.classify_llm_timeout_seconds
        )
        self._max_attempts = (
            max_attempts_per_item
            if max_attempts_per_item is not None
            else settings.classify_max_attempts_per_item
        )
        self._budget = (
            request_budget_seconds
            if request_budget_seconds is not None
            else settings.classify_request_budget_seconds
        )
        self._max_items = (
            max_items_per_request
            if max_items_per_request is not None
            else settings.classify_max_items_per_request
        )
        self._max_desc_len = (
            max_description_length
            if max_description_length is not None
            else settings.classify_max_description_length
        )

    async def classify(self, request: ClassifyRequest) -> ClassifyResponse:
        t0 = time.monotonic()
        deadline = t0 + self._budget

        # ── Input validation ────────────────────────────────────────────────
        warnings: list[str] = []
        self._validate_request(request, warnings)

        postings = request.postings[: self._max_items]
        if len(request.postings) > self._max_items:
            warnings.append(
                f"INPUT_TRUNCATED:requested={len(request.postings)},max={self._max_items}"
            )

        # Truncate long descriptions
        truncated_postings = self._truncate_descriptions(postings)

        # Detect prompt injection attempts
        for p in truncated_postings:
            inj = _detect_prompt_injection(p)
            if inj:
                warnings.append(
                    f"PROMPT_INJECTION_DETECTED:id={p.job_posting_id},pattern={inj}"
                )

        # ── Batch processing ────────────────────────────────────────────────
        batches = _split_batches(truncated_postings, self._batch_size)
        sem = asyncio.Semaphore(self._max_concurrency)

        batch_tasks = [
            self._process_batch(batch, request.classifier_version, deadline, sem)
            for batch in batches
        ]
        batch_results: list[_BatchResult] = await asyncio.gather(*batch_tasks)

        # ── Aggregate ───────────────────────────────────────────────────────
        all_results: list[ClassificationResult] = []
        total_llm_calls = 0
        total_usage = LLMTokenUsage()
        for br in batch_results:
            all_results.extend(br.results)
            warnings.extend(br.warnings)
            total_llm_calls += br.llm_calls
            total_usage = _add_usage(total_usage, br.usage)

        # Ensure every requested ID is present (budget-exceeded items may be missing)
        result_ids = {r.job_posting_id for r in all_results}
        for p in truncated_postings:
            if p.job_posting_id not in result_ids:
                all_results.append(_apply_rule_fallback(p, "BUDGET_EXCEEDED"))
                warnings.append(f"BUDGET_EXCEEDED:id={p.job_posting_id}")

        elapsed = time.monotonic() - t0
        logger.info(
            "classification: requestId=%s model=%s classifierVersion=%s "
            "total=%d succeeded=%d fallback=%d failed=%d llm_calls=%d "
            "input_tokens=%d output_tokens=%d elapsed_s=%.2f",
            request.request_id,
            settings.llm_model,
            request.classifier_version,
            len(all_results),
            sum(1 for r in all_results if r.status == "SUCCEEDED"),
            sum(1 for r in all_results if r.status == "FALLBACK"),
            sum(1 for r in all_results if r.status == "FAILED"),
            total_llm_calls,
            total_usage.input_tokens,
            total_usage.output_tokens,
            elapsed,
        )

        return ClassifyResponse(
            request_id=request.request_id,
            classifier_version=request.classifier_version,
            results=all_results,
            token_usage=ClassifyTokenUsage(
                prompt_tokens=total_usage.input_tokens,
                completion_tokens=total_usage.output_tokens,
                total_tokens=total_usage.total_tokens,
            ),
            warnings=warnings,
        )

    def _validate_request(self, request: ClassifyRequest, warnings: list[str]) -> None:
        ids = [p.job_posting_id for p in request.postings]
        seen: set[int] = set()
        for pid in ids:
            if pid in seen:
                raise ValueError(f"DUPLICATE_POSTING_ID:{pid}")
            seen.add(pid)

    def _truncate_descriptions(
        self, postings: list[ClassifyPostingInput]
    ) -> list[ClassifyPostingInput]:
        result = []
        for p in postings:
            if p.description and len(p.description) > self._max_desc_len:
                # Pydantic models are immutable — rebuild from dict with truncated desc
                data = p.model_dump()
                data["description"] = p.description[: self._max_desc_len]
                result.append(ClassifyPostingInput.model_validate(data))
            else:
                result.append(p)
        return result

    async def _process_batch(
        self,
        batch: list[ClassifyPostingInput],
        classifier_version: str,
        deadline: float,
        sem: asyncio.Semaphore,
    ) -> _BatchResult:
        async with sem:
            return await self._process_batch_inner(batch, classifier_version, deadline)

    async def _process_batch_inner(
        self,
        batch: list[ClassifyPostingInput],
        classifier_version: str,
        deadline: float,
    ) -> _BatchResult:
        br = _BatchResult()

        if time.monotonic() > deadline:
            br.results = [_apply_rule_fallback(p, "BUDGET_EXCEEDED") for p in batch]
            br.warnings = [f"BUDGET_EXCEEDED:id={p.job_posting_id}" for p in batch]
            return br

        if not self._llm.enabled:
            br.results = [_apply_rule_fallback(p, "LLM_UNAVAILABLE") for p in batch]
            return br

        # Attempt LLM call (up to max_attempts for transient errors)
        raw: dict | None = None
        usage = LLMTokenUsage()
        last_error_code = ""
        backoff = 1.0

        for attempt in range(self._max_attempts):
            if time.monotonic() > deadline:
                break
            try:
                remaining = max(0.1, deadline - time.monotonic())
                actual_timeout = min(float(self._llm_timeout), remaining)
                raw_parsed, usage = await asyncio.wait_for(
                    self._llm.call_json(
                        _prompts.get_system_prompt(),
                        _prompts.build_user_prompt(batch),
                    ),
                    timeout=actual_timeout,
                )
                raw = raw_parsed
                br.llm_calls += 1
                break
            except asyncio.TimeoutError:
                br.llm_calls += 1
                last_error_code = "TIMEOUT"
                logger.warning(
                    "classification: batch LLM call timed out (attempt %d/%d) ids=%s",
                    attempt + 1,
                    self._max_attempts,
                    [p.job_posting_id for p in batch],
                )
            except LLMUnavailableError:
                br.llm_calls += 1
                last_error_code = "LLM_UNAVAILABLE"
                break  # No retry
            except LLMClientError as exc:
                br.llm_calls += 1
                msg = str(exc)
                if _is_config_error(msg):
                    last_error_code = "CONFIG_ERROR"
                    logger.error(
                        "classification: config error (no retry): %s", msg[:200]
                    )
                    break
                if _is_transient_llm_error(msg):
                    last_error_code = (
                        "RATE_LIMIT" if "429" in msg else "TRANSIENT_ERROR"
                    )
                    logger.warning(
                        "classification: transient LLM error (attempt %d/%d): %s",
                        attempt + 1,
                        self._max_attempts,
                        msg[:200],
                    )
                    await asyncio.sleep(min(backoff, 10.0))
                    backoff *= 2
                else:
                    # Invalid JSON or other non-transient error → retry once
                    last_error_code = "LLM_ERROR"
                    logger.warning(
                        "classification: LLM error (attempt %d/%d): %s",
                        attempt + 1,
                        self._max_attempts,
                        msg[:200],
                    )

        br.usage = usage

        if raw is None:
            # All attempts failed → rule fallback for entire batch
            for p in batch:
                br.results.append(_apply_rule_fallback(p, last_error_code))
                br.warnings.append(f"FALLBACK_{last_error_code}:id={p.job_posting_id}")
            return br

        # ── Validate per-item ───────────────────────────────────────────────
        raw_results: list[dict] = []
        if isinstance(raw, dict):
            raw_results = raw.get("results") or []
        if not isinstance(raw_results, list):
            raw_results = []

        # Build lookup: id → raw result
        raw_by_id: dict[int, dict] = {}
        for r in raw_results:
            if isinstance(r, dict) and isinstance(r.get("jobPostingId"), int):
                pid = r["jobPostingId"]
                if pid in raw_by_id:
                    br.warnings.append(f"DUPLICATE_ID_IN_LLM_RESPONSE:{pid}")
                else:
                    raw_by_id[pid] = r

        for p in batch:
            raw_r = raw_by_id.get(p.job_posting_id)
            if raw_r is None:
                br.results.append(_apply_rule_fallback(p, "MISSING_IN_RESPONSE"))
                br.warnings.append(f"MISSING_IN_LLM_RESPONSE:id={p.job_posting_id}")
                continue
            try:
                result, item_warnings = _validate_item(raw_r, p)
                br.results.append(result)
                br.warnings.extend(item_warnings)
            except _ItemValidationError as exc:
                br.results.append(_apply_rule_fallback(p, "VALIDATION_ERROR"))
                br.warnings.append(
                    f"VALIDATION_ERROR:id={p.job_posting_id},codes={exc.codes}"
                )

        # Warn about IDs in LLM response that were not in the request
        request_ids = {p.job_posting_id for p in batch}
        for extra_id in set(raw_by_id) - request_ids:
            br.warnings.append(f"UNEXPECTED_ID_IN_LLM_RESPONSE:{extra_id}")

        return br


# ── Helpers ───────────────────────────────────────────────────────────────────


def _split_batches(
    postings: list[ClassifyPostingInput], batch_size: int
) -> list[list[ClassifyPostingInput]]:
    return [postings[i : i + batch_size] for i in range(0, len(postings), batch_size)]


def _detect_prompt_injection(posting: ClassifyPostingInput) -> str:
    """Return the first matched injection pattern label, or empty string."""
    text = " ".join(
        filter(
            None,
            [posting.title, posting.description or "", " ".join(posting.parsed_roles)],
        )
    )
    for pat in _INJECTION_PATTERNS:
        m = pat.search(text)
        if m:
            return m.group(0)[:60]
    return ""
