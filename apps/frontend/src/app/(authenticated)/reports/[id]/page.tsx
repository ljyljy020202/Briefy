'use client'

import { useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import Link from 'next/link'
import {
  ArrowLeft,
  Bookmark,
  Clock,
  ExternalLink,
  Share2,
  Sparkles,
} from 'lucide-react'

import { cn } from '@/lib/utils'
import { buttonVariants } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { briefings } from '@/lib/api'
import type { BriefingDetail } from '@/types/api'
import { getMockReport, type MockReport } from '@/lib/mock-data'
import { FeedbackButtons } from '@/components/reports/FeedbackButtons'

export default function ReportDetailPage() {
  const { id } = useParams<{ id: string }>()
  const numericId = Number(id)
  const [report, setReport] = useState<BriefingDetail | null>(null)
  const [mockReport, setMockReport] = useState<MockReport | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!id) return

    briefings
      .get(numericId)
      .then(setReport)
      .catch(() => {
        // TODO: Remove mock fallback when GET /api/briefings/{id} is implemented (Backend step 8)
        const mock = getMockReport(id)
        if (mock && mock.status === 'delivered') {
          setMockReport(mock)
        } else {
          setError('브리핑을 불러올 수 없습니다.')
        }
      })
      .finally(() => setLoading(false))
  }, [id, numericId])

  if (loading) {
    return (
      <div className="space-y-6 animate-pulse max-w-5xl">
        <div className="h-5 w-24 rounded bg-muted" />
        <div className="h-10 w-2/3 rounded bg-muted" />
        <div className="grid lg:grid-cols-[1fr_260px] gap-8">
          <div className="space-y-4">
            <div className="h-32 rounded-lg bg-muted" />
            <div className="h-48 rounded-lg bg-muted" />
          </div>
          <div className="h-48 rounded-lg bg-muted" />
        </div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="text-center py-16 space-y-2">
        <p className="text-destructive text-sm">{error}</p>
        <Link
          href="/reports"
          className="text-xs text-muted-foreground hover:underline"
        >
          ← 보관함으로
        </Link>
      </div>
    )
  }

  // Render mock report (dev fallback)
  if (mockReport) {
    return (
      <div>
        <Link
          href="/reports"
          className={cn(
            buttonVariants({ variant: 'ghost', size: 'sm' }),
            'mb-6 -ml-2 inline-flex items-center gap-2',
          )}
        >
          <ArrowLeft className="size-4" />
          보관함으로
        </Link>

        <article className="grid gap-8 lg:grid-cols-[1fr_260px]">
          {/* Main column */}
          <div>
            <div className="flex items-center gap-2 text-xs text-muted-foreground">
              <Clock className="size-3.5" />
              {mockReport.date} · {mockReport.readTime}
            </div>
            <h1 className="mt-3 text-balance text-3xl font-bold tracking-tight text-foreground">
              {mockReport.title}
            </h1>
            <p className="mt-3 text-pretty leading-relaxed text-muted-foreground">
              {mockReport.preview}
            </p>

            <div className="mt-5 flex flex-wrap items-center gap-2">
              {mockReport.topics.map((t) => (
                <Badge key={t} variant="muted">
                  {t}
                </Badge>
              ))}
            </div>

            {/* TL;DR */}
            {mockReport.highlights.length > 0 && (
              <Card className="mt-8 border-primary/20 bg-accent/50">
                <CardContent className="p-6">
                  <div className="flex items-center gap-2 text-sm font-semibold text-accent-foreground">
                    <Sparkles className="size-4" />
                    3줄 요약
                  </div>
                  <ul className="mt-4 space-y-2.5">
                    {mockReport.highlights.map((h) => (
                      <li key={h} className="flex items-start gap-2.5 text-sm">
                        <span className="mt-1.5 size-1.5 shrink-0 rounded-full bg-primary" />
                        <span className="text-foreground">{h}</span>
                      </li>
                    ))}
                  </ul>
                </CardContent>
              </Card>
            )}

            {/* Sections */}
            <div className="mt-10 space-y-10">
              {mockReport.sections.map((sec, i) => (
                <section key={sec.heading}>
                  <div className="flex items-baseline gap-3">
                    <span className="font-mono text-sm font-semibold text-primary">
                      {String(i + 1).padStart(2, '0')}
                    </span>
                    <h2 className="text-xl font-bold tracking-tight text-foreground">
                      {sec.heading}
                    </h2>
                  </div>
                  <p className="mt-3 leading-relaxed text-muted-foreground">
                    {sec.summary}
                  </p>
                  <ul className="mt-4 space-y-2.5 rounded-xl border border-border bg-card p-5">
                    {sec.bullets.map((b) => (
                      <li key={b} className="flex items-start gap-2.5 text-sm">
                        <span className="mt-1.5 size-1.5 shrink-0 rounded-full bg-primary/60" />
                        <span className="text-foreground">{b}</span>
                      </li>
                    ))}
                  </ul>
                  {sec.source && (
                    <a
                      href="#"
                      className="mt-3 inline-flex items-center gap-1.5 text-sm font-medium text-primary hover:underline"
                    >
                      출처: {sec.source}
                      <ExternalLink className="size-3.5" />
                    </a>
                  )}
                </section>
              ))}
            </div>
          </div>

          {/* Sidebar */}
          <aside className="lg:sticky lg:top-10 lg:h-fit">
            <Card>
              <CardContent className="p-5">
                <p className="text-sm font-medium text-foreground">이 브리핑</p>
                <div className="mt-4 space-y-2">
                  <button
                    type="button"
                    className={cn(
                      buttonVariants({ variant: 'outline' }),
                      'w-full justify-start gap-2',
                    )}
                  >
                    <Bookmark className="size-4" />
                    저장하기
                  </button>
                  <button
                    type="button"
                    className={cn(
                      buttonVariants({ variant: 'outline' }),
                      'w-full justify-start gap-2',
                    )}
                  >
                    <Share2 className="size-4" />
                    공유하기
                  </button>
                </div>

                <div className="mt-5 border-t border-border pt-5">
                  <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                    목차
                  </p>
                  <ul className="mt-3 space-y-2">
                    {mockReport.sections.map((sec, i) => (
                      <li
                        key={sec.heading}
                        className="flex items-center gap-2 text-sm text-muted-foreground"
                      >
                        <span className="font-mono text-xs text-primary">
                          {String(i + 1).padStart(2, '0')}
                        </span>
                        {sec.heading}
                      </li>
                    ))}
                  </ul>
                </div>
              </CardContent>
            </Card>

            <Card className="mt-4 bg-secondary/40">
              <CardContent className="p-5">
                <p className="text-sm font-medium text-foreground">
                  내일도 받아보세요
                </p>
                <p className="mt-1 text-xs leading-relaxed text-muted-foreground">
                  주제를 더 추가하면 브리핑이 더 풍부해집니다.
                </p>
                <Link
                  href="/onboarding"
                  className={cn(buttonVariants({ size: 'sm' }), 'mt-3 w-full')}
                >
                  주제 편집
                </Link>
              </CardContent>
            </Card>
          </aside>
        </article>
      </div>
    )
  }

  // Render real API report
  if (!report) return null

  return (
    <div>
      <Link
        href="/reports"
        className={cn(
          buttonVariants({ variant: 'ghost', size: 'sm' }),
          'mb-6 -ml-2 inline-flex items-center gap-2',
        )}
      >
        <ArrowLeft className="size-4" />
        보관함으로
      </Link>

      <article className="grid gap-8 lg:grid-cols-[1fr_260px]">
        {/* Main column */}
        <div>
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            <Clock className="size-3.5" />
            {report.reportDate}
          </div>
          <h1 className="mt-3 text-balance text-3xl font-bold tracking-tight text-foreground">
            {report.title}
          </h1>
          {report.summary && (
            <p className="mt-3 text-pretty leading-relaxed text-muted-foreground">
              {report.summary}
            </p>
          )}

          <div className="mt-8 prose prose-sm max-w-none">
            <pre className="whitespace-pre-wrap font-sans text-sm leading-relaxed text-foreground">
              {report.content}
            </pre>
          </div>

          {report.articles.length > 0 && (
            <div className="mt-10 border-t pt-8 space-y-4">
              <h2 className="text-base font-semibold">출처</h2>
              {report.articles.map((article, i) => (
                <div key={i} className="space-y-1">
                  <a
                    href={article.url}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="inline-flex items-center gap-1.5 text-sm font-medium text-primary hover:underline"
                  >
                    {article.title}
                    <ExternalLink className="size-3.5" />
                  </a>
                  <p className="text-xs text-muted-foreground">{article.source}</p>
                </div>
              ))}
            </div>
          )}

          <div className="mt-10 border-t pt-8">
            <FeedbackButtons reportId={numericId} />
          </div>
        </div>

        {/* Sidebar */}
        <aside className="lg:sticky lg:top-10 lg:h-fit">
          <Card>
            <CardContent className="p-5">
              <p className="text-sm font-medium text-foreground">이 브리핑</p>
              <div className="mt-4 space-y-2">
                <button
                  type="button"
                  className={cn(
                    buttonVariants({ variant: 'outline' }),
                    'w-full justify-start gap-2',
                  )}
                >
                  <Bookmark className="size-4" />
                  저장하기
                </button>
                <button
                  type="button"
                  className={cn(
                    buttonVariants({ variant: 'outline' }),
                    'w-full justify-start gap-2',
                  )}
                >
                  <Share2 className="size-4" />
                  공유하기
                </button>
              </div>
            </CardContent>
          </Card>

          <Card className="mt-4 bg-secondary/40">
            <CardContent className="p-5">
              <p className="text-sm font-medium text-foreground">
                내일도 받아보세요
              </p>
              <p className="mt-1 text-xs leading-relaxed text-muted-foreground">
                주제를 더 추가하면 브리핑이 더 풍부해집니다.
              </p>
              <Link
                href="/onboarding"
                className={cn(buttonVariants({ size: 'sm' }), 'mt-3 w-full')}
              >
                주제 편집
              </Link>
            </CardContent>
          </Card>
        </aside>
      </article>
    </div>
  )
}
