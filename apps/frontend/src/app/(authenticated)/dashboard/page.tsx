'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import {
  ArrowRight,
  CalendarClock,
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
        <div className="h-64 rounded-lg bg-muted" />
        <div className="h-32 rounded-lg bg-muted" />
        <div className="h-48 rounded-lg bg-muted" />
      </div>
    )
  }

  const latestBriefing = summary?.latestBriefing ?? null
  const recentReports = summary?.recentReports ?? []
  const nextDeliveryTime = summary?.nextDeliveryTime ?? null
  const preferences = summary?.briefingPreferences ?? []

  const displayName =
    summary?.user?.nickname ??
    user?.nickname ??
    user?.email?.split('@')[0] ??
    '사용자'

  const displayEmail = summary?.user?.email ?? user?.email ?? ''

  const prefKeywords = preferences
    .flatMap((p) => Object.values(p.preference).flat())
    .slice(0, 8)

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
          href="/mypage"
          className={cn(buttonVariants({ variant: 'outline' }), 'inline-flex items-center gap-2')}
        >
          <Settings2 className="size-4" />
          선호도 설정
        </Link>
      </div>

      {/* Latest briefing */}
      {latestBriefing ? (
        <Card className="mt-6 overflow-hidden">
          <div className="grid gap-0 lg:grid-cols-[1.4fr_1fr]">
            <CardContent className="p-6 sm:p-8">
              <div className="flex items-center gap-2">
                <Badge>
                  <Sparkles className="size-3.5" />
                  최신 브리핑
                </Badge>
                <span className="text-xs text-muted-foreground">
                  {latestBriefing.reportDate}
                </span>
              </div>
              <h2 className="mt-4 text-xl font-bold tracking-tight text-foreground">
                {latestBriefing.title}
              </h2>
              {latestBriefing.summary && (
                <p className="mt-2 text-sm leading-relaxed text-muted-foreground">
                  {latestBriefing.summary}
                </p>
              )}
              <p className="mt-3 text-sm text-muted-foreground">
                신규·마감 공고{' '}
                <span className="font-semibold text-foreground">
                  {latestBriefing.articleCount}건
                </span>
              </p>
              <Link
                href={`/reports/${latestBriefing.id}`}
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
                {prefKeywords.length > 0 ? (
                  prefKeywords.map((k) => (
                    <Badge key={k} variant="muted">
                      {k}
                    </Badge>
                  ))
                ) : (
                  <p className="text-xs text-muted-foreground">설정된 조건이 없습니다.</p>
                )}
              </div>
              <Link
                href="/mypage"
                className={cn(
                  buttonVariants({ variant: 'ghost', size: 'sm' }),
                  'mt-3 inline-flex items-center gap-1 px-0',
                )}
              >
                조건 편집
                <ArrowRight className="size-3.5" />
              </Link>
              <div className="mt-4 flex items-center gap-3 rounded-xl border border-border bg-card p-4">
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
      ) : (
        <Card className="mt-6">
          <CardContent className="flex flex-col items-center py-16 text-center">
            <Sparkles className="size-8 text-muted-foreground/40" />
            <p className="mt-3 text-sm font-medium text-foreground">아직 생성된 브리핑이 없습니다.</p>
            <p className="mt-1 text-xs text-muted-foreground">
              선호도를 설정하면 매일 아침 맞춤 브리핑이 전달됩니다.
            </p>
            <Link
              href="/mypage"
              className={cn(buttonVariants({ variant: 'outline' }), 'mt-5 inline-flex items-center gap-2')}
            >
              선호도 설정하기
              <ArrowRight className="size-4" />
            </Link>
          </CardContent>
        </Card>
      )}

      {/* Next briefing */}
      <Card className="mt-6">
        <CardContent className="p-6">
          <div className="flex items-center gap-2 text-sm font-medium text-foreground">
            <CalendarClock className="size-4 text-primary" />
            다음 브리핑
          </div>
          {nextDeliveryTime ? (
            <>
              <p className="mt-3 text-lg font-semibold text-foreground">
                {nextDeliveryTime}
              </p>
              <p className="mt-1 text-sm text-muted-foreground">
                설정한 조건 기준으로 브리핑이 생성됩니다.
              </p>
            </>
          ) : (
            <p className="mt-3 text-sm text-muted-foreground">
              예정된 브리핑이 없습니다.
            </p>
          )}
        </CardContent>
      </Card>

      {/* Recent reports */}
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
      {recentReports.length > 0 ? (
        <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {recentReports.map((r) => (
            <ReportCard key={r.id} report={briefingItemToCard(r)} />
          ))}
        </div>
      ) : (
        <div className="mt-4 flex items-center justify-center rounded-xl border border-border py-12">
          <p className="text-sm text-muted-foreground">최근 브리핑이 없습니다.</p>
        </div>
      )}
    </div>
  )
}
