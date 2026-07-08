"""Tests for the deterministic Jasoseol role planner."""

from app.adapters.jasoseol_role_planner import (
    DUTY_GROUP_IDS,
    DeveloperRoleGroup,
    get_combined_duty_ids,
    plan_developer_roles,
)

# ── plan_developer_roles ──────────────────────────────────────────────────────


def test_korean_backend_role():
    assert DeveloperRoleGroup.BACKEND in plan_developer_roles(["백엔드 개발자"])


def test_english_backend_role():
    assert DeveloperRoleGroup.BACKEND in plan_developer_roles(["Backend Engineer"])


def test_server_engineer_maps_to_backend():
    assert DeveloperRoleGroup.BACKEND in plan_developer_roles(["Server Engineer"])


def test_java_developer_maps_to_backend():
    assert DeveloperRoleGroup.BACKEND in plan_developer_roles(["Java Developer"])


def test_frontend_role():
    assert DeveloperRoleGroup.FRONTEND in plan_developer_roles(["프론트엔드 개발자"])


def test_react_developer_maps_to_frontend():
    assert DeveloperRoleGroup.FRONTEND in plan_developer_roles(["React Developer"])


def test_mobile_role_android():
    assert DeveloperRoleGroup.MOBILE in plan_developer_roles(["Android Developer"])


def test_mobile_role_ios():
    assert DeveloperRoleGroup.MOBILE in plan_developer_roles(["iOS Developer"])


def test_korean_mobile_role():
    assert DeveloperRoleGroup.MOBILE in plan_developer_roles(["모바일 개발자"])


def test_devops_role():
    assert DeveloperRoleGroup.DEVOPS_INFRA in plan_developer_roles(["DevOps Engineer"])


def test_sre_role():
    assert DeveloperRoleGroup.DEVOPS_INFRA in plan_developer_roles(["SRE"])


def test_infra_engineer_role():
    assert DeveloperRoleGroup.DEVOPS_INFRA in plan_developer_roles(
        ["Infrastructure Engineer"]
    )


def test_cloud_engineer_role():
    assert DeveloperRoleGroup.DEVOPS_INFRA in plan_developer_roles(["Cloud Engineer"])


def test_data_engineering_role():
    assert DeveloperRoleGroup.DATA_ENGINEERING in plan_developer_roles(
        ["Data Engineer"]
    )


def test_korean_data_engineer():
    assert DeveloperRoleGroup.DATA_ENGINEERING in plan_developer_roles(
        ["데이터 엔지니어"]
    )


def test_ai_ml_engineer_role():
    assert DeveloperRoleGroup.AI_ML in plan_developer_roles(["ML Engineer"])


def test_machine_learning_engineer():
    assert DeveloperRoleGroup.AI_ML in plan_developer_roles(
        ["Machine Learning Engineer"]
    )


def test_korean_ml_engineer():
    assert DeveloperRoleGroup.AI_ML in plan_developer_roles(["머신러닝 엔지니어"])


def test_llm_engineer_maps_to_ai_ml():
    assert DeveloperRoleGroup.AI_ML in plan_developer_roles(["LLM Engineer"])


def test_security_engineer():
    assert DeveloperRoleGroup.SECURITY in plan_developer_roles(["Security Engineer"])


def test_korean_security_engineer():
    assert DeveloperRoleGroup.SECURITY in plan_developer_roles(["보안 엔지니어"])


def test_general_software_engineer():
    assert DeveloperRoleGroup.GENERAL_SOFTWARE in plan_developer_roles(
        ["Software Engineer"]
    )


def test_korean_software_developer():
    assert DeveloperRoleGroup.GENERAL_SOFTWARE in plan_developer_roles(
        ["소프트웨어 개발자"]
    )


def test_sw_engineer():
    assert DeveloperRoleGroup.GENERAL_SOFTWARE in plan_developer_roles(["SW Engineer"])


def test_duplicate_aliases_return_single_group():
    roles = ["백엔드 개발자", "Backend Engineer", "서버 개발자"]
    result = plan_developer_roles(roles)
    assert result.count(DeveloperRoleGroup.BACKEND) == 1


def test_mixed_case_matching():
    assert DeveloperRoleGroup.BACKEND in plan_developer_roles(["BACKEND ENGINEER"])
    assert DeveloperRoleGroup.FRONTEND in plan_developer_roles(["frontend Developer"])


def test_unknown_role_returns_empty():
    result = plan_developer_roles(["간호사"])
    assert result == []


def test_unknown_marketing_role():
    result = plan_developer_roles(["마케터", "영업직"])
    assert result == []


def test_empty_roles_returns_empty():
    assert plan_developer_roles([]) == []


def test_mixed_known_and_unknown():
    result = plan_developer_roles(["백엔드 개발자", "간호사"])
    assert DeveloperRoleGroup.BACKEND in result
    # "간호사" must not generate a spurious group
    assert DeveloperRoleGroup.SECURITY not in result


def test_result_deduplication():
    result = plan_developer_roles(["Backend Engineer", "백엔드 개발자", "서버 개발자"])
    assert len(result) == 1


def test_multiple_groups_from_multiple_roles():
    result = plan_developer_roles(["백엔드 개발자", "프론트엔드 개발자"])
    assert DeveloperRoleGroup.BACKEND in result
    assert DeveloperRoleGroup.FRONTEND in result


def test_result_order_is_stable():
    """Result must follow DeveloperRoleGroup enum declaration order."""
    result1 = plan_developer_roles(["프론트엔드 개발자", "백엔드 개발자"])
    result2 = plan_developer_roles(["백엔드 개발자", "프론트엔드 개발자"])
    assert result1 == result2


# ── get_combined_duty_ids ─────────────────────────────────────────────────────


def test_backend_maps_to_verified_id_176():
    ids = get_combined_duty_ids([DeveloperRoleGroup.BACKEND])
    assert 176 in ids


def test_frontend_maps_to_verified_id_175():
    ids = get_combined_duty_ids([DeveloperRoleGroup.FRONTEND])
    assert 175 in ids


def test_mobile_maps_to_ios_and_android():
    ids = get_combined_duty_ids([DeveloperRoleGroup.MOBILE])
    assert 172 in ids  # iOS개발
    assert 173 in ids  # 안드로이드개발


def test_devops_maps_to_verified_ids():
    ids = get_combined_duty_ids([DeveloperRoleGroup.DEVOPS_INFRA])
    assert 164 in ids  # 시스템프로그래머
    assert 166 in ids  # 네트워크·보안·운영


def test_data_engineering_maps_to_179():
    ids = get_combined_duty_ids([DeveloperRoleGroup.DATA_ENGINEERING])
    assert 179 in ids


def test_ai_ml_maps_to_167():
    ids = get_combined_duty_ids([DeveloperRoleGroup.AI_ML])
    assert 167 in ids


def test_security_maps_to_166():
    ids = get_combined_duty_ids([DeveloperRoleGroup.SECURITY])
    assert 166 in ids


def test_general_software_maps_to_165_and_170():
    ids = get_combined_duty_ids([DeveloperRoleGroup.GENERAL_SOFTWARE])
    assert 165 in ids  # 응용프로그래머
    assert 170 in ids  # SW·솔루션·ERP


def test_duplicate_ids_deduplicated():
    # SECURITY and DEVOPS_INFRA both include 166
    ids = get_combined_duty_ids(
        [DeveloperRoleGroup.SECURITY, DeveloperRoleGroup.DEVOPS_INFRA]
    )
    assert ids.count(166) == 1


def test_combined_ids_are_sorted():
    ids = get_combined_duty_ids(
        [DeveloperRoleGroup.BACKEND, DeveloperRoleGroup.FRONTEND]
    )
    assert ids == sorted(ids)


def test_empty_groups_returns_empty():
    assert get_combined_duty_ids([]) == []


def test_combined_backend_frontend_no_fabrication():
    """Only verified IDs 175, 176 for BACKEND+FRONTEND combination."""
    ids = get_combined_duty_ids(
        [DeveloperRoleGroup.BACKEND, DeveloperRoleGroup.FRONTEND]
    )
    assert ids == [175, 176]


def test_all_duty_ids_are_integers():
    for group in DeveloperRoleGroup:
        for id_ in DUTY_GROUP_IDS.get(group, []):
            assert isinstance(id_, int)
