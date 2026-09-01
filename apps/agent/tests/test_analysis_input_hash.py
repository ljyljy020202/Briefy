"""Unit tests for compute_analysis_input_hash in identifiers.py."""

from __future__ import annotations

from app.core.identifiers import compute_analysis_input_hash

# ── 안정성 및 결정론 ─────────────────────────────────────────────────────────


def test_same_inputs_produce_same_hash():
    h1 = compute_analysis_input_hash(
        "백엔드 개발자", "설명", ["Backend"], "3년 이상", "정규직", None
    )
    h2 = compute_analysis_input_hash(
        "백엔드 개발자", "설명", ["Backend"], "3년 이상", "정규직", None
    )
    assert h1 == h2
    assert len(h1) == 64
    assert h1.isalnum()  # hex chars


# ── roles 순서 불변 ──────────────────────────────────────────────────────────


def test_roles_order_does_not_affect_hash():
    h_asc = compute_analysis_input_hash(
        "백엔드 개발자", "설명", ["Backend", "Fullstack"], None, None, None
    )
    h_desc = compute_analysis_input_hash(
        "백엔드 개발자", "설명", ["Fullstack", "Backend"], None, None, None
    )
    assert h_asc == h_desc


def test_empty_roles_list_treated_same_as_no_roles():
    h_empty = compute_analysis_input_hash("백엔드 개발자", "설명", [], None, None, None)
    h_none_list = compute_analysis_input_hash(
        "백엔드 개발자", "설명", [], None, None, None
    )
    assert h_empty == h_none_list


# ── null/blank 정규화 ────────────────────────────────────────────────────────


def test_null_and_empty_description_produce_same_hash():
    h_null = compute_analysis_input_hash("백엔드 개발자", None, [], None, None, None)
    h_blank = compute_analysis_input_hash("백엔드 개발자", "   ", [], None, None, None)
    assert h_null == h_blank


def test_null_and_empty_experience_produce_same_hash():
    h_null = compute_analysis_input_hash("백엔드 개발자", "설명", [], None, None, None)
    h_empty = compute_analysis_input_hash("백엔드 개발자", "설명", [], "", None, None)
    assert h_null == h_empty


def test_whitespace_in_title_is_normalized():
    h_single = compute_analysis_input_hash(
        "백엔드 개발자", "설명", [], None, None, None
    )
    h_extra = compute_analysis_input_hash(
        "백엔드  개발자", "설명", [], None, None, None
    )
    assert h_single == h_extra


# ── descriptionTruncated 구분 ─────────────────────────────────────────────────


def test_truncated_none_vs_false_produce_different_hashes():
    h_none = compute_analysis_input_hash("백엔드 개발자", "설명", [], None, None, None)
    h_false = compute_analysis_input_hash(
        "백엔드 개발자", "설명", [], None, None, False
    )
    assert h_none != h_false


def test_truncated_false_vs_true_produce_different_hashes():
    h_false = compute_analysis_input_hash(
        "백엔드 개발자", "설명", [], None, None, False
    )
    h_true = compute_analysis_input_hash("백엔드 개발자", "설명", [], None, None, True)
    assert h_false != h_true


# ── 입력 변경 시 hash 변경 ────────────────────────────────────────────────────


def test_description_change_changes_hash():
    h1 = compute_analysis_input_hash("백엔드 개발자", "기존 설명", [], None, None, None)
    h2 = compute_analysis_input_hash(
        "백엔드 개발자", "변경된 설명", [], None, None, None
    )
    assert h1 != h2


def test_experience_change_changes_hash():
    h1 = compute_analysis_input_hash(
        "백엔드 개발자", "설명", [], "3년 이상", None, None
    )
    h2 = compute_analysis_input_hash(
        "백엔드 개발자", "설명", [], "5년 이상", None, None
    )
    assert h1 != h2


def test_roles_addition_changes_hash():
    h1 = compute_analysis_input_hash("백엔드 개발자", "설명", [], None, None, None)
    h2 = compute_analysis_input_hash(
        "백엔드 개발자", "설명", ["Backend"], None, None, None
    )
    assert h1 != h2


# ── contentHash[:500] 동일, full description 다름 → hash 다름 ─────────────────


def test_full_description_change_detected_beyond_500_chars():
    """contentHash[:500]이 같아도 전체 설명이 다르면 analysisInputHash는 달라야 한다."""
    base = "A" * 500
    desc_v1 = base + "요건: Java Spring"
    desc_v2 = base + "요건: Python Django"
    h1 = compute_analysis_input_hash("백엔드 개발자", desc_v1, [], None, None, False)
    h2 = compute_analysis_input_hash("백엔드 개발자", desc_v2, [], None, None, False)
    assert h1 != h2


# ── 교차 언어 fixture ─────────────────────────────────────────────────────────

# 아래 값은 Java AnalysisInputHashCalculatorTest::crossLanguage_fixtureHash_isStable
# 실행 후 콘솔 출력으로 얻은 값으로 업데이트한다.
# 테스트 첫 실행 시 Python이 생성한 값을 출력하고,
# Java 쪽 출력값과 비교하여 일치하면 고정한다.
_CROSS_LANG_FIXTURE_HASH: str | None = None  # Java 실행 후 채울 것


def test_cross_language_fixture_is_64char_hex():
    h = compute_analysis_input_hash(
        "백엔드 개발자",
        "Java Spring Boot를 사용한 백엔드 개발",
        ["backend engineering", "server-side"],
        "3년 이상",
        "정규직",
        False,
    )
    assert len(h) == 64
    assert all(c in "0123456789abcdef" for c in h)
    # Java쪽 fixture 값이 결정되면 아래 주석을 해제하고 고정한다.
    # assert h == _CROSS_LANG_FIXTURE_HASH
    print(f"\n[cross-lang fixture] Python hash: {h}")  # CI 로그로 Java와 비교
