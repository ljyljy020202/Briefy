"""Official-source preflight service.

Runs a bounded probe against a company source URL to verify that the source
is reachable, the adapter type is supported, at least one item is discoverable,
and sample-parsed items have the required fields (title, source_url).

Constraints enforced here:
  - No DB writes.
  - No full DailyCollection run.
  - Small bounded request only (max 5 discovered URLs, 1 sample fetch).
  - No browser automation, no auth bypass.
  - Internal stack traces are never surfaced in failure_message.
"""

import logging
from datetime import date

from httpx import AsyncClient, HTTPStatusError, TimeoutException

from app.adapters.official_company import (
    _CUSTOM_REGISTRY_BY_KEY,
    _extract_parser_key,
    parse_page_generic,
    parse_rss_feed,
    parse_sitemap_xml,
)
from app.schemas.official_sources import OfficialSourcePreflightResponse

log = logging.getLogger(__name__)

_PREFLIGHT_TIMEOUT = 15
_MAX_DISCOVER = 5
_MAX_SAMPLE = 1
_HEADERS = {"User-Agent": "Briefy-Agent/1.0 (+https://briefy.io)"}


async def run_preflight(
    source_id: int,
    company_id: int,
    company_name: str | None,
    source_type: str,
    source_url: str | None,
    adapter_type: str,
    config_json: str | None,
) -> OfficialSourcePreflightResponse:
    """Dispatch to the adapter-specific preflight handler."""
    norm = adapter_type.strip().upper() if adapter_type else ""
    try:
        if norm == "SITEMAP":
            return await _sitemap_preflight(source_id, company_name, source_url)
        elif norm == "RSS":
            return await _rss_preflight(source_id, company_name, source_url)
        elif norm == "CUSTOM":
            return await _custom_preflight(
                source_id, company_id, company_name, source_url, config_json
            )
        else:
            return OfficialSourcePreflightResponse(
                failure_code="UNSUPPORTED_ADAPTER",
                failure_message=f"Unknown adapterType: {adapter_type!r}",
            )
    except Exception as exc:
        log.exception("Unexpected error in preflight sourceId=%d: %s", source_id, exc)
        return OfficialSourcePreflightResponse(
            failure_code="INTERNAL_ERROR",
            failure_message="An unexpected error occurred during preflight",
        )


# ── SITEMAP ───────────────────────────────────────────────────────────────────


async def _sitemap_preflight(
    source_id: int,
    company_name: str | None,
    source_url: str | None,
) -> OfficialSourcePreflightResponse:
    if not source_url:
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            failure_code="INVALID_CONFIG",
            failure_message="No source_url provided for SITEMAP",
        )

    label = company_name or f"source_{source_id}"
    warnings: list[str] = []

    try:
        async with AsyncClient(
            timeout=_PREFLIGHT_TIMEOUT, headers=_HEADERS, follow_redirects=True
        ) as client:
            # 1. Fetch sitemap
            try:
                resp = await client.get(source_url)
                resp.raise_for_status()
            except TimeoutException:
                return OfficialSourcePreflightResponse(
                    adapter_supported=True,
                    failure_code="TIMEOUT",
                    failure_message="Sitemap URL timed out",
                )
            except HTTPStatusError as exc:
                return OfficialSourcePreflightResponse(
                    adapter_supported=True,
                    failure_code="URL_UNREACHABLE",
                    failure_message=f"Sitemap HTTP {exc.response.status_code}",
                )

            # 2. Parse sitemap (accept all dates for preflight)
            try:
                entries = parse_sitemap_xml(resp.text, date.min)
            except ValueError as exc:
                return OfficialSourcePreflightResponse(
                    adapter_supported=True,
                    reachable=True,
                    failure_code="REQUIRED_FIELD_PARSE_FAILED",
                    failure_message=f"Could not parse sitemap XML: {exc}",
                )

            if not entries:
                return OfficialSourcePreflightResponse(
                    adapter_supported=True,
                    reachable=True,
                    failure_code="NO_ITEMS_DISCOVERED",
                    failure_message="No URLs found in sitemap",
                )

            discovered_count = min(len(entries), _MAX_DISCOVER)

            # 3. Fetch one sample page
            sample_url = entries[0][1]
            sample_parsed_count = 0
            try:
                page_resp = await client.get(sample_url)
                page_resp.raise_for_status()
                posting = parse_page_generic(
                    page_resp.text, sample_url, label, "preflight"
                )
                if posting is not None and posting.title:
                    sample_parsed_count = 1
                else:
                    return OfficialSourcePreflightResponse(
                        adapter_supported=True,
                        reachable=True,
                        discovered_count=discovered_count,
                        failure_code="REQUIRED_FIELD_PARSE_FAILED",
                        failure_message="Sample page did not yield a title",
                    )
            except TimeoutException:
                warnings.append("Sample page timed out; skipping sample check")
            except HTTPStatusError as exc:
                warnings.append(f"Sample page HTTP {exc.response.status_code}")
            except Exception as exc:
                warnings.append(f"Sample page error: {type(exc).__name__}")

        return OfficialSourcePreflightResponse(
            reachable=True,
            adapter_supported=True,
            discovered_count=discovered_count,
            sample_parsed_count=sample_parsed_count,
            required_fields_valid=sample_parsed_count > 0,
            warnings=warnings,
        )

    except Exception as exc:
        log.warning("SITEMAP preflight error sourceId=%d: %s", source_id, exc)
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            failure_code="INTERNAL_ERROR",
            failure_message="Unexpected error during sitemap preflight",
        )


# ── RSS ───────────────────────────────────────────────────────────────────────


async def _rss_preflight(
    source_id: int,
    company_name: str | None,
    source_url: str | None,
) -> OfficialSourcePreflightResponse:
    if not source_url:
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            failure_code="INVALID_CONFIG",
            failure_message="No source_url provided for RSS",
        )

    label = company_name or f"source_{source_id}"
    warnings: list[str] = []

    try:
        async with AsyncClient(
            timeout=_PREFLIGHT_TIMEOUT, headers=_HEADERS, follow_redirects=True
        ) as client:
            try:
                resp = await client.get(source_url)
                resp.raise_for_status()
            except TimeoutException:
                return OfficialSourcePreflightResponse(
                    adapter_supported=True,
                    failure_code="TIMEOUT",
                    failure_message="RSS feed URL timed out",
                )
            except HTTPStatusError as exc:
                return OfficialSourcePreflightResponse(
                    adapter_supported=True,
                    failure_code="URL_UNREACHABLE",
                    failure_message=f"RSS feed HTTP {exc.response.status_code}",
                )

            try:
                postings = parse_rss_feed(
                    resp.text, label, "preflight", max_items=_MAX_DISCOVER
                )
            except ValueError as exc:
                return OfficialSourcePreflightResponse(
                    adapter_supported=True,
                    reachable=True,
                    failure_code="REQUIRED_FIELD_PARSE_FAILED",
                    failure_message=f"Could not parse RSS/Atom XML: {exc}",
                )

            if not postings:
                return OfficialSourcePreflightResponse(
                    adapter_supported=True,
                    reachable=True,
                    failure_code="NO_ITEMS_DISCOVERED",
                    failure_message="No items found in feed",
                )

            valid = [p for p in postings if p.title and p.source_url]
            if not valid:
                return OfficialSourcePreflightResponse(
                    adapter_supported=True,
                    reachable=True,
                    discovered_count=len(postings),
                    failure_code="REQUIRED_FIELD_PARSE_FAILED",
                    failure_message=(
                        "Feed items are missing required fields (title/link)"
                    ),
                )

        return OfficialSourcePreflightResponse(
            reachable=True,
            adapter_supported=True,
            discovered_count=len(postings),
            sample_parsed_count=min(len(valid), _MAX_SAMPLE),
            required_fields_valid=True,
            warnings=warnings,
        )

    except Exception as exc:
        log.warning("RSS preflight error sourceId=%d: %s", source_id, exc)
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            failure_code="INTERNAL_ERROR",
            failure_message="Unexpected error during RSS preflight",
        )


# ── CUSTOM ────────────────────────────────────────────────────────────────────


async def _custom_preflight(
    source_id: int,
    company_id: int,
    company_name: str | None,
    source_url: str | None,
    config_json: str | None,
) -> OfficialSourcePreflightResponse:
    parser_key, key_error = _extract_parser_key(config_json)

    if key_error is not None:
        code = (
            "MISSING_PARSER_KEY"
            if key_error in ("missing_key", "empty_key")
            else "INVALID_CONFIG"
        )
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            failure_code=code,
            failure_message=f"config_json error: {key_error}",
        )

    if parser_key is None:
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            failure_code="MISSING_PARSER_KEY",
            failure_message="parser_key is required for CUSTOM adapter",
        )

    parser = _CUSTOM_REGISTRY_BY_KEY.get(parser_key)
    if parser is None:
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=False,
            failure_code="UNKNOWN_PARSER",
            failure_message=f"No parser registered for parser_key={parser_key!r}",
        )

    # Delegate to parser-specific preflight
    if parser_key == "GREETING":
        return await _greeting_preflight(
            source_id, company_id, company_name, source_url, config_json
        )

    if parser_key in {"GREENHOUSE", "DAANGN_CAREERS"}:
        return await _greenhouse_preflight(
            source_id, company_id, company_name, source_url, config_json
        )

    if parser_key == "TOSS_CAREERS":
        return await _toss_preflight(
            source_id, company_id, company_name, source_url, config_json
        )

    if parser_key == "NAVER_CAREERS":
        return await _naver_preflight(
            source_id, company_id, company_name, source_url, config_json
        )

    if parser_key == "ABLY_CAREERS":
        return await _ably_preflight(
            source_id, company_id, company_name, source_url, config_json
        )

    if parser_key == "KAKAO_CAREERS":
        return await _kakao_preflight(
            source_id, company_id, company_name, source_url, config_json
        )

    if parser_key == "SAMSUNG_CAREERS":
        return await _samsung_preflight(
            source_id, company_id, company_name, source_url, config_json
        )

    if parser_key == "LG_CNS_CAREERS":
        return await _lg_cns_preflight(
            source_id, company_id, company_name, source_url, config_json
        )

    # Other parsers: not supported yet
    return OfficialSourcePreflightResponse(
        adapter_supported=True,
        parser_registered=True,
        failure_code="UNSUPPORTED_ADAPTER",
        failure_message=f"Parser {parser_key!r} does not implement preflight",
    )


async def _greeting_preflight(
    source_id: int,
    company_id: int,
    company_name: str | None,
    source_url: str | None,
    config_json: str | None,
) -> OfficialSourcePreflightResponse:
    from app.adapters.greeting import greeting_preflight
    from app.schemas.collection import OfficialCompanySource

    source = OfficialCompanySource(
        company_id=company_id,
        source_type="OFFICIAL_CAREER",
        source_url=source_url,
        adapter_type="CUSTOM",
        config_json=config_json,
    )

    result = await greeting_preflight(source)
    reachable: bool = result["reachable"]
    discovered: int = result["discovered_count"]
    warnings: list[str] = result["warnings"]
    sample = result["sample_parsed"]

    if not reachable:
        msg = warnings[0] if warnings else "List page unreachable"
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            failure_code="URL_UNREACHABLE",
            failure_message=msg,
        )

    if discovered == 0:
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            reachable=True,
            failure_code="NO_ITEMS_DISCOVERED",
            failure_message="No job URLs discovered on Greeting page",
            warnings=warnings,
        )

    sample_parsed = 1 if (sample is not None and sample.get("title")) else 0
    required_valid = sample_parsed > 0

    if not required_valid:
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            reachable=True,
            discovered_count=discovered,
            failure_code="REQUIRED_FIELD_PARSE_FAILED",
            failure_message="Sample job page did not yield a title",
            warnings=warnings,
        )

    return OfficialSourcePreflightResponse(
        reachable=True,
        adapter_supported=True,
        parser_registered=True,
        discovered_count=discovered,
        sample_parsed_count=sample_parsed,
        required_fields_valid=True,
        warnings=warnings,
    )


async def _greenhouse_preflight(
    source_id: int,
    company_id: int,
    company_name: str | None,
    source_url: str | None,
    config_json: str | None,
) -> OfficialSourcePreflightResponse:
    from app.adapters.greenhouse import greenhouse_preflight
    from app.schemas.collection import OfficialCompanySource

    source = OfficialCompanySource(
        company_id=company_id,
        source_type="OFFICIAL_CAREER",
        source_url=source_url,
        adapter_type="CUSTOM",
        config_json=config_json,
    )

    result = await greenhouse_preflight(source)
    reachable: bool = result["reachable"]
    discovered: int = result["discovered_count"]
    warnings: list[str] = result["warnings"]
    sample = result["sample_parsed"]

    if not reachable:
        msg = warnings[0] if warnings else "Greenhouse API unreachable"
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            failure_code="URL_UNREACHABLE",
            failure_message=msg,
        )

    if discovered == 0:
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            reachable=True,
            failure_code="NO_ITEMS_DISCOVERED",
            failure_message="No jobs found on Greenhouse board",
            warnings=warnings,
        )

    sample_parsed = 1 if (sample is not None and sample.get("title")) else 0
    required_valid = sample_parsed > 0

    if not required_valid:
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            reachable=True,
            discovered_count=discovered,
            failure_code="REQUIRED_FIELD_PARSE_FAILED",
            failure_message="Sample job did not yield a title",
            warnings=warnings,
        )

    return OfficialSourcePreflightResponse(
        reachable=True,
        adapter_supported=True,
        parser_registered=True,
        discovered_count=discovered,
        sample_parsed_count=sample_parsed,
        required_fields_valid=True,
        warnings=warnings,
    )


async def _toss_preflight(
    source_id: int,
    company_id: int,
    company_name: str | None,
    source_url: str | None,
    config_json: str | None,
) -> OfficialSourcePreflightResponse:
    from app.adapters.toss_careers import toss_preflight
    from app.schemas.collection import OfficialCompanySource

    source = OfficialCompanySource(
        company_id=company_id,
        source_type="OFFICIAL_CAREER",
        source_url=source_url,
        adapter_type="CUSTOM",
        config_json=config_json,
    )

    result = await toss_preflight(source)
    reachable: bool = result["reachable"]
    discovered: int = result["discovered_count"]
    warnings: list[str] = result["warnings"]
    sample = result["sample_parsed"]

    if not reachable:
        msg = warnings[0] if warnings else "Toss sitemap unreachable"
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            failure_code="URL_UNREACHABLE",
            failure_message=msg,
        )

    if discovered == 0:
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            reachable=True,
            failure_code="NO_ITEMS_DISCOVERED",
            failure_message="No job URLs found in Toss sitemap",
            warnings=warnings,
        )

    sample_parsed = 1 if (sample is not None and sample.get("title")) else 0
    required_valid = sample_parsed > 0

    if not required_valid:
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            reachable=True,
            discovered_count=discovered,
            failure_code="REQUIRED_FIELD_PARSE_FAILED",
            failure_message="Sample job page did not yield a title",
            warnings=warnings,
        )

    return OfficialSourcePreflightResponse(
        reachable=True,
        adapter_supported=True,
        parser_registered=True,
        discovered_count=discovered,
        sample_parsed_count=sample_parsed,
        required_fields_valid=True,
        warnings=warnings,
    )


async def _naver_preflight(
    source_id: int,
    company_id: int,
    company_name: str | None,
    source_url: str | None,
    config_json: str | None,
) -> OfficialSourcePreflightResponse:
    from app.adapters.naver_careers import naver_preflight
    from app.schemas.collection import OfficialCompanySource

    source = OfficialCompanySource(
        company_id=company_id,
        source_type="OFFICIAL_CAREER",
        source_url=source_url,
        adapter_type="CUSTOM",
        config_json=config_json,
    )

    result = await naver_preflight(source)
    reachable: bool = result["reachable"]
    discovered: int = result["discovered_count"]
    warnings: list[str] = result["warnings"]
    sample = result["sample_parsed"]

    if not reachable:
        msg = warnings[0] if warnings else "Naver recruit API unreachable"
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            failure_code="URL_UNREACHABLE",
            failure_message=msg,
        )

    if discovered == 0:
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            reachable=True,
            failure_code="NO_ITEMS_DISCOVERED",
            failure_message="No jobs found on Naver recruit",
            warnings=warnings,
        )

    sample_parsed = 1 if (sample is not None and sample.get("title")) else 0
    required_valid = sample_parsed > 0

    if not required_valid:
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            reachable=True,
            discovered_count=discovered,
            failure_code="REQUIRED_FIELD_PARSE_FAILED",
            failure_message="Sample job item did not yield a title",
            warnings=warnings,
        )

    return OfficialSourcePreflightResponse(
        reachable=True,
        adapter_supported=True,
        parser_registered=True,
        discovered_count=discovered,
        sample_parsed_count=sample_parsed,
        required_fields_valid=True,
        warnings=warnings,
    )


async def _ably_preflight(
    source_id: int,
    company_id: int,
    company_name: str | None,
    source_url: str | None,
    config_json: str | None,
) -> OfficialSourcePreflightResponse:
    from app.adapters.ably_careers import ably_preflight
    from app.schemas.collection import OfficialCompanySource

    source = OfficialCompanySource(
        company_id=company_id,
        source_type="OFFICIAL_CAREER",
        source_url=source_url,
        adapter_type="CUSTOM",
        config_json=config_json,
    )

    result = await ably_preflight(source)
    reachable: bool = result["reachable"]
    discovered: int = result.get("discovered_count", 0)
    warnings: list[str] = result["warnings"]
    sample = result.get("sample_parsed")

    if not reachable:
        msg = warnings[0] if warnings else "Ably recruit page unreachable"
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            failure_code=result.get("failure_code", "URL_UNREACHABLE"),
            failure_message=msg,
        )

    if discovered == 0:
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            reachable=True,
            failure_code="NO_ITEMS_DISCOVERED",
            failure_message="No active jobs found on ably.team/recruit",
            warnings=warnings,
        )

    sample_parsed = 1 if (sample is not None and sample.get("title")) else 0
    required_valid = sample_parsed > 0

    if not required_valid:
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            reachable=True,
            discovered_count=discovered,
            failure_code="REQUIRED_FIELD_PARSE_FAILED",
            failure_message="Sample job item did not yield a title",
            warnings=warnings,
        )

    return OfficialSourcePreflightResponse(
        reachable=True,
        adapter_supported=True,
        parser_registered=True,
        discovered_count=discovered,
        sample_parsed_count=sample_parsed,
        required_fields_valid=True,
        warnings=warnings,
    )


async def _kakao_preflight(
    source_id: int,
    company_id: int,
    company_name: str | None,
    source_url: str | None,
    config_json: str | None,
) -> OfficialSourcePreflightResponse:
    from app.adapters.kakao_careers import kakao_preflight
    from app.schemas.collection import OfficialCompanySource

    source = OfficialCompanySource(
        company_id=company_id,
        source_type="OFFICIAL_CAREER",
        source_url=source_url,
        adapter_type="CUSTOM",
        config_json=config_json,
    )

    result = await kakao_preflight(source)
    reachable: bool = result["reachable"]
    discovered: int = result.get("discovered_count", 0)
    warnings: list[str] = result["warnings"]
    sample = result.get("sample_parsed")

    if not reachable:
        msg = warnings[0] if warnings else "Kakao job-list API unreachable"
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            failure_code=result.get("failure_code", "URL_UNREACHABLE"),
            failure_message=msg,
        )

    fc = result.get("failure_code")
    if fc == "CLOUDFLARE_SHELL":
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            reachable=True,
            failure_code="CLOUDFLARE_SHELL",
            failure_message=(
                result.get("failure_message") or "HTML shell returned"
            ),
            warnings=warnings,
        )

    if fc:
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            reachable=True,
            failure_code=fc,
            failure_message=result.get("failure_message") or "",
            warnings=warnings,
        )

    if discovered == 0:
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            reachable=True,
            failure_code="NO_ITEMS_DISCOVERED",
            failure_message=(
                "No active Kakao Corp jobs found (company=KAKAO)"
            ),
            warnings=warnings,
        )

    sample_parsed = 1 if (sample is not None and sample.get("title")) else 0
    required_valid = sample_parsed > 0

    if not required_valid:
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            reachable=True,
            discovered_count=discovered,
            failure_code="REQUIRED_FIELD_PARSE_FAILED",
            failure_message="Sample job item did not yield a title",
            warnings=warnings,
        )

    return OfficialSourcePreflightResponse(
        reachable=True,
        adapter_supported=True,
        parser_registered=True,
        discovered_count=discovered,
        sample_parsed_count=sample_parsed,
        required_fields_valid=True,
        warnings=warnings,
    )


async def _samsung_preflight(
    source_id: int,
    company_id: int,
    company_name: str | None,
    source_url: str | None,
    config_json: str | None,
) -> OfficialSourcePreflightResponse:
    from app.adapters.samsung_careers import samsung_preflight
    from app.schemas.collection import OfficialCompanySource

    source = OfficialCompanySource(
        company_id=company_id,
        source_type="OFFICIAL_CAREER",
        source_url=source_url,
        adapter_type="CUSTOM",
        config_json=config_json,
    )

    result = await samsung_preflight(source)
    reachable: bool = result["reachable"]
    discovered: int = result.get("discovered_count", 0)
    warnings: list[str] = result["warnings"]
    sample = result.get("sample_parsed")

    if not reachable:
        fc = result.get("failure_code", "URL_UNREACHABLE")
        msg = result.get("failure_message") or (warnings[0] if warnings else "")
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            failure_code=fc,
            failure_message=msg,
        )

    fc = result.get("failure_code")
    if fc:
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            reachable=True,
            failure_code=fc,
            failure_message=result.get("failure_message") or "",
            warnings=warnings,
        )

    if discovered == 0:
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            reachable=True,
            failure_code="NO_ITEMS_DISCOVERED",
            failure_message=(
                "No active postings found for configured com_codes — "
                "activate source once postings appear"
            ),
            warnings=warnings,
        )

    sample_parsed = 1 if (sample is not None and sample.get("title")) else 0
    required_valid = sample_parsed > 0

    if not required_valid:
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            reachable=True,
            discovered_count=discovered,
            failure_code="REQUIRED_FIELD_PARSE_FAILED",
            failure_message="Sample posting did not yield a title",
            warnings=warnings,
        )

    return OfficialSourcePreflightResponse(
        reachable=True,
        adapter_supported=True,
        parser_registered=True,
        discovered_count=discovered,
        sample_parsed_count=sample_parsed,
        required_fields_valid=True,
        warnings=warnings,
    )


async def _lg_cns_preflight(
    source_id: int,
    company_id: int,
    company_name: str | None,
    source_url: str | None,
    config_json: str | None,
) -> OfficialSourcePreflightResponse:
    from app.adapters.lg_cns_careers import lg_cns_preflight
    from app.schemas.collection import OfficialCompanySource

    source = OfficialCompanySource(
        company_id=company_id,
        source_type="OFFICIAL_CAREER",
        source_url=source_url,
        adapter_type="CUSTOM",
        config_json=config_json,
    )

    result = await lg_cns_preflight(source)
    reachable: bool = result["reachable"]
    discovered: int = result.get("discovered_count", 0)
    warnings: list[str] = result["warnings"]
    sample_parsed_bool: bool = result.get("sample_parsed", False)

    if not reachable:
        fc = result.get("failure_code", "URL_UNREACHABLE")
        msg = warnings[0] if warnings else "LG CNS careers API unreachable"
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            failure_code=fc,
            failure_message=msg,
        )

    fc = result.get("failure_code")
    if fc:
        msg = (
            "No active postings for companyCode=CNS"
            if fc == "NO_ITEMS_DISCOVERED"
            else result.get("failure_message") or ""
        )
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            reachable=True,
            failure_code=fc,
            failure_message=msg,
            warnings=warnings,
        )

    if discovered == 0:
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            reachable=True,
            failure_code="NO_ITEMS_DISCOVERED",
            failure_message="No active postings for companyCode=CNS",
            warnings=warnings,
        )

    sample_parsed = 1 if sample_parsed_bool else 0
    required_valid = sample_parsed > 0

    if not required_valid:
        return OfficialSourcePreflightResponse(
            adapter_supported=True,
            parser_registered=True,
            reachable=True,
            discovered_count=discovered,
            failure_code="REQUIRED_FIELD_PARSE_FAILED",
            failure_message="Sample LG CNS posting did not pass validation",
            warnings=warnings,
        )

    return OfficialSourcePreflightResponse(
        reachable=True,
        adapter_supported=True,
        parser_registered=True,
        discovered_count=discovered,
        sample_parsed_count=sample_parsed,
        required_fields_valid=True,
        warnings=warnings,
    )
