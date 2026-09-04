// TODO: Remove this file when all backend endpoints are fully implemented.
// Used by landing page for sample display and as dev fallback in authenticated pages.

export type MockReportStatus = 'delivered' | 'scheduled' | 'draft'

export type MockReportSection = {
  heading: string
  summary: string
  bullets: string[]
  source?: string
}

export type MockReportArticle = {
  title: string
  source: string
  url: string
  summary: string
  whyItMatters: string
}

export type MockReport = {
  id: string
  title: string
  date: string
  readTime: string
  status: MockReportStatus
  topics: string[]
  preview: string
  highlights: string[]
  sections: MockReportSection[]
  articles?: MockReportArticle[]
  actions?: string[]
}

// Suggested values for each JOB_POSTING preference field in onboarding.
// Keys match JobPostingPreference field names.
export const JOB_KEYWORD_SUGGESTIONS: Record<string, string[]> = {
  roles: [
    '백엔드 개발자',
    '프론트엔드 개발자',
    '풀스택 개발자',
    '데이터 엔지니어',
    'AI 엔지니어',
    'DevOps 엔지니어',
    '인프라 엔지니어',
    'iOS 개발자',
    'Android 개발자',
  ],
  companies: [
    '네이버',
    '카카오',
    '라인',
    '쿠팡',
    '토스',
    '당근마켓',
    '배달의민족',
    '크래프톤',
  ],
  companySizes: ['스타트업', '중소기업', '중견기업', '대기업', '공기업/공공기관', '외국계'],
  industries: [
    'IT/소프트웨어',
    '게임',
    '핀테크',
    '은행/증권사',
    '이커머스',
    '의료/헬스케어',
    '교육',
  ],
  skills: [
    'Spring Boot',
    'Python',
    'Kotlin',
    'Java',
    'React',
    'TypeScript',
    'AWS',
    'Docker',
    'Kubernetes',
  ],
  locations: ['서울', '판교', '부산', '재택 가능'],
  experienceLevels: ['신입', '1~3년', '3~5년', '5년 이상', '경력 무관'],
  employmentTypes: ['정규직', '계약직', '채용연계형 인턴', '인턴'],
}

export const DELIVERY_TIMES = [
  { id: 'early', label: '오전 6시', hint: '출근 전 미리' },
  { id: 'morning', label: '오전 8시', hint: '가장 인기' },
  { id: 'work', label: '오전 9시', hint: '업무 시작' },
  { id: 'lunch', label: '오후 12시', hint: '점심 시간' },
]

export const MOCK_REPORTS: MockReport[] = [
  {
    id: 'r-2026-08-04',
    title: '8월 4일 채용 브리핑 — 백엔드 개발자',
    date: '2026년 8월 4일 (월)',
    readTime: '추천 공고 3건',
    status: 'delivered',
    topics: ['목표 직무', '관심 기업', '기술/역량'],
    preview:
      '현대오토에버·당근마켓·LG CNS에서 신규 공고 3건이 확인됐습니다. Spring Boot·Java 스킬과 매칭되는 포지션이 포함돼 있습니다.',
    highlights: [
      '현대오토에버 — 채용연계형 인턴, 마감 D-2',
      '당근마켓 — 백엔드 개발자 신입 공채 (Kotlin·Spring 매칭)',
      '관심 기업 3곳 신규 공고 3건 · 마감 임박 1건',
    ],
    articles: [
      {
        title: '[현대오토에버] 소프트웨어 개발 채용연계형 인턴 (마감 D-2)',
        source: '현대오토에버 채용',
        url: '#',
        summary:
          '현대오토에버 커넥티드카·모빌리티 플랫폼 개발 인턴. Java, Spring 기반 백엔드 개발 업무 수행. 판교 근무. 지원 마감 8월 6일.',
        whyItMatters: '관심 기업 현대오토에버, Java·Spring 매칭. 마감 임박.',
      },
      {
        title: '[당근마켓] 백엔드 개발자 신입 공채',
        source: '당근마켓 채용',
        url: '#',
        summary:
          '당근마켓 로컬 커머스 플랫폼 신입 백엔드 개발자 공채. Kotlin, Spring Boot 기반 서버 개발. 신입 지원 가능. 서울 근무.',
        whyItMatters: '관심 기업 당근마켓, Kotlin·Spring Boot 스킬 매칭. 신입 지원 가능.',
      },
      {
        title: '[LG CNS] IT 서비스 개발 신입 공채',
        source: '잡코리아',
        url: '#',
        summary:
          'LG CNS IT 서비스 개발 부문 신입 공채. Spring Boot 기반 사내 플랫폼 및 고객사 시스템 개발. 신입 지원 가능. 서울 근무.',
        whyItMatters: '선호 스킬 Spring Boot·Java 매칭. 신입 공채.',
      },
    ],
    actions: [
      '현대오토에버 공고는 D-2 마감 — 서류를 준비해 두었다면 오늘 중으로 제출하세요.',
      '당근마켓 신입 공채는 Kotlin 경험을 자소서에 구체적으로 어필하면 유리합니다.',
      'LG CNS 신입 공채는 마감 여유가 있으니 GitHub 프로젝트 정리와 포트폴리오를 보강한 뒤 지원하세요.',
    ],
    sections: [
      {
        heading: '📌 신규 공고',
        summary:
          '오늘 관심 기업에서 새로 올라온 채용 공고입니다. 설정하신 직무·스킬 기준으로 매칭 이유를 함께 정리했습니다.',
        bullets: [
          '현대오토에버 — 소프트웨어 개발 채용연계형 인턴 | Java, Spring | 판교 | 현대오토에버 채용',
          '당근마켓 — 백엔드 개발자 신입 공채 | Kotlin, Spring Boot | 서울 | 당근마켓 채용',
          'LG CNS — IT 서비스 개발 신입 공채 | Spring Boot, Java | 서울 | 잡코리아',
        ],
        source: '현대오토에버 채용 · 당근마켓 채용 · 잡코리아',
      },
      {
        heading: '⏰ 마감 임박 공고 (3일 이내)',
        summary: '지원 마감이 3일 이내로 다가온 공고입니다.',
        bullets: ['현대오토에버 — 소프트웨어 개발 채용연계형 인턴 | D-2 | Java, Spring | 현대오토에버 채용'],
        source: '현대오토에버 채용',
      },
      {
        heading: '💡 오늘의 추천 액션',
        summary: 'Briefy가 오늘의 브리핑을 바탕으로 제안하는 행동 목록입니다.',
        bullets: [
          '현대오토에버 공고는 D-2 마감 — 서류를 준비해 두었다면 오늘 중으로 제출하세요.',
          '당근마켓 신입 공채는 Kotlin 경험을 자소서에 구체적으로 어필하면 유리합니다.',
          'LG CNS 신입 공채는 마감 여유가 있으니 GitHub 정리와 포트폴리오를 보강한 뒤 지원하세요.',
        ],
      },
    ],
  },
  {
    id: 'r-2026-06-25',
    title: '6월 25일 채용 브리핑 — 백엔드 개발자',
    date: '2026년 6월 25일 (목)',
    readTime: '3분 분량',
    status: 'delivered',
    topics: ['목표 직무', '관심 기업', '선호 지역'],
    preview:
      '당근마켓·배달의민족에서 신규 공고 2건이 올라왔습니다. 판교 근무와 재택 가능 조건이 포함된 포지션이 있습니다.',
    highlights: [
      '당근마켓 — 백엔드 개발자 신규 공고 (판교 근무)',
      '배달의민족 — 풀스택 개발자, AWS·Docker 스킬 매칭',
      '네이버 서버 엔지니어 마감 D-1',
    ],
    sections: [
      {
        heading: '📌 신규 공고',
        summary:
          '어제 기준으로 새로 등록된 공고입니다. 관심 기업 키워드와 선호 지역 기준으로 필터링했습니다.',
        bullets: [
          '당근마켓 — 백엔드 개발자 | Java, Spring | 판교 | 원티드',
          '배달의민족 — 풀스택 개발자 | React, AWS, Docker | 서울 | 잡코리아',
        ],
        source: '원티드 · 잡코리아',
      },
      {
        heading: '⏰ 마감 임박 공고 (3일 이내)',
        summary: '관심 목록에서 마감이 임박한 공고입니다.',
        bullets: ['네이버 — 서버 엔지니어 | D-1 | Kotlin | 채용 홈페이지'],
        source: '채용 홈페이지',
      },
      {
        heading: '💡 오늘의 추천 액션',
        summary: 'Briefy의 오늘 제안입니다.',
        bullets: [
          '당근마켓 공고는 판교 근무 — 출퇴근 조건이 맞는다면 빠르게 지원하세요.',
          '네이버 서버 엔지니어 공고 D-1 — 오늘 지원이 최선입니다.',
          'Docker·AWS 경험이 있다면 배달의민족 공고 매칭 점수가 높습니다.',
        ],
      },
    ],
  },
  {
    id: 'r-2026-06-24',
    title: '6월 24일 채용 브리핑 — 백엔드 개발자',
    date: '2026년 6월 24일 (수)',
    readTime: '4분 분량',
    status: 'delivered',
    topics: ['목표 직무', '고용 형태', '선호 지역'],
    preview:
      '쿠팡·토스·카카오에서 정규직 백엔드 개발자 공고 3건이 확인됐습니다. 서울 및 재택 가능 포지션 위주로 정리했습니다.',
    highlights: [
      '토스 — 서버 개발자 신규 공고 (재택 가능, Kotlin 매칭)',
      '카카오 — 정규직 백엔드, 경력 3년 이상 우대',
      '신규 공고 3건 모두 정규직 · 서울/재택 근무',
    ],
    sections: [
      {
        heading: '📌 신규 공고',
        summary: '서울·재택 조건과 정규직 필터를 적용한 결과입니다.',
        bullets: [
          '토스 — 서버 개발자 | Kotlin, AWS | 재택 가능 | 원티드',
          '카카오 — 백엔드 개발자 | Java, Kafka | 서울 | LinkedIn',
          '쿠팡 — 백엔드 엔지니어 | Spring Boot | 서울 | 채용 홈페이지',
        ],
        source: '원티드 · LinkedIn · 채용 홈페이지',
      },
      {
        heading: '⏰ 마감 임박 공고 (3일 이내)',
        summary: '이번 주 내로 마감되는 공고가 없습니다.',
        bullets: ['오늘 기준 3일 이내 마감 임박 공고 없음'],
      },
      {
        heading: '💡 오늘의 추천 액션',
        summary: '오늘의 추천 액션입니다.',
        bullets: [
          '토스 공고는 재택 가능 — 거주지 무관하게 지원할 수 있습니다.',
          'Kotlin 경험이 있다면 토스 우선 지원을 고려해 보세요.',
          '카카오·쿠팡 공고는 마감 여유가 있으니 서류를 충분히 다듬은 뒤 제출하세요.',
        ],
      },
    ],
  },
  {
    id: 'r-2026-06-27',
    title: '6월 27일 채용 브리핑 — 백엔드 개발자',
    date: '2026년 6월 27일 (토)',
    readTime: '예약됨',
    status: 'scheduled',
    topics: ['목표 직무', '관심 기업', '기술/역량'],
    preview:
      '내일 오전 8시에 전송될 예정입니다. 선호도는 언제든 수정할 수 있습니다.',
    highlights: [],
    sections: [],
  },
]

export const MOCK_STATS = [
  { label: '연속 수신', value: '23일', hint: '꾸준히 읽고 있어요' },
  { label: '오늘 신규 공고', value: '3건', hint: '관심 기업 기준' },
  { label: '설정 조건', value: '6개', hint: '직무·기업·스킬 등' },
  { label: '마감 임박', value: '2건', hint: '3일 이내 마감' },
]

export function getMockReport(id: string): MockReport | undefined {
  return MOCK_REPORTS.find((r) => r.id === id)
}
