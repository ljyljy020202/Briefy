'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import {
  ArrowRight,
  CalendarClock,
  Flame,
  Hash,
  Mail,
  Settings2,
  Sparkles,
} from 'lucide-react'

import { cn } from '@/lib/utils'
import { buttonVariants } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { dashboard } from '@/lib/api'
import type { BriefingListItem, DashboardSummary } from '@/types/api'
import { useAuthContext } from '@/contexts/AuthContext'
import { ReportCard } from '@/components/briefy/ReportCard'
import { MOCK_REPORTS, MOCK_STATS } from '@/lib/mock-data'
import type { MockReport } from '@/lib/mock-data'

function briefingItemToCard(item: BriefingListItem): MockReport {
  return {
    id: String(item.id),
    title: item.title,
    date: item.reportDate,
    readTime: item.articleCount > 0 ? `${item.articleCount}건` : '',
    status: 'delivered',
    topics: [],
    preview: item.summary ?? '',
    highlights: [],
    sections: [],
  }
}

export default function DashboardPage() {
  const { user, loading: authLoading } = useAuthContext()
  const [summary, setSummary] = useState<DashboardSummary | null>(null)
  const [dataLoading, setDataLoading] = useState(true)

  useEffect(() => {
    if (authLoading) return

    dashboard
      .getSummary()
      .then(setSummary)
      .catch(() => {
        if (user) {
          setSummary({
            user: {
              nickname: user.nickname,
              email: user.email,
              onboardingCompleted: user.onboardingCompleted,
            },
            briefingPreferences: [],
            nextDeliveryTime: null,
            latestBriefing: null,
            latestDeliveryStatus: null,
            recentReports: [],
          })
        }
      })
      .finally(() => setDataLoading(false))
  }, [authLoading, user])

  const loading = authLoading || dataLoading

  if (loading) {
    return (
      <div className="space-y-6 animate-pulse">
        <div className="h-12 w-64 rounded-lg bg-muted" />
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
          {[...Array(4)].map((_, i) => (
            <div key={i} className="h-28 rounded-lg bg-muted" />
          ))}
        </div>
        <div className="h-64 rounded-lg bg-muted" />
      </div>
    )
  }

  const hasRealReports = (summary?.recentReports?.length ?? 0) > 0
  const realLatest = hasRealReports ? (summary?.latestBriefing ?? null) : null
  const realRecent = summary?.recentReports ?? []

  // Mock fallbacks — used only when no real reports exist yet
  const mockLatest = !hasRealReports
    ? MOCK_REPORTS.find((r) => r.status === 'delivered')
    : null
  const mockScheduled = MOCK_REPORTS.find((r) => r.status === 'scheduled')
  const mockRecent = !hasRealReports
    ? MOCK_REPORTS.filter((r) => r.status === 'delivered').slice(0, 3)
    : []

  const displayName =
    summary?.user?.nickname ??
    user?.nickname ??
    user?.email?.split('@')[0] ??
    '사용자'

  const displayEmail = summary?.user?.email ?? user?.email ?? ''

  return (
    <div>
      {/* Greeting */}
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="text-sm text-muted-foreground">
            좋은 아침이에요, {displayName}님
          </p>
          <h1 className="mt-1 text-2xl font-bold tracking-tight text-foreground sm:text-3xl">
            오늘의 브리핑이 도착했어요
          </h1>
        </div>
        <Link
          href="/onboarding"
          className={cn(buttonVariants({ variant: 'outline' }), 'inline-flex items-center gap-2')}
        >
          <Settings2 className="size-4" />
          선호도 설정
        </Link>
      </div>

      {/* Stats */}
      <div className="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {MOCK_STATS.map((s, i) => (
          <Card key={s.label}>
            <CardContent className="p-5">
              <div className="flex items-center gap-2 text-xs text-muted-foreground">
                {i === 0 && <Flame className="size-3.5 text-primary" />}
                {s.label}
              </div>
              <p className="mt-2 text-2xl font-bold tracking-tight text-foreground">
                {s.value}
              </p>
              <p className="mt-1 text-xs text-muted-foreground">{s.hint}</p>
            </CardContent>
          </Card>
        ))}
      </div>

      {/* Latest briefing — real data when available, mock otherwise */}
      {realLatest ? (
        <Card className="mt-6 overflow-hidden">
          <div className="grid gap-0 lg:grid-cols-[1.4fr_1fr]">
            <CardContent className="p-6 sm:p-8">
              <div className="flex items-center gap-2">
                <Badge>
                  <Sparkles className="size-3.5" />
                  최신 브리핑
                </Badge>
                <span className="text-xs text-muted-foreground">
                  {realLatest.reportDate}
                </span>
              </div>
              <h2 className="mt-4 text-xl font-bold tracking-tight text-foreground">
                {realLatest.title}
              </h2>
              {realLatest.summary && (
                <p className="mt-2 text-sm leading-relaxed text-muted-foreground">
                  {realLatest.summary}
                </p>
              )}
              <p className="mt-3 text-sm text-muted-foreground">
                신규·마감 공고{' '}
                <span className="font-semibold text-foreground">
                  {realLatest.articleCount}건
                </span>
              </p>
              <Link
                href={`/reports/${realLatest.id}`}
                className={cn(
                  buttonVariants(),
                  'mt-6 inline-flex items-center gap-2',
                )}
              >
                전체 브리핑 읽기
                <ArrowRight className="size-4" />
              </Link>
            </CardContent>

            <div className="border-t border-border bg-secondary/40 p-6 sm:p-8 lg:border-l lg:border-t-0">
              <p className="text-sm font-medium text-foreground">설정한 조건</p>
              <div className="mt-3 flex flex-wrap gap-1.5">
                {(summary?.briefingPreferences ?? [])
                  .flatMap((p) => Object.values(p.preference).flat())
                  .slice(0, 8)
                  .map((k) => (
                    <Badge key={k} variant="muted">
                      {k}
                    </Badge>
                  ))}
              </div>
              <div className="mt-6 flex items-center gap-3 rounded-xl border border-border bg-card p-4">
                <span className="flex size-9 items-center justify-center rounded-lg bg-primary/10 text-primary">
                  <Mail className="size-4" />
                </span>
                <div className="text-sm">
                  <p className="font-medium text-foreground">{displayEmail}</p>
                  <p className="text-xs text-muted-foreground">매일 오전 8시 발송</p>
                </div>
              </div>
            </div>
          </div>
        </Card>
      ) : mockLatest ? (
        <Card className="mt-6 overflow-hidden">
          <div className="grid gap-0 lg:grid-cols-[1.4fr_1fr]">
            <CardContent className="p-6 sm:p-8">
              <div className="flex items-center gap-2">
                <Badge>
                  <Sparkles className="size-3.5" />
                  오늘의 브리핑
                </Badge>
                <span className="text-xs text-muted-foreground">
                  {mockLatest.date} · {mockLatest.readTime}
                </span>
              </div>
              <h2 className="mt-4 text-xl font-bold tracking-tight text-foreground">
                {mockLatest.title}
              </h2>
              <p className="mt-2 text-sm leading-relaxed text-muted-foreground">
                {mockLatest.preview}
              </p>
              <ul className="mt-5 space-y-2.5">
                {mockLatest.highlights.map((h) => (
                  <li key={h} className="flex items-start gap-2.5 text-sm">
                    <span className="mt-1.5 size-1.5 shrink-0 rounded-full bg-primary" />
                    <span className="text-foreground">{h}</span>
                  </li>
                ))}
              </ul>
              <Link
                href={`/reports/${mockLatest.id}`}
                className={cn(
                  buttonVariants(),
                  'mt-6 inline-flex items-center gap-2',
                )}
              >
                전체 브리핑 읽기
                <ArrowRight className="size-4" />
              </Link>
            </CardContent>

            <div className="border-t border-border bg-secondary/40 p-6 sm:p-8 lg:border-l lg:border-t-0">
              <p className="text-sm font-medium text-foreground">브리핑 조건</p>
              <div className="mt-3 flex flex-wrap gap-1.5">
                {mockLatest.topics.map((t) => (
                  <Badge key={t} variant="muted">
                    {t}
                  </Badge>
                ))}
              </div>
              <div className="mt-6 flex items-center gap-3 rounded-xl border border-border bg-card p-4">
                <span className="flex size-9 items-center justify-center rounded-lg bg-primary/10 text-primary">
                  <Mail className="size-4" />
                </span>
                <div className="text-sm">
                  <p className="font-medium text-foreground">{displayEmail}</p>
                  <p className="text-xs text-muted-foreground">매일 오전 8시 발송</p>
                </div>
              </div>
            </div>
          </div>
        </Card>
      ) : null}

      {/* Next + keywords row */}
      <div className="mt-6 grid gap-6 lg:grid-cols-2">
        {mockScheduled && (
          <Card>
            <CardContent className="p-6">
              <div className="flex items-center gap-2 text-sm font-medium text-foreground">
                <CalendarClock className="size-4 text-primary" />
                다음 브리핑
              </div>
              <p className="mt-3 text-lg font-semibold text-foreground">
                {mockScheduled.date}
              </p>
              <p className="mt-1 text-sm text-muted-foreground">
                {mockScheduled.preview}
              </p>
              <div className="mt-4 flex flex-wrap gap-1.5">
                {mockScheduled.topics.map((t) => (
                  <Badge key={t} variant="muted">
                    {t}
                  </Badge>
                ))}
              </div>
            </CardContent>
          </Card>
        )}

        <Card>
          <CardContent className="p-6">
            <div className="flex items-center gap-2 text-sm font-medium text-foreground">
              <Hash className="size-4 text-primary" />
              설정한 조건
            </div>
            <div className="mt-4 flex flex-wrap gap-2">
              {(summary?.briefingPreferences ?? [])
                .flatMap((p) => Object.values(p.preference).flat()).length > 0
                ? (summary?.briefingPreferences ?? [])
                    .flatMap((p) => Object.values(p.preference).flat())
                    .map((k) => <Badge key={k}>{k}</Badge>)
                : ['백엔드 개발자', '네이버', 'Spring Boot'].map((k) => (
                    <Badge key={k}>{k}</Badge>
                  ))}
            </div>
            <Link
              href="/onboarding"
              className={cn(
                buttonVariants({ variant: 'ghost', size: 'sm' }),
                'mt-4 inline-flex items-center gap-1',
              )}
            >
              조건 편집
              <ArrowRight className="size-3.5" />
            </Link>
          </CardContent>
        </Card>
      </div>

      {/* Recent reports — real data when available, mock otherwise */}
      <div className="mt-8 flex items-center justify-between">
        <h2 className="text-lg font-semibold text-foreground">최근 브리핑</h2>
        <Link
          href="/reports"
          className={cn(
            buttonVariants({ variant: 'ghost', size: 'sm' }),
            'inline-flex items-center gap-1',
          )}
        >
          전체 보기
          <ArrowRight className="size-3.5" />
        </Link>
      </div>
      <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {hasRealReports
          ? realRecent.map((r) => (
              <ReportCard key={r.id} report={briefingItemToCard(r)} />
            ))
          : mockRecent.map((r) => <ReportCard key={r.id} report={r} />)}
      </div>
    </div>
  )
}
