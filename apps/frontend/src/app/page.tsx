import Link from 'next/link'
import {
  ArrowRight,
  Clock,
  Filter,
  Mail,
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
import { KakaoLoginButton } from '@/components/auth/KakaoLoginButton'
import { MOCK_REPORTS } from '@/lib/mock-data'

const FEATURES = [
  {
    icon: Filter,
    title: '선호도 기반 큐레이션',
    desc: '목표 직무·기업·스킬을 설정하면, 노이즈를 걷어내고 정말 관련 있는 정보만 모읍니다.',
  },
  {
    icon: Sparkles,
    title: 'AI가 핵심만 요약',
    desc: '수십 개의 출처를 읽고 3~4분이면 끝나는 브리핑으로 압축합니다.',
  },
  {
    icon: Mail,
    title: '매일 아침 메일함으로',
    desc: '매일 오전 8시, 이메일로 도착합니다. 앱을 열 필요도 없습니다.',
  },
  {
    icon: Clock,
    title: '하루 10분을 돌려드려요',
    desc: '여러 사이트를 돌며 검색하는 시간을 아껴 본업에 집중하세요.',
  },
]

export default function LandingPage() {
  const sample = MOCK_REPORTS[0]

  return (
    <div className="min-h-screen bg-background">
      <MarketingNav />

      {/* Hero */}
      <section className="relative">
        <div className="mx-auto max-w-4xl px-4 pb-20 pt-16 sm:px-6 lg:px-8 lg:pt-24">
          <div className="text-center">
            <Badge variant="outline" className="mb-6 bg-card gap-1">
              <Sparkles className="size-3.5 text-primary" />
              AI 맞춤 채용 브리핑
            </Badge>
            <h1 className="text-balance text-2xl font-bold leading-[1.15] tracking-tight text-foreground sm:text-4xl lg:text-5xl">
              흩어진 개발자 채용 공고,
              <br />
              <span className="text-primary">매일 아침 AI 브리핑</span>으로
              받아보세요.
            </h1>
            <p className="mx-auto mt-6 max-w-2xl text-pretty text-lg leading-relaxed text-muted-foreground sm:text-xl">
              여러 채용 사이트와 기업 공식 채용 페이지의 공고를 모아,
              관심 직무와 기업 등 내 조건에 맞는 공고만 선별해 이메일로 보내드려요.
            </p>
            <p className="mx-auto mt-3 max-w-sm text-sm text-muted-foreground/80">
              지금은{' '}
              <span className="font-medium text-foreground">개발자·IT 직군 채용 브리핑</span>을
              제공합니다 — 신규 공고·마감 임박·매칭 이유를 매일 아침 한눈에.
            </p>
            <div className="mt-8 flex flex-col items-center gap-3 sm:flex-row sm:justify-center">
              <Link
                href="/onboarding"
                className={cn(
                  buttonVariants({ size: 'lg' }),
                  'h-11 px-6 text-sm inline-flex items-center gap-2',
                )}
              >
                무료로 브리핑 받기
                <ArrowRight className="size-4" />
              </Link>
              <a
                href="#sample"
                className={cn(
                  buttonVariants({ variant: 'outline', size: 'lg' }),
                  'h-11 px-6 text-sm',
                )}
              >
                실제 브리핑 미리보기
              </a>
            </div>
            <p className="mt-4 text-xs text-muted-foreground">
              간편 로그인 · 1분이면 설정 완료 · 이메일 수신 언제든 변경
            </p>
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

      {/* Sample briefing */}
      <section
        id="sample"
        className="mx-auto max-w-6xl px-4 py-20 sm:px-6 lg:px-8"
      >
        <div className="grid items-center gap-8 lg:grid-cols-[1fr_1.4fr]">
          <div>
            <Badge variant="outline" className="mb-4 bg-card">
              브리핑 미리보기
            </Badge>
            <h2 className="text-balance text-3xl font-bold tracking-tight text-foreground sm:text-4xl">
              읽기 좋게 정리된
              <br />
              하루치 채용 브리핑
            </h2>
            <p className="mt-4 text-pretty leading-relaxed text-muted-foreground">
              신규 공고·마감 임박·매칭 이유·추천 액션까지. 
              <br />
              오늘 지원할 공고를 결정하는 데 필요한 정보만 골라 드립니다.
            </p>
            {/* testimonial hidden — not real user data yet */}
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
            <CardContent className="space-y-5 p-6">
              {/* 추천 공고 */}
              <div>
                <p className="text-xs font-semibold text-foreground">🏆 추천 공고</p>
                <div className="mt-2 space-y-3">
                  {(sample.articles ?? []).map((article) => (
                    <div key={article.title} className="rounded-lg border border-border p-3">
                      <p className="text-xs font-medium text-foreground leading-snug">
                        {article.title}
                      </p>
                      <p className="mt-0.5 text-[11px] text-muted-foreground">{article.source}</p>
                      <p className="mt-1.5 text-xs leading-relaxed text-muted-foreground">
                        {article.summary}
                      </p>
                      <div className="mt-2 rounded-md bg-primary/5 px-2.5 py-1.5">
                        <p className="text-[11px] font-medium text-primary">매칭 이유</p>
                        <p className="mt-0.5 text-[11px] text-foreground">{article.whyItMatters}</p>
                      </div>
                    </div>
                  ))}
                </div>
              </div>

              {/* 추천 액션 */}
              {(sample.actions ?? []).length > 0 && (
                <div>
                  <p className="text-xs font-semibold text-foreground">💡 오늘의 추천 액션</p>
                  <ul className="mt-2 space-y-1.5">
                    {(sample.actions ?? []).map((action) => (
                      <li key={action} className="flex items-start gap-2 text-xs text-muted-foreground">
                        <span className="mt-1.5 size-1 shrink-0 rounded-full bg-primary" />
                        {action}
                      </li>
                    ))}
                  </ul>
                </div>
              )}

              <Link
                href="/reports"
                className={cn(buttonVariants({ variant: 'outline', size: 'sm' }), 'w-full')}
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
              <p className="mt-4 max-w-md text-pretty leading-relaxed text-primary-foreground/80">
                현재 베타 운영 중으로, 모든 기능을 무료로 이용하실 수 있습니다.
              </p>
              <ul className="mt-4 space-y-2 text-sm text-primary-foreground/90">
                {['채용 선호도 설정 (직무·기업·스킬)', '매일 아침 1회 발송', '언제든 해지 가능'].map(
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
                간편 로그인으로 1초 만에 시작하세요.
              </p>
              <div className="mt-4 space-y-3">
                <GoogleLoginButton />
                <KakaoLoginButton label="카카오로 시작하기" />
              </div>
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
