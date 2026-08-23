"""Deterministic developer role planner for Jasoseol search discovery.

Converts raw seedKeywords.roles strings into normalized internal developer role
groups, then maps those groups to verified public Jasoseol dutyGroupIds.

No LLM, no network calls — pure deterministic string matching.

Verified dutyGroupIds extracted from __NEXT_DATA__ on jasoseol.com/search
on 2026-07-09 (public HTML, no authentication required):

  176 서버·백엔드개발    (group_id=162, category=small)
  175 프론트엔드개발      (group_id=162, category=small)
  172 iOS개발            (group_id=161, category=small)
  173 안드로이드개발      (group_id=161, category=small)
  164 시스템프로그래머    (group_id=94,  category=medium)
  165 응용프로그래머      (group_id=94,  category=medium)
  166 네트워크·보안·운영  (group_id=94,  category=medium)
  167 빅데이터·AI(인공지능) (group_id=94, category=medium)
  170 SW·솔루션·ERP      (group_id=94,  category=medium)
  179 데이터엔지니어      (group_id=163, category=small)
"""

from enum import Enum


class DeveloperRoleGroup(str, Enum):
    BACKEND = "BACKEND"
    FRONTEND = "FRONTEND"
    MOBILE = "MOBILE"
    DEVOPS_INFRA = "DEVOPS_INFRA"
    DATA_ENGINEERING = "DATA_ENGINEERING"
    AI_ML = "AI_ML"
    SECURITY = "SECURITY"
    GENERAL_SOFTWARE = "GENERAL_SOFTWARE"


# Verified mapping: internal group → public dutyGroupIds.
# IDs sourced from __NEXT_DATA__ on jasoseol.com/search, 2026-07-09.
# SECURITY(166) and DEVOPS_INFRA(166) share the "네트워크·보안·운영" category —
# duplicate IDs are removed when groups are combined.
DUTY_GROUP_IDS: dict[DeveloperRoleGroup, list[int]] = {
    DeveloperRoleGroup.BACKEND: [176],
    DeveloperRoleGroup.FRONTEND: [175],
    DeveloperRoleGroup.MOBILE: [172, 173],
    DeveloperRoleGroup.DEVOPS_INFRA: [164, 166],
    DeveloperRoleGroup.DATA_ENGINEERING: [179],
    DeveloperRoleGroup.AI_ML: [167],
    DeveloperRoleGroup.SECURITY: [166],
    DeveloperRoleGroup.GENERAL_SOFTWARE: [165, 170],
}

# Alias table: lowercased substring → DeveloperRoleGroup.
# Longer aliases must appear before shorter substrings to avoid false matches
# (e.g. "서버" would match "서버·백엔드" → use specific first).
_ALIASES: list[tuple[str, DeveloperRoleGroup]] = [
    # BACKEND
    ("백엔드", DeveloperRoleGroup.BACKEND),
    ("서버 개발", DeveloperRoleGroup.BACKEND),
    ("서버개발", DeveloperRoleGroup.BACKEND),
    ("server engineer", DeveloperRoleGroup.BACKEND),
    ("backend engineer", DeveloperRoleGroup.BACKEND),
    ("backend developer", DeveloperRoleGroup.BACKEND),
    ("back-end", DeveloperRoleGroup.BACKEND),
    ("java developer", DeveloperRoleGroup.BACKEND),
    ("api developer", DeveloperRoleGroup.BACKEND),
    ("spring", DeveloperRoleGroup.BACKEND),
    ("서버", DeveloperRoleGroup.BACKEND),
    # FRONTEND
    ("프론트엔드", DeveloperRoleGroup.FRONTEND),
    ("프론트 엔드", DeveloperRoleGroup.FRONTEND),
    ("frontend engineer", DeveloperRoleGroup.FRONTEND),
    ("frontend developer", DeveloperRoleGroup.FRONTEND),
    ("front-end", DeveloperRoleGroup.FRONTEND),
    ("react developer", DeveloperRoleGroup.FRONTEND),
    ("web frontend", DeveloperRoleGroup.FRONTEND),
    ("vue developer", DeveloperRoleGroup.FRONTEND),
    ("angular developer", DeveloperRoleGroup.FRONTEND),
    # MOBILE
    ("android developer", DeveloperRoleGroup.MOBILE),
    ("ios developer", DeveloperRoleGroup.MOBILE),
    ("모바일 개발", DeveloperRoleGroup.MOBILE),
    ("모바일개발", DeveloperRoleGroup.MOBILE),
    ("모바일", DeveloperRoleGroup.MOBILE),
    ("mobile developer", DeveloperRoleGroup.MOBILE),
    ("mobile engineer", DeveloperRoleGroup.MOBILE),
    ("android", DeveloperRoleGroup.MOBILE),
    ("ios", DeveloperRoleGroup.MOBILE),
    # DEVOPS_INFRA
    ("infrastructure engineer", DeveloperRoleGroup.DEVOPS_INFRA),
    ("cloud engineer", DeveloperRoleGroup.DEVOPS_INFRA),
    ("인프라 엔지니어", DeveloperRoleGroup.DEVOPS_INFRA),
    ("인프라엔지니어", DeveloperRoleGroup.DEVOPS_INFRA),
    ("devops engineer", DeveloperRoleGroup.DEVOPS_INFRA),
    ("site reliability", DeveloperRoleGroup.DEVOPS_INFRA),
    ("sre", DeveloperRoleGroup.DEVOPS_INFRA),
    ("devops", DeveloperRoleGroup.DEVOPS_INFRA),
    ("인프라", DeveloperRoleGroup.DEVOPS_INFRA),
    # DATA_ENGINEERING
    ("데이터 엔지니어", DeveloperRoleGroup.DATA_ENGINEERING),
    ("데이터엔지니어", DeveloperRoleGroup.DATA_ENGINEERING),
    ("data engineer", DeveloperRoleGroup.DATA_ENGINEERING),
    # AI_ML
    ("machine learning engineer", DeveloperRoleGroup.AI_ML),
    ("ml engineer", DeveloperRoleGroup.AI_ML),
    ("ai engineer", DeveloperRoleGroup.AI_ML),
    ("머신러닝 엔지니어", DeveloperRoleGroup.AI_ML),
    ("머신러닝엔지니어", DeveloperRoleGroup.AI_ML),
    ("llm engineer", DeveloperRoleGroup.AI_ML),
    ("machine learning", DeveloperRoleGroup.AI_ML),
    # SECURITY
    ("application security", DeveloperRoleGroup.SECURITY),
    ("security engineer", DeveloperRoleGroup.SECURITY),
    ("보안 엔지니어", DeveloperRoleGroup.SECURITY),
    ("보안엔지니어", DeveloperRoleGroup.SECURITY),
    # GENERAL_SOFTWARE
    ("software engineer", DeveloperRoleGroup.GENERAL_SOFTWARE),
    ("sw engineer", DeveloperRoleGroup.GENERAL_SOFTWARE),
    ("소프트웨어 개발자", DeveloperRoleGroup.GENERAL_SOFTWARE),
    ("소프트웨어개발자", DeveloperRoleGroup.GENERAL_SOFTWARE),
]


def plan_developer_roles(roles: list[str]) -> list[DeveloperRoleGroup]:
    """Map raw user role strings to deduplicated internal DeveloperRoleGroups.

    - Case-insensitive substring matching.
    - Unknown roles are silently skipped (they do not fail collection).
    - Empty input returns an empty list (Sitemap Exploration fallback applies).
    - The result order is deterministic: enum declaration order, deduplicated.
    """
    if not roles:
        return []

    found: set[DeveloperRoleGroup] = set()
    for raw_role in roles:
        normalised = " ".join(raw_role.strip().lower().split())
        for alias, group in _ALIASES:
            if alias in normalised:
                found.add(group)
                break  # one match per raw role is enough

    # Return in stable enum-declaration order
    return [g for g in DeveloperRoleGroup if g in found]


def get_combined_duty_ids(groups: list[DeveloperRoleGroup]) -> list[int]:
    """Return sorted, deduplicated public dutyGroupIds for the given groups."""
    ids: set[int] = set()
    for group in groups:
        ids.update(DUTY_GROUP_IDS.get(group, []))
    return sorted(ids)
