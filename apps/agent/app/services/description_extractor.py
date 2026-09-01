"""HTML 공고 본문 추출기.

지원 소스: jasoseol
반환값: (description: str | None, truncated: bool)
  - description=None: 추출 불가 (허위 본문으로 채우지 않음)
  - truncated=True: 본문이 MAX_LENGTH를 초과하여 잘림
  - truncated=False: 전체 본문이 포함됨

설계 원칙:
  - 메뉴, 푸터, 타 공고 추천 영역을 본문으로 저장하지 않는다.
  - JSON-LD/공고 본문 컨테이너 등 확인 가능한 구조를 우선 사용한다.
  - 추출이 불가능하면 None을 반환하고 허위 본문으로 채우지 않는다.
  - 페이지를 중복 요청하지 않는다 (이미 파싱된 soup 사용).
"""

from __future__ import annotations

import json
import re
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from bs4 import BeautifulSoup

_MAX_LENGTH = 4000

# 제거 대상 태그: 네비게이션, 스크립트, 스타일 등
_STRIP_TAGS = frozenset({
    "nav", "header", "footer", "aside", "script", "style", "noscript",
    "iframe", "form",
})

# 공고 본문 컨테이너 후보 선택자 (우선순위 순)
_CONTENT_SELECTORS = [
    # jasoseol 공고 상세 페이지에서 관측되는 패턴
    "[class*='recruit_wrap']",
    "[class*='job_detail']",
    "[class*='detail_content']",
    "[class*='content_body']",
    "[class*='recruit_detail']",
    "[class*='jse_text']",
    "[class*='wr_con']",
    "article",
    "main",
    "[role='main']",
]

# 자격요건·업무 섹션 키워드 (이 단어를 포함하는 섹션 우선 보존)
_PRIORITY_KEYWORDS = frozenset({
    "모집분야", "담당업무", "주요업무",
    "자격요건", "필수요건", "우대요건",
    "지원자격", "우대사항",
})

# 중복 공백 정규화
_WHITESPACE_RE = re.compile(r"[ \t]+")
_NEWLINES_RE = re.compile(r"\n{3,}")


def _strip_excluded_elements(soup: "BeautifulSoup") -> None:
    """soup을 in-place 수정하여 불필요한 태그를 제거한다."""
    for tag in soup.find_all(_STRIP_TAGS):
        tag.decompose()


def _clean_text(text: str) -> str:
    text = _WHITESPACE_RE.sub(" ", text)
    text = _NEWLINES_RE.sub("\n\n", text)
    return text.strip()


def _apply_limit(text: str) -> tuple[str, bool]:
    if len(text) <= _MAX_LENGTH:
        return text, False
    return text[:_MAX_LENGTH], True


def _extract_from_json_ld(soup: "BeautifulSoup") -> str | None:
    """JSON-LD <script type='application/ld+json'> 에서 description 추출."""
    for tag in soup.find_all("script", type="application/ld+json"):
        try:
            data = json.loads(tag.string or "")
            if isinstance(data, list):
                data = data[0] if data else {}
            desc = data.get("description") or data.get("jobPosting", {}).get("description")
            if desc and isinstance(desc, str) and desc.strip():
                return desc.strip()
        except (json.JSONDecodeError, AttributeError, TypeError):
            continue
    return None


def _extract_from_selector(soup: "BeautifulSoup", selector: str) -> str | None:
    """CSS 선택자로 본문 컨테이너를 찾아 텍스트를 추출한다."""
    try:
        container = soup.select_one(selector)
    except Exception:
        return None
    if container is None:
        return None
    _strip_excluded_elements(container)
    text = container.get_text(separator="\n", strip=True)
    cleaned = _clean_text(text)
    return cleaned if cleaned else None


def extract_jasoseol_description(soup: "BeautifulSoup") -> tuple[str | None, bool]:
    """jasoseol 공고 페이지 BeautifulSoup 객체에서 본문을 추출한다.

    Returns:
        (description, truncated)
        description=None: 추출 불가 — 허위 본문으로 채우지 않는다.
        truncated=True: MAX_LENGTH 초과로 잘림.
    """
    # 1. JSON-LD 우선
    json_ld = _extract_from_json_ld(soup)
    if json_ld:
        cleaned = _clean_text(json_ld)
        if cleaned:
            return _apply_limit(cleaned)

    # 2. 공고 본문 컨테이너 선택자
    for selector in _CONTENT_SELECTORS:
        text = _extract_from_selector(soup, selector)
        if text and len(text) >= 50:
            return _apply_limit(text)

    # 3. body 전체에서 제외 태그 제거 후 추출 (최후 수단)
    body = soup.find("body")
    if body is None:
        return None, False

    body_copy = body  # soup.find 는 참조를 반환
    _strip_excluded_elements(body_copy)
    text = body_copy.get_text(separator="\n", strip=True)
    cleaned = _clean_text(text)
    if cleaned and len(cleaned) >= 50:
        return _apply_limit(cleaned)

    return None, False
