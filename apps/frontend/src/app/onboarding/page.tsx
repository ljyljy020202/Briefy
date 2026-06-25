'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import {
  ArrowLeft,
  ArrowRight,
  Check,
  Clock,
  Hash,
  Plus,
  X,
} from 'lucide-react'

import { cn } from '@/lib/utils'
import { users, topics as topicsApi, ApiError } from '@/lib/api'
import type { Topic } from '@/types/api'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { BriefyLogo } from '@/components/briefy/BriefyLogo'
import { GoogleLoginButton } from '@/components/auth/GoogleLoginButton'
import { SUGGESTED_KEYWORDS, DELIVERY_TIMES } from '@/lib/mock-data'

const STEPS = ['계정', '주제', '키워드', '발송 시간']

export default function OnboardingPage() {
  const router = useRouter()
  const [step, setStep] = useState(0)
  const [pageLoading, setPageLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Step 1 — topics from API
  const [availableTopics, setAvailableTopics] = useState<Topic[]>([])
  const [selectedTopicIds, setSelectedTopicIds] = useState<Set<number>>(new Set())

  // Step 2 — global keywords
  const [keywords, setKeywords] = useState<string[]>([])
  const [keywordInput, setKeywordInput] = useState('')

  // Step 3 — delivery time (display only; no backend field yet)
  const [deliveryTime, setDeliveryTime] = useState('morning')

  useEffect(() => {
    Promise.all([users.me(), topicsApi.getAll()])
      .then(([user, allTopics]) => {
        if (user.onboardingCompleted) {
          router.replace('/dashboard')
          return
        }
        setAvailableTopics(allTopics)
        // Already authenticated — skip account step
        setStep(1)
        setPageLoading(false)
      })
      .catch((err) => {
        if (err instanceof ApiError && err.code === 'UNAUTHORIZED') {
          // Show account step (Google login)
          setStep(0)
          setPageLoading(false)
        } else {
          setError('페이지를 불러오지 못했습니다. 새로고침해 주세요.')
          setPageLoading(false)
        }
      })
  }, [router])

  const toggleTopic = (id: number) =>
    setSelectedTopicIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  const addKeyword = (value: string) => {
    const v = value.trim()
    if (v && !keywords.includes(v) && keywords.length < 20) {
      setKeywords((prev) => [...prev, v])
    }
    setKeywordInput('')
  }

  const removeKeyword = (value: string) =>
    setKeywords((prev) => prev.filter((k) => k !== value))

  const handleSubmit = async () => {
    if (selectedTopicIds.size === 0) {
      setError('주제를 1개 이상 선택해 주세요.')
      return
    }
    setSubmitting(true)
    setError(null)

    try {
      const bulkTopics = Array.from(selectedTopicIds).map((topicId) => ({
        topicId,
        keywords: keywords.length > 0 ? keywords : ['general'],
      }))

      await topicsApi.subscribeBulk({ topics: bulkTopics })
      await users.completeOnboarding()
      router.push('/dashboard')
    } catch (err) {
      setError(err instanceof Error ? err.message : '오류가 발생했습니다. 다시 시도해 주세요.')
      setSubmitting(false)
    }
  }

  const canContinue =
    step === 0 ||
    (step === 1 && selectedTopicIds.size > 0) ||
    step === 2 ||
    step === 3

  if (pageLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <p className="text-sm text-muted-foreground">불러오는 중…</p>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-background">
      <header className="border-b border-border">
        <div className="mx-auto flex h-16 max-w-3xl items-center justify-between px-4 sm:px-6">
          <Link href="/">
            <BriefyLogo />
          </Link>
          <span className="text-sm text-muted-foreground">
            단계 {step + 1} / {STEPS.length}
          </span>
        </div>
      </header>

      <main className="mx-auto max-w-3xl px-4 py-10 sm:px-6">
        {/* Progress */}
        <div className="mb-10">
          <div className="flex items-center justify-between">
            {STEPS.map((label, i) => (
              <div key={label} className="flex flex-1 items-center last:flex-none">
                <div className="flex flex-col items-center gap-1.5">
                  <span
                    className={cn(
                      'flex size-8 items-center justify-center rounded-full border text-xs font-semibold transition-colors',
                      i < step
                        ? 'border-primary bg-primary text-primary-foreground'
                        : i === step
                          ? 'border-primary bg-card text-primary'
                          : 'border-border bg-card text-muted-foreground',
                    )}
                  >
                    {i < step ? <Check className="size-4" /> : i + 1}
                  </span>
                  <span
                    className={cn(
                      'hidden text-xs sm:block',
                      i <= step ? 'text-foreground' : 'text-muted-foreground',
                    )}
                  >
                    {label}
                  </span>
                </div>
                {i < STEPS.length - 1 && (
                  <span
                    className={cn(
                      'mx-2 h-px flex-1 transition-colors',
                      i < step ? 'bg-primary' : 'bg-border',
                    )}
                  />
                )}
              </div>
            ))}
          </div>
        </div>

        <Card>
          <CardContent className="p-6 sm:p-8">
            {/* Step 0 — account */}
            {step === 0 && (
              <div>
                <h1 className="text-2xl font-bold tracking-tight text-foreground">
                  Briefy 시작하기
                </h1>
                <p className="mt-2 text-sm leading-relaxed text-muted-foreground">
                  Google 계정으로 가입하면 설정이 자동으로 저장됩니다.
                </p>
                <div className="mt-6 max-w-sm">
                  <GoogleLoginButton />
                </div>
              </div>
            )}

            {/* Step 1 — topics */}
            {step === 1 && (
              <div>
                <h1 className="text-2xl font-bold tracking-tight text-foreground">
                  어떤 주제가 궁금하세요?
                </h1>
                <p className="mt-2 text-sm text-muted-foreground">
                  최소 1개 이상 선택하세요. 나중에 언제든 바꿀 수 있습니다.
                </p>
                <div className="mt-6 grid gap-3 sm:grid-cols-2">
                  {availableTopics.map((topic) => {
                    const active = selectedTopicIds.has(topic.id)
                    return (
                      <button
                        key={topic.id}
                        type="button"
                        onClick={() => toggleTopic(topic.id)}
                        className={cn(
                          'flex items-start gap-3 rounded-xl border p-4 text-left transition-colors',
                          active
                            ? 'border-primary bg-accent'
                            : 'border-border bg-card hover:border-primary/40 hover:bg-muted',
                        )}
                      >
                        <span
                          className={cn(
                            'mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-md border transition-colors',
                            active
                              ? 'border-primary bg-primary text-primary-foreground'
                              : 'border-border bg-card',
                          )}
                        >
                          {active && <Check className="size-3.5" />}
                        </span>
                        <span>
                          <span className="block text-sm font-medium text-foreground">
                            {topic.name}
                          </span>
                          <span className="mt-0.5 block text-xs text-muted-foreground">
                            {topic.description}
                          </span>
                        </span>
                      </button>
                    )
                  })}
                </div>
                <p className="mt-4 text-sm text-muted-foreground">
                  {selectedTopicIds.size}개 선택됨
                </p>
              </div>
            )}

            {/* Step 2 — keywords */}
            {step === 2 && (
              <div>
                <h1 className="text-2xl font-bold tracking-tight text-foreground">
                  키워드를 추가하세요
                </h1>
                <p className="mt-2 text-sm text-muted-foreground">
                  특정 기술이나 회사 이름을 넣으면 더 정교하게 큐레이션됩니다.
                </p>

                <form
                  className="mt-6 flex gap-2"
                  onSubmit={(e) => {
                    e.preventDefault()
                    addKeyword(keywordInput)
                  }}
                >
                  <div className="relative flex-1">
                    <Hash className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                    <Input
                      value={keywordInput}
                      onChange={(e) => setKeywordInput(e.target.value)}
                      placeholder="예: Kubernetes, LLM, 금리"
                      className="pl-9"
                    />
                  </div>
                  <Button type="submit" size="lg" className="h-10 px-4">
                    <Plus className="size-4" />
                    추가
                  </Button>
                </form>

                {keywords.length > 0 && (
                  <div className="mt-4 flex flex-wrap gap-2">
                    {keywords.map((k) => (
                      <span
                        key={k}
                        className="inline-flex items-center gap-1.5 rounded-full bg-primary/10 py-1 pl-3 pr-1.5 text-sm font-medium text-primary"
                      >
                        {k}
                        <button
                          type="button"
                          aria-label={`${k} 삭제`}
                          onClick={() => removeKeyword(k)}
                          className="flex size-5 items-center justify-center rounded-full hover:bg-primary/20"
                        >
                          <X className="size-3" />
                        </button>
                      </span>
                    ))}
                  </div>
                )}

                <div className="mt-6">
                  <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                    추천 키워드
                  </p>
                  <div className="mt-2.5 flex flex-wrap gap-2">
                    {SUGGESTED_KEYWORDS.filter(
                      (k) => !keywords.includes(k),
                    ).map((k) => (
                      <button
                        key={k}
                        type="button"
                        onClick={() => addKeyword(k)}
                        className="inline-flex items-center gap-1 rounded-full border border-border bg-card px-3 py-1 text-sm text-muted-foreground transition-colors hover:border-primary/40 hover:text-foreground"
                      >
                        <Plus className="size-3" />
                        {k}
                      </button>
                    ))}
                  </div>
                </div>
              </div>
            )}

            {/* Step 3 — delivery time */}
            {step === 3 && (
              <div>
                <h1 className="text-2xl font-bold tracking-tight text-foreground">
                  언제 받아볼까요?
                </h1>
                <p className="mt-2 text-sm text-muted-foreground">
                  매일 이 시간에 맞춰 브리핑이 메일로 도착합니다.
                </p>
                <div className="mt-6 grid gap-3 sm:grid-cols-2">
                  {DELIVERY_TIMES.map((t) => {
                    const active = deliveryTime === t.id
                    return (
                      <button
                        key={t.id}
                        type="button"
                        onClick={() => setDeliveryTime(t.id)}
                        className={cn(
                          'flex items-center gap-3 rounded-xl border p-4 text-left transition-colors',
                          active
                            ? 'border-primary bg-accent'
                            : 'border-border bg-card hover:border-primary/40 hover:bg-muted',
                        )}
                      >
                        <span
                          className={cn(
                            'flex size-10 items-center justify-center rounded-lg',
                            active
                              ? 'bg-primary text-primary-foreground'
                              : 'bg-muted text-muted-foreground',
                          )}
                        >
                          <Clock className="size-5" />
                        </span>
                        <span>
                          <span className="block text-sm font-semibold text-foreground">
                            {t.label}
                          </span>
                          <span className="text-xs text-muted-foreground">
                            {t.hint}
                          </span>
                        </span>
                      </button>
                    )
                  })}
                </div>

                <div className="mt-6 rounded-xl border border-border bg-secondary/40 p-4">
                  <p className="text-sm font-medium text-foreground">설정 요약</p>
                  <div className="mt-3 flex flex-wrap gap-1.5">
                    {availableTopics
                      .filter((t) => selectedTopicIds.has(t.id))
                      .map((t) => (
                        <Badge key={t.id} variant="muted">
                          {t.name}
                        </Badge>
                      ))}
                    {keywords.map((k) => (
                      <Badge key={k}>{k}</Badge>
                    ))}
                  </div>
                </div>
              </div>
            )}

            {error && (
              <p className="mt-4 text-sm text-destructive">{error}</p>
            )}

            {/* Footer controls */}
            <div className="mt-8 flex items-center justify-between gap-3">
              {step > 1 ? (
                <Button
                  variant="ghost"
                  size="lg"
                  className="h-10"
                  onClick={() => setStep((s) => s - 1)}
                >
                  <ArrowLeft className="size-4" />
                  이전
                </Button>
              ) : (
                <span />
              )}

              {step < STEPS.length - 1 ? (
                <Button
                  size="lg"
                  className="h-10 px-5"
                  disabled={!canContinue || step === 0}
                  onClick={() => setStep((s) => s + 1)}
                >
                  다음
                  <ArrowRight className="size-4" />
                </Button>
              ) : (
                <Button
                  size="lg"
                  className="h-10 px-5"
                  disabled={submitting || selectedTopicIds.size === 0}
                  onClick={handleSubmit}
                >
                  {submitting ? '설정 중…' : '브리핑 시작하기'}
                </Button>
              )}
            </div>
          </CardContent>
        </Card>
      </main>
    </div>
  )
}
