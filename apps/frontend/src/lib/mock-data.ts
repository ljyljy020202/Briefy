// TODO: Remove this file when all backend endpoints are fully implemented.
// Used by landing page for sample display and as dev fallback in authenticated pages.

export type MockTopic = {
  id: string
  label: string
  description: string
}

export type MockReportStatus = 'delivered' | 'scheduled' | 'draft'

export type MockReportSection = {
  heading: string
  summary: string
  bullets: string[]
  source?: string
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
}

export const MOCK_TOPICS: MockTopic[] = [
  { id: 'ai', label: 'AI / 머신러닝', description: '최신 모델, 논문, 릴리즈' },
  { id: 'dev', label: '백엔드 개발', description: '아키텍처, 인프라, DevOps' },
  { id: 'startup', label: '스타트업', description: '투자, 채용, 시장 동향' },
  { id: 'finance', label: '경제 / 금융', description: '시장, 금리, 환율' },
  { id: 'product', label: '프로덕트', description: '디자인, 그로스, PM' },
  { id: 'security', label: '보안', description: 'CVE, 침해 사고, 대응' },
  { id: 'cloud', label: '클라우드', description: 'AWS, GCP, Azure 소식' },
  { id: 'opensource', label: '오픈소스', description: '주요 릴리즈와 PR' },
]

export const SUGGESTED_KEYWORDS = [
  'Next.js',
  'PostgreSQL',
  'Kubernetes',
  'LLM',
  'Rust',
  'gRPC',
  'Vercel',
  'Kafka',
  '금리 인하',
  'Series A',
]

export const DELIVERY_TIMES = [
  { id: 'early', label: '오전 6시', hint: '출근 전 미리' },
  { id: 'morning', label: '오전 8시', hint: '가장 인기' },
  { id: 'work', label: '오전 9시', hint: '업무 시작' },
  { id: 'lunch', label: '오후 12시', hint: '점심 시간' },
]

export const MOCK_REPORTS: MockReport[] = [
  {
    id: 'r-2026-06-25',
    title: '6월 25일 아침 브리핑',
    date: '2026년 6월 25일 (목)',
    readTime: '4분 분량',
    status: 'delivered',
    topics: ['AI / 머신러닝', '백엔드 개발', '클라우드'],
    preview:
      '오픈소스 LLM 추론 비용이 다시 한 번 절반으로 줄었고, 주요 클라우드 3사가 동시에 서버리스 GPU를 발표했습니다.',
    highlights: [
      '신규 오픈 가중치 모델, 추론 비용 52% 절감',
      'AWS·GCP·Azure, 서버리스 GPU 동시 공개',
      'PostgreSQL 18 RC1 — 비동기 I/O 정식 지원',
    ],
    sections: [
      {
        heading: 'AI / 머신러닝',
        summary:
          '새 오픈 가중치 모델이 동급 성능을 유지하면서 추론 비용을 절반 가까이 낮췄습니다. 양자화 친화적 구조가 핵심입니다.',
        bullets: [
          '7B 규모로 70B급 추론 정확도에 근접',
          'MoE 라우팅 최적화로 토큰당 비용 52% 감소',
          'Apache 2.0 라이선스로 상업적 사용 가능',
        ],
        source: 'arXiv · HuggingFace',
      },
      {
        heading: '클라우드 인프라',
        summary:
          '세 개 주요 클라우드 사업자가 같은 주에 서버리스 GPU 제품을 발표하며 콜드 스타트 경쟁이 본격화됐습니다.',
        bullets: [
          '초당 과금, 콜드 스타트 2초 이내 목표',
          'Inference 전용 인스턴스 프리뷰 시작',
          '컨테이너 이미지 그대로 배포 가능',
        ],
        source: 'AWS Blog · GCP Release Notes',
      },
      {
        heading: '백엔드 개발',
        summary:
          'PostgreSQL 18 RC1이 비동기 I/O를 정식 지원하며, 고동시성 워크로드에서 의미 있는 처리량 향상을 보였습니다.',
        bullets: [
          'io_uring 기반 비동기 I/O 정식 도입',
          '읽기 집약 워크로드에서 처리량 최대 2.8배',
          'pg_upgrade 호환성 유지',
        ],
        source: 'PostgreSQL Weekly',
      },
    ],
  },
  {
    id: 'r-2026-06-24',
    title: '6월 24일 아침 브리핑',
    date: '2026년 6월 24일 (수)',
    readTime: '3분 분량',
    status: 'delivered',
    topics: ['스타트업', '경제 / 금융'],
    preview:
      '국내 AI 인프라 스타트업이 시리즈 B를 마감했고, 미국 연준은 금리 동결을 시사했습니다.',
    highlights: [
      'AI 인프라 스타트업, 시리즈 B 4,200만 달러',
      '연준 의장, 추가 인상 가능성 낮춰',
      '개발자 채용 시장, 인프라 직군 중심 회복세',
    ],
    sections: [
      {
        heading: '스타트업 / 투자',
        summary:
          'GPU 스케줄링을 자동화하는 인프라 스타트업이 시리즈 B를 마감하며 기업 가치를 두 배로 끌어올렸습니다.',
        bullets: [
          '시리즈 B 4,200만 달러 유치',
          '주요 고객사 분기 매출 3배 성장',
          '플랫폼·SRE 직군 집중 채용 예정',
        ],
        source: 'TechCrunch',
      },
      {
        heading: '경제 / 금융',
        summary:
          '연준은 인플레이션 둔화를 근거로 금리 동결 기조를 재확인했고, 시장은 연내 인하 가능성에 무게를 뒀습니다.',
        bullets: [
          '기준금리 동결, 추가 인상 가능성 축소',
          '기술주 중심으로 위험 선호 회복',
          '환율 변동성 완화 흐름',
        ],
        source: 'Bloomberg',
      },
    ],
  },
  {
    id: 'r-2026-06-23',
    title: '6월 23일 아침 브리핑',
    date: '2026년 6월 23일 (화)',
    readTime: '4분 분량',
    status: 'delivered',
    topics: ['보안', '오픈소스', '백엔드 개발'],
    preview:
      '널리 쓰이는 직렬화 라이브러리에서 원격 코드 실행 취약점이 공개되어 즉시 패치가 권고됩니다.',
    highlights: [
      '인기 직렬화 라이브러리에서 RCE 취약점 공개',
      '주요 런타임, 보안 패치 릴리즈',
      '컨테이너 런타임 메모리 사용량 30% 절감 업데이트',
    ],
    sections: [
      {
        heading: '보안',
        summary:
          '광범위하게 사용되는 직렬화 라이브러리에서 신뢰할 수 없는 입력으로 RCE가 가능한 취약점이 보고됐습니다.',
        bullets: [
          'CVSS 9.1, 즉시 업그레이드 권고',
          '임시 완화책으로 입력 검증 강화 제시',
          '주요 배포판에 패치 백포트 진행 중',
        ],
        source: 'NVD · GitHub Advisory',
      },
      {
        heading: '오픈소스',
        summary:
          '대표 컨테이너 런타임의 새 마이너 릴리즈가 메모리 사용량을 크게 줄이며 엣지 환경에 적합해졌습니다.',
        bullets: [
          '런타임 메모리 사용량 약 30% 감소',
          '시작 시간 단축으로 콜드 스타트 개선',
          'cgroup v2 기본 활성화',
        ],
        source: 'CNCF Blog',
      },
    ],
  },
  {
    id: 'r-2026-06-26',
    title: '6월 26일 아침 브리핑',
    date: '2026년 6월 26일 (금)',
    readTime: '예약됨',
    status: 'scheduled',
    topics: ['AI / 머신러닝', '프로덕트'],
    preview: '내일 오전 8시에 전송될 예정입니다. 주제와 키워드는 언제든 수정할 수 있습니다.',
    highlights: [],
    sections: [],
  },
]

export const MOCK_STATS = [
  { label: '연속 수신', value: '23일', hint: '꾸준히 읽고 있어요' },
  { label: '이번 주 절약 시간', value: '2.5시간', hint: '직접 검색 대비' },
  { label: '구독 주제', value: '5개', hint: '맞춤 큐레이션' },
  { label: '평균 읽기 시간', value: '3.8분', hint: '핵심만 요약' },
]

export function getMockReport(id: string): MockReport | undefined {
  return MOCK_REPORTS.find((r) => r.id === id)
}
