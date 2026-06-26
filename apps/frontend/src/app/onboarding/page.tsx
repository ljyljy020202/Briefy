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
import { JOB_KEYWORD_SUGGESTIONS, DELIVERY_TIMES } from '@/lib/mock-data'

const STEPS = ['계정', '선호도 항목', '세부 값', '발송 시간']

export default function OnboardingPage() {
  const router = useRouter()
  const [step, setStep] = useState(0)
  const [pageLoading, setPageLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Step 1 — topics from API
  const [availableTopics, setAvailableTopics] = useState<Topic[]>([])
  const [selectedTopicIds, setSelectedTopicIds] = useState<Set<number>>(new Set())

  // Step 2 — per-topic keywords
  const [keywordsByTopic, setKeywordsByTopic] = useState<Map<number, string[]>>(new Map())
  const [keywordInputByTopic, setKeywordInputByTopic] = useState<Map<number, string>>(new Map())

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

  const addKeywordForTopic = (topicId: number, value: string) => {
    const v = value.trim()
    if (!v) return
    setKeywordsByTopic((prev) => {
      const next = new Map(prev)
      const existing = next.get(topicId) ?? []
      if (!existing.includes(v) && existing.length < 20) {
        next.set(topicId, [...existing, v])
      }
      return next
    })
    setKeywordInputByTopic((prev) => {
      const next = new Map(prev)
      next.set(topicId, '')
      return next
    })
  }

  const removeKeywordForTopic = (topicId: number, value: string) => {
    setKeywordsByTopic((prev) => {
      const next = new Map(prev)
      next.set(topicId, (next.get(topicId) ?? []).filter((k) => k !== value))
      return next
    })
  }

  const handleSubmit = async () => {
    if (selectedTopicIds.size === 0) {
      setError('항목을 1개 이상 선택해 주세요.')
      return
    }
    setSubmitting(true)
    setError(null)

    try {
      const bulkTopics = Array.from(selectedTopicIds).map((topicId) => ({
        topicId,
        keywords: (keywordsByTopic.get(topicId) ?? []).length > 0
          ? keywordsByTopic.get(topicId)!
          : ['general'],
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

            {/* Step 1 — preference categories */}
            {step === 1 && (
              <div>
                <h1 className="text-2xl font-bold tracking-tight text-foreground">
                  채용 선호도 항목을 선택하세요
                </h1>
                <p className="mt-2 text-sm text-muted-foreground">
                  관리하고 싶은 항목을 고르세요. 다음 단계에서 세부 값을 입력합니다.
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

            {/* Step 2 — per-topic keyword input */}
            {step === 2 && (
              <div>
                <h1 className="text-2xl font-bold tracking-tight text-foreground">
                  선호도를 입력해 주세요
                </h1>
                <p className="mt-2 text-sm text-muted-foreground">
                  각 항목에 해당하는 값을 입력하면 브리핑이 더 정확해집니다.
                  건너뛰어도 됩니다.
                </p>

                <div className="mt-6 space-y-5">
                  {availableTopics
                    .filter((t) => selectedTopicIds.has(t.id))
                    .map((topic) => {
                      const topicKeywords = keywordsByTopic.get(topic.id) ?? []
                      const topicInput = keywordInputByTopic.get(topic.id) ?? ''
                      const suggestions = JOB_KEYWORD_SUGGESTIONS[topic.name] ?? []
                      const unusedSuggestions = suggestions.filter(
                        (s) => !topicKeywords.includes(s),
                      )

                      return (
                        <div
                          key={topic.id}
                          className="rounded-xl border border-border p-4"
                        >
                          <p className="text-sm font-medium text-foreground">
                            {topic.name}
                          </p>
                          <p className="mt-0.5 text-xs text-muted-foreground">
                            {topic.description}
                          </p>

                          <form
                            className="mt-3 flex gap-2"
                            onSubmit={(e) => {
                              e.preventDefault()
                              addKeywordForTopic(topic.id, topicInput)
                            }}
                          >
                            <div className="relative flex-1">
                              <Hash className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                              <Input
                                value={topicInput}
                                onChange={(e) =>
                                  setKeywordInputByTopic((prev) => {
                                    const next = new Map(prev)
                                    next.set(topic.id, e.target.value)
                                    return next
                                  })
                                }
                                placeholder={
                                  suggestions[0]
                                    ? `예: ${suggestions[0]}`
                                    : '직접 입력…'
                                }
                                className="pl-9"
                              />
                            </div>
                            <Button
                              type="submit"
                              size="sm"
                              className="h-10 px-3"
                            >
                              <Plus className="size-4" />
                              추가
                            </Button>
                          </form>

                          {topicKeywords.length > 0 && (
                            <div className="mt-3 flex flex-wrap gap-2">
                              {topicKeywords.map((k) => (
                                <span
                                  key={k}
                                  className="inline-flex items-center gap-1.5 rounded-full bg-primary/10 py-1 pl-3 pr-1.5 text-sm font-medium text-primary"
                                >
                                  {k}
                                  <button
                                    type="button"
                                    aria-label={`${k} 삭제`}
                                    onClick={() =>
                                      removeKeywordForTopic(topic.id, k)
                                    }
                                    className="flex size-5 items-center justify-center rounded-full hover:bg-primary/20"
                                  >
                                    <X className="size-3" />
                                  </button>
                                </span>
                              ))}
                            </div>
                          )}

                          {unusedSuggestions.length > 0 && (
                            <div className="mt-3">
                              <p className="text-xs text-muted-foreground">
                                추천
                              </p>
                              <div className="mt-1.5 flex flex-wrap gap-1.5">
                                {unusedSuggestions.slice(0, 6).map((s) => (
                                  <button
                                    key={s}
                                    type="button"
                                    onClick={() =>
                                      addKeywordForTopic(topic.id, s)
                                    }
                                    className="inline-flex items-center gap-1 rounded-full border border-border bg-card px-2.5 py-0.5 text-xs text-muted-foreground transition-colors hover:border-primary/40 hover:text-foreground"
                                  >
                                    <Plus className="size-2.5" />
                                    {s}
                                  </button>
                                ))}
                              </div>
                            </div>
                          )}
                        </div>
                      )
                    })}
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
                  <div className="mt-3 space-y-2">
                    {availableTopics
                      .filter((t) => selectedTopicIds.has(t.id))
                      .map((t) => {
                        const kws = keywordsByTopic.get(t.id) ?? []
                        return (
                          <div key={t.id} className="flex flex-wrap gap-1.5">
                            <Badge variant="muted">{t.name}</Badge>
                            {kws.map((k) => (
                              <Badge key={k}>{k}</Badge>
                            ))}
                          </div>
                        )
                      })}
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
