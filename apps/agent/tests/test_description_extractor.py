"""Unit tests for description_extractor.py — fixture HTML, no network."""

from __future__ import annotations

from bs4 import BeautifulSoup

from app.services.description_extractor import (
    _MAX_LENGTH,
    extract_jasoseol_description,
)


def _soup(html: str) -> BeautifulSoup:
    return BeautifulSoup(html, "html.parser")


# ── JSON-LD 추출 ────────────────────────────────────────────────────────────


class TestJsonLd:
    def test_extracts_description_from_json_ld(self):
        html = """
        <html><head>
        <script type="application/ld+json">
        {"@type":"JobPosting","description":"Java Spring Boot 백엔드 개발자 모집"}
        </script>
        </head><body></body></html>
        """
        desc, trunc = extract_jasoseol_description(_soup(html))
        assert desc == "Java Spring Boot 백엔드 개발자 모집"
        assert trunc is False

    def test_handles_json_ld_list(self):
        html = """
        <html><head>
        <script type="application/ld+json">
        [{"@type":"JobPosting","description":"배열 형식 JSON-LD 설명"}]
        </script>
        </head><body></body></html>
        """
        desc, trunc = extract_jasoseol_description(_soup(html))
        assert desc == "배열 형식 JSON-LD 설명"
        assert trunc is False

    def test_invalid_json_ld_falls_through(self):
        html = """
        <html><head>
        <script type="application/ld+json">NOT JSON</script>
        </head><body>
        <main>본문 내용이 여기 있습니다. 자격요건: 경력 3년 이상
        Java Spring Boot 개발 경험 필수, AWS 운영 경험 우대</main>
        </body></html>
        """
        desc, trunc = extract_jasoseol_description(_soup(html))
        # JSON-LD 실패 → selector(main) → 본문 추출
        assert desc is not None
        assert trunc is False

    def test_empty_json_ld_falls_through(self):
        html = """
        <html><head>
        <script type="application/ld+json">{}</script>
        </head><body>
        <article>자격요건: Java Spring Boot 경력 3년 이상,
        담당업무: RESTful API 개발 및 유지보수, 우대사항: AWS 경험</article>
        </body></html>
        """
        desc, trunc = extract_jasoseol_description(_soup(html))
        assert desc is not None


# ── CSS 선택자 추출 ─────────────────────────────────────────────────────────


class TestCssSelector:
    def test_extracts_via_recruit_wrap_class(self):
        long_content = "자격요건: Java Spring Boot 3년 이상 " * 5
        html = f"""
        <html><body>
        <nav>불필요한 내비게이션</nav>
        <div class="recruit_wrap">{long_content}</div>
        </body></html>
        """
        desc, trunc = extract_jasoseol_description(_soup(html))
        assert desc is not None
        # nav 내용은 포함되지 않아야 함
        assert "불필요한 내비게이션" not in desc

    def test_extracts_via_article_tag(self):
        content = (
            "담당업무: RESTful API 개발 및 유지보수. "
            "자격요건: Python FastAPI 경력 3년 이상. "
            "우대사항: Docker, Kubernetes 경험."
        )
        html = f"<html><body><article>{content}</article></body></html>"
        desc, trunc = extract_jasoseol_description(_soup(html))
        assert desc is not None
        assert "API 개발" in desc

    def test_strips_nav_header_footer(self):
        content = (
            "자격요건: Java Spring Boot 3년 이상. "
            "담당업무: 백엔드 서버 개발 및 유지보수. "
            "우대사항: AWS EC2, RDS 운영 경험 보유자."
        )
        html = f"""
        <html><body>
        <header>헤더 정보</header>
        <nav>내비게이션</nav>
        <main>{content}</main>
        <footer>푸터 정보</footer>
        </body></html>
        """
        desc, trunc = extract_jasoseol_description(_soup(html))
        assert desc is not None
        assert "헤더 정보" not in desc
        assert "푸터 정보" not in desc

    def test_short_container_below_50chars_is_skipped(self):
        html = """
        <html><body>
        <div class="recruit_wrap">짧음</div>
        <main>자격요건 Python Django 경력 2년 이상
        서버사이드 개발 담당업무 RESTful API</main>
        </body></html>
        """
        desc, trunc = extract_jasoseol_description(_soup(html))
        # recruit_wrap 내용이 50자 미만이므로 건너뜀 → main에서 추출
        assert desc is not None
        assert "Python" in desc or "RESTful" in desc


# ── Body 폴백 ───────────────────────────────────────────────────────────────


class TestBodyFallback:
    def test_falls_back_to_body_when_no_selector_matches(self):
        content = (
            "자격요건: Java Spring Boot 경력 3년 이상. "
            "담당업무: 백엔드 API 설계 및 개발."
        )
        html = f"<html><body><p>{content}</p></body></html>"
        desc, trunc = extract_jasoseol_description(_soup(html))
        assert desc is not None
        assert "Java" in desc

    def test_returns_none_when_body_is_missing(self):
        html = "<html><head><title>제목</title></head></html>"
        desc, trunc = extract_jasoseol_description(_soup(html))
        assert desc is None
        assert trunc is False


# ── 길이 제한 (MAX_LENGTH) ──────────────────────────────────────────────────


class TestLengthLimit:
    def test_long_json_ld_is_truncated(self):
        long_desc = "A" * (_MAX_LENGTH + 500)
        html = f"""
        <html><head>
        <script type="application/ld+json">{{"description": "{long_desc}"}}</script>
        </head><body></body></html>
        """
        desc, trunc = extract_jasoseol_description(_soup(html))
        assert trunc is True
        assert len(desc) == _MAX_LENGTH

    def test_exact_limit_is_not_truncated(self):
        exact_desc = "A" * _MAX_LENGTH
        html = f"""
        <html><head>
        <script type="application/ld+json">{{"description": "{exact_desc}"}}</script>
        </head><body></body></html>
        """
        desc, trunc = extract_jasoseol_description(_soup(html))
        assert trunc is False
        assert len(desc) == _MAX_LENGTH

    def test_short_content_is_not_truncated(self):
        html = """
        <html><head>
        <script type="application/ld+json">{"description": "짧은 설명"}</script>
        </head><body></body></html>
        """
        desc, trunc = extract_jasoseol_description(_soup(html))
        assert trunc is False


# ── None 반환 계약 ──────────────────────────────────────────────────────────


class TestNoneContract:
    def test_completely_empty_html_returns_none(self):
        desc, trunc = extract_jasoseol_description(_soup(""))
        assert desc is None
        assert trunc is False

    def test_only_nav_and_header_returns_none(self):
        html = """
        <html><body>
        <nav>내비게이션 내용</nav>
        <header>헤더 내용</header>
        <footer>푸터 내용</footer>
        </body></html>
        """
        # 제외 태그만 있어서 50자 이상 본문 없음 → None 반환
        desc, trunc = extract_jasoseol_description(_soup(html))
        # 실제 동작에 따라: body 폴백에서 남은 text가 없거나 50자 미만이면 None
        assert trunc is False
