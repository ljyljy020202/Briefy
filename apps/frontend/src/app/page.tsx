import Link from 'next/link'
import {
  ArrowRight,
  Clock,
  Filter,
  Mail,
  Quote,
  Sparkles,
  Check,
} from 'lucide-react'

import { cn } from '@/lib/utils'
import { buttonVariants } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { BriefyLogo } from '@/components/briefy/BriefyLogo'
import { MarketingNav } from '@/components/briefy/MarketingNav'
import { GoogleLoginButton } from '@/components/auth/GoogleLoginButton'
import { MOCK_REPORTS } from '@/lib/mock-data'

const FEATURES = [
  {
    icon: Filter,
    title: '주제와 키워드로 큐레이션',
    desc: '관심 주제와 키워드를 선택하면, 노이즈를 걷어내고 정말 중요한 소식만 모읍니다.',
  },
  {
    icon: Sparkles,
    title: 'AI가 핵심만 요약',
    desc: '수십 개의 출처를 읽고 3~4분이면 끝나는 브리핑으로 압축합니다.',
  },
  {
    icon: Mail,
    title: '매일 아침 메일함으로',
    desc: '원하는 시간에 맞춰 이메일로 도착합니다. 앱을 열 필요도 없습니다.',
  },
  {
    icon: Clock,
    title: '하루 10분을 돌려드려요',
    desc: '여러 사이트를 돌며 검색하는 시간을 아껴 본업에 집중하세요.',
  },
]

const STEPS = [
  {
    step: '01',
    title: '주제를 고르세요',
    desc: 'AI, 백엔드, 스타트업 등 관심 분야와 키워드를 선택합니다.',
  },
  {
    step: '02',
    title: '시간을 정하세요',
    desc: '오전 6시부터 점심까지, 받고 싶은 시간을 지정합니다.',
  },
  {
    step: '03',
    title: '아침마다 받아보세요',
    desc: '매일 정해진 시간, AI가 정리한 맞춤 브리핑이 도착합니다.',
  },
]

export default function LandingPage() {
  const sample = MOCK_REPORTS[0]

  return (
    <div className="min-h-screen bg-background">
      <MarketingNav />

      {/* Hero */}
      <section className="relative">
        <div className="mx-auto max-w-6xl px-4 pb-20 pt-16 sm:px-6 lg:px-8 lg:pt-24">
          <div className="grid items-center gap-12 lg:grid-cols-2">
            <div>
              <Badge variant="outline" className="mb-6 bg-card">
                <Sparkles className="size-3.5 text-primary" />
                매일 아침, AI 맞춤 브리핑
              </Badge>
              <h1 className="text-balance text-4xl font-bold leading-[1.1] tracking-tight text-foreground sm:text-5xl lg:text-6xl">
                중요한 소식만,
                <br />
                <span className="text-primary">아침 한 통의 메일</span>로.
              </h1>
              <p className="mt-6 max-w-md text-pretty text-base leading-relaxed text-muted-foreground sm:text-lg">
                Briefy는 당신이 고른 주제와 키워드를 바탕으로, AI가 정리한 하루치
                브리핑을 매일 아침 이메일로 보내드립니다.
              </p>
              <div className="mt-8 flex flex-col gap-3 sm:flex-row">
                <Link
                  href="/onboarding"
                  className={cn(
                    buttonVariants({ size: 'lg' }),
                    'h-11 px-6 text-sm inline-flex items-center gap-2',
                  )}
                >
                  무료로 시작하기
                  <ArrowRight className="size-4" />
                </Link>
                <a
                  href="#sample"
                  className={cn(
                    buttonVariants({ variant: 'outline', size: 'lg' }),
                    'h-11 px-6 text-sm',
                  )}
                >
                  브리핑 예시 보기
                </a>
              </div>
              <p className="mt-4 text-xs text-muted-foreground">
                신용카드 불필요 · 1분이면 설정 완료 · 언제든 해지
              </p>
            </div>

            {/* Email preview card */}
            <div className="relative">
              <Card className="overflow-hidden">
                <div className="flex items-center justify-between border-b border-border bg-secondary/50 px-5 py-3">
                  <div className="flex items-center gap-2">
                    <BriefyLogo size="sm" showWordmark={false} />
                    <div className="leading-tight">
                      <p className="text-sm font-medium text-foreground">
                        오늘의 Briefy
                      </p>
                      <p className="text-xs text-muted-foreground">
                        오전 8:00 · 4분 분량
                      </p>
                    </div>
                  </div>
                  <Badge variant="success">도착함</Badge>
                </div>
                <CardContent className="space-y-4 p-5">
                  <p className="text-sm leading-relaxed text-muted-foreground">
                    {sample.preview}
                  </p>
                  <ul className="space-y-2.5">
                    {sample.highlights.map((h) => (
                      <li key={h} className="flex items-start gap-2.5 text-sm">
                        <span className="mt-1 flex size-4 shrink-0 items-center justify-center rounded-full bg-primary/10">
                          <Check className="size-2.5 text-primary" />
                        </span>
                        <span className="text-foreground">{h}</span>
                      </li>
                    ))}
                  </ul>
                  <div className="flex flex-wrap gap-1.5 pt-1">
                    {sample.topics.map((t) => (
                      <Badge key={t} variant="muted">
                        {t}
                      </Badge>
                    ))}
                  </div>
                </CardContent>
              </Card>
            </div>
          </div>

          {/* trust row */}
          <div className="mt-16 flex flex-wrap items-center justify-center gap-x-10 gap-y-3 text-sm text-muted-foreground">
            <span>매일 12,000+ 개발자가 읽는 아침 브리핑</span>
            <span className="hidden h-4 w-px bg-border sm:block" />
            <span>평균 읽기 시간 3.8분</span>
            <span className="hidden h-4 w-px bg-border sm:block" />
            <span>구독 만족도 4.8 / 5</span>
          </div>
        </div>
      </section>

      {/* Features */}
      <section
        id="features"
        className="mx-auto max-w-6xl px-4 py-20 sm:px-6 lg:px-8"
      >
        <div className="mx-auto max-w-2xl text-center">
          <h2 className="text-balance text-3xl font-bold tracking-tight text-foreground sm:text-4xl">
            정보 과부하 없이, 핵심만
          </h2>
          <p className="mt-4 text-pretty text-muted-foreground">
            검색하고 거르는 일은 Briefy에게 맡기고, 당신은 결정만 하세요.
          </p>
        </div>
        <div className="mt-12 grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
          {FEATURES.map((f) => (
            <Card key={f.title}>
              <CardContent className="p-6">
                <span className="flex size-11 items-center justify-center rounded-xl bg-accent text-accent-foreground">
                  <f.icon className="size-5" />
                </span>
                <h3 className="mt-4 font-semibold text-foreground">{f.title}</h3>
                <p className="mt-2 text-sm leading-relaxed text-muted-foreground">
                  {f.desc}
                </p>
              </CardContent>
            </Card>
          ))}
        </div>
      </section>

      {/* How it works */}
      <section id="how" className="border-y border-border bg-secondary/40">
        <div className="mx-auto max-w-6xl px-4 py-20 sm:px-6 lg:px-8">
          <div className="mx-auto max-w-2xl text-center">
            <h2 className="text-balance text-3xl font-bold tracking-tight text-foreground sm:text-4xl">
              세 단계면 충분합니다
            </h2>
            <p className="mt-4 text-pretty text-muted-foreground">
              설정은 1분, 효과는 매일 아침.
            </p>
          </div>
          <div className="mt-12 grid gap-6 md:grid-cols-3">
            {STEPS.map((s) => (
              <Card key={s.step} className="h-full">
                <CardContent className="p-6">
                  <span className="font-mono text-sm font-semibold text-primary">
                    {s.step}
                  </span>
                  <h3 className="mt-3 text-lg font-semibold text-foreground">
                    {s.title}
                  </h3>
                  <p className="mt-2 text-sm leading-relaxed text-muted-foreground">
                    {s.desc}
                  </p>
                </CardContent>
              </Card>
            ))}
          </div>
        </div>
      </section>

      {/* Sample briefing */}
      <section
        id="sample"
        className="mx-auto max-w-6xl px-4 py-20 sm:px-6 lg:px-8"
      >
        <div className="grid items-center gap-12 lg:grid-cols-2">
          <div>
            <Badge variant="outline" className="mb-4 bg-card">
              브리핑 예시
            </Badge>
            <h2 className="text-balance text-3xl font-bold tracking-tight text-foreground sm:text-4xl">
              읽기 좋게 정리된
              <br />
              하루치 인사이트
            </h2>
            <p className="mt-4 text-pretty leading-relaxed text-muted-foreground">
              섹션별 요약과 핵심 불릿, 출처까지. 길게 읽지 않아도 흐름을 놓치지
              않습니다. 더 깊이 보고 싶을 땐 원문 링크로 바로 이동하세요.
            </p>
            <figure className="mt-8 rounded-xl border border-border bg-card p-6">
              <Quote className="size-5 text-primary" />
              <blockquote className="mt-3 text-pretty text-sm leading-relaxed text-foreground">
                &ldquo;아침에 뉴스레터 다섯 개를 보던 걸 Briefy 하나로 줄였어요.
                백엔드 직군에 딱 맞는 큐레이션이라 출근길에 다 읽힙니다.&rdquo;
              </blockquote>
              <figcaption className="mt-4 text-sm text-muted-foreground">
                — 9년 차 백엔드 엔지니어
              </figcaption>
            </figure>
          </div>

          <Card className="overflow-hidden">
            <div className="border-b border-border bg-secondary/50 px-6 py-4">
              <p className="text-sm font-medium text-foreground">
                {sample.title}
              </p>
              <p className="text-xs text-muted-foreground">
                {sample.date} · {sample.readTime}
              </p>
            </div>
            <CardContent className="space-y-6 p-6">
              {sample.sections.map((sec) => (
                <div key={sec.heading}>
                  <h4 className="text-sm font-semibold text-foreground">
                    {sec.heading}
                  </h4>
                  <p className="mt-1.5 text-sm leading-relaxed text-muted-foreground">
                    {sec.summary}
                  </p>
                </div>
              ))}
              <Link
                href="/reports"
                className={cn(buttonVariants({ variant: 'outline' }), 'w-full')}
              >
                전체 브리핑 보기
              </Link>
            </CardContent>
          </Card>
        </div>
      </section>

      {/* CTA / pricing */}
      <section
        id="pricing"
        className="mx-auto max-w-6xl px-4 pb-24 sm:px-6 lg:px-8"
      >
        <Card className="overflow-hidden bg-primary text-primary-foreground border-0">
          <CardContent className="grid items-center gap-8 p-8 sm:p-12 lg:grid-cols-2">
            <div>
              <h2 className="text-balance text-3xl font-bold tracking-tight sm:text-4xl">
                내일 아침부터 시작하세요
              </h2>
              <p className="mt-4 max-w-md text-pretty leading-relaxed text-primary-foreground/80">
                무료 플랜으로 매일 한 통의 맞춤 브리핑을 받아보세요. 설정은
                1분이면 끝납니다.
              </p>
              <ul className="mt-6 space-y-2 text-sm text-primary-foreground/90">
                {['주제 5개까지 선택', '매일 아침 1회 발송', '언제든 해지 가능'].map(
                  (item) => (
                    <li key={item} className="flex items-center gap-2">
                      <Check className="size-4" />
                      {item}
                    </li>
                  ),
                )}
              </ul>
            </div>
            <div className="rounded-xl bg-card p-6 text-foreground">
              <p className="text-sm font-medium text-foreground">
                지금 무료로 시작
              </p>
              <p className="mt-1 text-xs text-muted-foreground">
                Google 계정으로 1초 만에 시작하세요.
              </p>
              <div className="mt-4">
                <GoogleLoginButton />
              </div>
              <div className="my-4 flex items-center gap-3">
                <span className="h-px flex-1 bg-border" />
                <span className="text-xs text-muted-foreground">또는</span>
                <span className="h-px flex-1 bg-border" />
              </div>
              <Link
                href="/onboarding"
                className={cn(
                  buttonVariants({ variant: 'outline' }),
                  'h-11 w-full',
                )}
              >
                이메일로 설정하기
              </Link>
            </div>
          </CardContent>
        </Card>
      </section>

      {/* Footer */}
      <footer className="border-t border-border">
        <div className="mx-auto flex max-w-6xl flex-col items-center justify-between gap-4 px-4 py-10 sm:flex-row sm:px-6 lg:px-8">
          <BriefyLogo size="sm" />
          <p className="text-sm text-muted-foreground">
            © 2026 Briefy. 매일 아침을 가볍게.
          </p>
          <div className="flex items-center gap-6 text-sm text-muted-foreground">
            <a href="#" className="hover:text-foreground">
              이용약관
            </a>
            <a href="#" className="hover:text-foreground">
              개인정보
            </a>
          </div>
        </div>
      </footer>
    </div>
  )
}
