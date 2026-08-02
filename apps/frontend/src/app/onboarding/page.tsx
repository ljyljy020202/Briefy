'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import {
  ArrowLeft,
  ArrowRight,
  Check,
  Hash,
  Plus,
  X,
} from 'lucide-react'

import { cn } from '@/lib/utils'
import {
  users,
  briefingCategories as categoriesApi,
  briefingPreferences as prefsApi,
  ApiError,
} from '@/lib/api'
import type { JobPostingPreference } from '@/types/api'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { BriefyLogo } from '@/components/briefy/BriefyLogo'
import { GoogleLoginButton } from '@/components/auth/GoogleLoginButton'
import { KakaoLoginButton } from '@/components/auth/KakaoLoginButton'
import { JOB_KEYWORD_SUGGESTIONS } from '@/lib/mock-data'

type PreferenceKey = keyof Required<JobPostingPreference>

const STEPS = ['계정', '채용 조건 입력', '프로필 설정']

const PREFERENCE_FIELDS: {
  key: PreferenceKey
  label: string
  description: string
}[] = [
  {
    key: 'roles',
    label: '목표 직무',
    description: '지원하려는 직무명을 입력하세요.',
  },
  {
    key: 'companies',
    label: '관심 기업',
    description: '지원을 고려하는 기업명을 자유롭게 입력하세요.',
  },
  {
    key: 'companySizes',
    label: '선호 기업 규모',
    description: '선호하는 회사 규모를 선택하세요.',
  },
  {
    key: 'industries',
    label: '관심 산업',
    description: '관심 있는 산업 분야를 입력하세요.',
  },
  {
    key: 'skills',
    label: '기술/역량',
    description: '보유하거나 원하는 기술 스택을 입력하세요.',
  },
  {
    key: 'locations',
    label: '선호 지역',
    description: '선호하는 근무 지역을 입력하세요.',
  },
  {
    key: 'experienceLevels',
    label: '경력 수준',
    description: '해당하는 경력 조건을 선택하세요.',
  },
  {
    key: 'employmentTypes',
    label: '고용 형태',
    description: '원하는 고용 형태를 선택하세요.',
  },
]

const EMPTY_PREFERENCE: Required<JobPostingPreference> = {
  roles: [],
  companies: [],
  companySizes: [],
  industries: [],
  skills: [],
  locations: [],
  experienceLevels: [],
  employmentTypes: [],
}

const EMPTY_INPUTS: Record<PreferenceKey, string> = {
  roles: '',
  companies: '',
  companySizes: '',
  industries: '',
  skills: '',
  locations: '',
  experienceLevels: '',
  employmentTypes: '',
}

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export default function OnboardingPage() {
  const router = useRouter()
  const [step, setStep] = useState(0)
  const [pageLoading, setPageLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // JOB_POSTING category id — fetched from GET /api/briefing-categories
  const [jobCategoryId, setJobCategoryId] = useState<number | null>(null)

  // Preference fields for JOB_POSTING
  const [preference, setPreference] =
    useState<Required<JobPostingPreference>>(EMPTY_PREFERENCE)

  // Per-field text input
  const [inputByField, setInputByField] =
    useState<Record<PreferenceKey, string>>(EMPTY_INPUTS)

  // Profile fields (Step 2)
  const [nickname, setNickname] = useState('')
  const [reportEmail, setReportEmail] = useState('')

  useEffect(() => {
    Promise.all([users.me(), categoriesApi.getAll()])
      .then(([user, categories]) => {
        if (user.onboardingCompleted) {
          router.replace('/dashboard')
          return
        }
        const jobCategory = categories.find((c) => c.code === 'JOB_POSTING')
        setJobCategoryId(jobCategory?.id ?? null)
        setNickname(user.nickname ?? '')
        setReportEmail(user.email ?? '')
        setStep(1)
        setPageLoading(false)
      })
      .catch((err) => {
        if (err instanceof ApiError && err.code === 'UNAUTHORIZED') {
          setStep(0)
          setPageLoading(false)
        } else {
          setError('페이지를 불러오지 못했습니다. 새로고침해 주세요.')
          setPageLoading(false)
        }
      })
  }, [router])

  const addToField = (key: PreferenceKey, value: string) => {
    const v = value.trim()
    if (!v) return
    const existing = preference[key]
    if (existing.includes(v) || existing.length >= 20) return
    setPreference((prev) => ({ ...prev, [key]: [...prev[key], v] }))
    setInputByField((prev) => ({ ...prev, [key]: '' }))
  }

  const removeFromField = (key: PreferenceKey, value: string) => {
    setPreference((prev) => ({
      ...prev,
      [key]: prev[key].filter((k) => k !== value),
    }))
  }

  const hasAnyPreference = PREFERENCE_FIELDS.some(
    (f) => preference[f.key].length > 0,
  )

  const isEmailValid = EMAIL_RE.test(reportEmail.trim())

  const handleSubmit = async () => {
    if (!isEmailValid) {
      setError('올바른 이메일 주소를 입력해 주세요.')
      return
    }
    setSubmitting(true)
    setError(null)

    try {
      if (jobCategoryId !== null) {
        await prefsApi.upsert({ categoryId: jobCategoryId, preference })
      }
      await users.completeOnboarding(
        nickname.trim() || undefined,
        reportEmail.trim(),
      )
      router.push('/dashboard')
    } catch (err) {
      setError(
        err instanceof Error ? err.message : '오류가 발생했습니다. 다시 시도해 주세요.',
      )
      setSubmitting(false)
    }
  }

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
                  소셜 계정으로 가입하면 설정이 자동으로 저장됩니다.
                </p>
                <div className="mt-6 flex max-w-sm flex-col gap-3">
                  <GoogleLoginButton />
                  <KakaoLoginButton />
                </div>
              </div>
            )}

            {/* Step 1 — JOB_POSTING preference fields */}
            {step === 1 && (
              <div>
                <h1 className="text-2xl font-bold tracking-tight text-foreground">
                  채용 조건을 입력해 주세요
                </h1>
                <p className="mt-2 text-sm text-muted-foreground">
                  입력한 조건을 기반으로 매일 맞춤 채용 브리핑을 전달해 드립니다.
                  건너뛰어도 됩니다.
                </p>

                <div className="mt-6 space-y-5">
                  {PREFERENCE_FIELDS.map(({ key, label, description }) => {
                    const values = preference[key]
                    const inputValue = inputByField[key]
                    const suggestions = JOB_KEYWORD_SUGGESTIONS[key] ?? []
                    const unusedSuggestions = suggestions.filter(
                      (s) => !values.includes(s),
                    )

                    return (
                      <div
                        key={key}
                        className="rounded-xl border border-border p-4"
                      >
                        <p className="text-sm font-medium text-foreground">
                          {label}
                        </p>
                        <p className="mt-0.5 text-xs text-muted-foreground">
                          {description}
                        </p>

                        <form
                          className="mt-3 flex gap-2"
                          onSubmit={(e) => {
                            e.preventDefault()
                            addToField(key, inputValue)
                          }}
                        >
                          <div className="relative flex-1">
                            <Hash className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                            <Input
                              value={inputValue}
                              onChange={(e) =>
                                setInputByField((prev) => ({
                                  ...prev,
                                  [key]: e.target.value,
                                }))
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

                        {values.length > 0 && (
                          <div className="mt-3 flex flex-wrap gap-2">
                            {values.map((v) => (
                              <span
                                key={v}
                                className="inline-flex items-center gap-1.5 rounded-full bg-primary/10 py-1 pl-3 pr-1.5 text-sm font-medium text-primary"
                              >
                                {v}
                                <button
                                  type="button"
                                  aria-label={`${v} 삭제`}
                                  onClick={() => removeFromField(key, v)}
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
                                  onClick={() => addToField(key, s)}
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

                {!hasAnyPreference && (
                  <p className="mt-4 text-xs text-muted-foreground">
                    조건을 입력하지 않아도 계속 진행할 수 있습니다. 나중에
                    선호도 설정에서 수정 가능합니다.
                  </p>
                )}
              </div>
            )}

            {/* Step 2 — profile setup (nickname + report email) */}
            {step === 2 && (
              <div>
                <h1 className="text-2xl font-bold tracking-tight text-foreground">
                  프로필을 완성해 주세요
                </h1>
                <p className="mt-2 text-sm text-muted-foreground">
                  닉네임과 브리핑 리포트를 받을 이메일 주소를 확인해 주세요.
                </p>

                <div className="mt-6 space-y-5">
                  <div className="rounded-xl border border-border p-4">
                    <label
                      htmlFor="nickname"
                      className="text-sm font-medium text-foreground"
                    >
                      닉네임 <span className="text-muted-foreground font-normal">(선택)</span>
                    </label>
                    <p className="mt-0.5 text-xs text-muted-foreground">
                      브리핑 인사말에 사용됩니다.
                    </p>
                    <Input
                      id="nickname"
                      className="mt-3"
                      value={nickname}
                      onChange={(e) => setNickname(e.target.value)}
                      placeholder="닉네임을 입력하세요"
                      maxLength={100}
                    />
                  </div>

                  <div className="rounded-xl border border-border p-4">
                    <label
                      htmlFor="reportEmail"
                      className="text-sm font-medium text-foreground"
                    >
                      리포트 수신 이메일 <span className="text-destructive">*</span>
                    </label>
                    <p className="mt-0.5 text-xs text-muted-foreground">
                      매일 브리핑 리포트가 이 주소로 발송됩니다.
                    </p>
                    <Input
                      id="reportEmail"
                      type="email"
                      className="mt-3"
                      value={reportEmail}
                      onChange={(e) => setReportEmail(e.target.value)}
                      placeholder="example@email.com"
                    />
                  </div>
                </div>

                {hasAnyPreference && (
                  <div className="mt-6 rounded-xl border border-border bg-secondary/40 p-4">
                    <p className="text-sm font-medium text-foreground">
                      설정 요약
                    </p>
                    <div className="mt-3 space-y-2">
                      {PREFERENCE_FIELDS.filter(
                        (f) => preference[f.key].length > 0,
                      ).map(({ key, label }) => (
                        <div key={key} className="flex flex-wrap gap-1.5">
                          <Badge variant="muted">{label}</Badge>
                          {preference[key].map((v) => (
                            <Badge key={v}>{v}</Badge>
                          ))}
                        </div>
                      ))}
                    </div>
                  </div>
                )}
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
                  disabled={step === 0}
                  onClick={() => setStep((s) => s + 1)}
                >
                  다음
                  <ArrowRight className="size-4" />
                </Button>
              ) : (
                <Button
                  size="lg"
                  className="h-10 px-5"
                  disabled={submitting || !isEmailValid}
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
