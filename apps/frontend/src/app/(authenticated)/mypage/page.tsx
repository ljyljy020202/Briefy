'use client'

import { useEffect, useState } from 'react'
import { useRouter } from 'next/navigation'
import { Hash, LogOut, Pencil, Plus, Trash2, X } from 'lucide-react'

import { cn } from '@/lib/utils'
import {
  auth,
  users,
  briefingCategories as categoriesApi,
  briefingPreferences as prefsApi,
  ApiError,
} from '@/lib/api'
import type { BriefingPreference, JobPostingPreference } from '@/types/api'
import { useAuthContext } from '@/contexts/AuthContext'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { Switch } from '@/components/ui/switch'
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogHeader,
  AlertDialogFooter,
  AlertDialogTitle,
  AlertDialogDescription,
  AlertDialogCancel,
} from '@/components/ui/alert-dialog'
import { JOB_KEYWORD_SUGGESTIONS } from '@/lib/mock-data'

type PreferenceKey = keyof Required<JobPostingPreference>

const PREFERENCE_FIELDS: { key: PreferenceKey; label: string; description: string }[] = [
  { key: 'roles', label: '목표 직무', description: '지원하려는 직무명을 입력하세요.' },
  { key: 'companies', label: '관심 기업', description: '지원을 고려하는 기업명을 자유롭게 입력하세요.' },
  { key: 'companySizes', label: '선호 기업 규모', description: '선호하는 회사 규모를 선택하세요.' },
  { key: 'industries', label: '관심 산업', description: '관심 있는 산업 분야를 입력하세요.' },
  { key: 'skills', label: '기술/역량', description: '보유하거나 원하는 기술 스택을 입력하세요.' },
  { key: 'locations', label: '선호 지역', description: '선호하는 근무 지역을 입력하세요.' },
  { key: 'experienceLevels', label: '경력 수준', description: '해당하는 경력 조건을 선택하세요.' },
  { key: 'employmentTypes', label: '고용 형태', description: '원하는 고용 형태를 선택하세요.' },
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

export default function MyPage() {
  const router = useRouter()
  const { user, refetch: refetchUser } = useAuthContext()

  // ── Loading / error ──────────────────────────────────────────
  const [pageLoading, setPageLoading] = useState(true)
  const [pageError, setPageError] = useState<string | null>(null)

  // ── User info edit ───────────────────────────────────────────
  const [editingInfo, setEditingInfo] = useState(false)
  const [nickname, setNickname] = useState('')
  const [reportEmail, setReportEmail] = useState('')
  const [infoSaving, setInfoSaving] = useState(false)
  const [infoError, setInfoError] = useState<string | null>(null)

  // ── Briefing email subscription ──────────────────────────────
  const [subscriptionDialogOpen, setSubscriptionDialogOpen] = useState(false)
  const [subscriptionPending, setSubscriptionPending] = useState(false)
  const [subscriptionError, setSubscriptionError] = useState<string | null>(null)
  const [subscriptionSuccess, setSubscriptionSuccess] = useState<string | null>(null)
  // target = the value the user wants to switch TO
  const [subscriptionTarget, setSubscriptionTarget] = useState<boolean>(true)

  // ── Logout / delete account ──────────────────────────────────
  const [loggingOut, setLoggingOut] = useState(false)
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false)
  const [deleting, setDeleting] = useState(false)

  // ── Briefing preference ──────────────────────────────────────
  const [existingPref, setExistingPref] = useState<BriefingPreference | null>(null)
  const [jobCategoryId, setJobCategoryId] = useState<number | null>(null)
  const [prefMode, setPrefMode] = useState<'view' | 'form'>('view')
  const [formPref, setFormPref] = useState<Required<JobPostingPreference>>(EMPTY_PREFERENCE)
  const [inputByField, setInputByField] = useState<Record<PreferenceKey, string>>(EMPTY_INPUTS)
  const [prefSaving, setPrefSaving] = useState(false)
  const [prefError, setPrefError] = useState<string | null>(null)
  const [showPrefDeleteConfirm, setShowPrefDeleteConfirm] = useState(false)
  const [prefDeleting, setPrefDeleting] = useState(false)

  // ── Initial load ─────────────────────────────────────────────
  useEffect(() => {
    Promise.all([prefsApi.getMine(), categoriesApi.getAll()])
      .then(([prefs, categories]) => {
        const jobPref = prefs.find((p) => p.categoryCode === 'JOB_POSTING') ?? prefs[0] ?? null
        setExistingPref(jobPref)

        const jobCategory = categories.find((c) => c.code === 'JOB_POSTING')
        setJobCategoryId(jobCategory?.id ?? null)

        setPageLoading(false)
      })
      .catch((err) => {
        if (err instanceof ApiError && err.code === 'UNAUTHORIZED') {
          router.replace('/')
        } else {
          setPageError('데이터를 불러오지 못했습니다. 새로고침해 주세요.')
          setPageLoading(false)
        }
      })
  }, [router])

  // Seed form with existing preference when entering edit mode
  function enterFormMode() {
    setFormPref(
      existingPref
        ? {
            roles: existingPref.preference.roles ?? [],
            companies: existingPref.preference.companies ?? [],
            companySizes: existingPref.preference.companySizes ?? [],
            industries: existingPref.preference.industries ?? [],
            skills: existingPref.preference.skills ?? [],
            locations: existingPref.preference.locations ?? [],
            experienceLevels: existingPref.preference.experienceLevels ?? [],
            employmentTypes: existingPref.preference.employmentTypes ?? [],
          }
        : EMPTY_PREFERENCE,
    )
    setInputByField(EMPTY_INPUTS)
    setPrefError(null)
    setPrefMode('form')
  }

  // ── User info handlers ───────────────────────────────────────
  function startEditInfo() {
    setNickname(user?.nickname ?? '')
    setReportEmail(user?.reportEmail ?? user?.email ?? '')
    setInfoError(null)
    setEditingInfo(true)
  }

  async function saveInfo() {
    if (!EMAIL_RE.test(reportEmail.trim())) {
      setInfoError('올바른 이메일 주소를 입력해 주세요.')
      return
    }
    setInfoSaving(true)
    setInfoError(null)
    try {
      await users.completeOnboarding(nickname.trim() || undefined, reportEmail.trim())
      await refetchUser()
      setEditingInfo(false)
    } catch {
      setInfoError('저장 중 오류가 발생했습니다. 다시 시도해 주세요.')
    } finally {
      setInfoSaving(false)
    }
  }

  function openSubscriptionDialog(targetEnabled: boolean) {
    setSubscriptionTarget(targetEnabled)
    setSubscriptionError(null)
    setSubscriptionSuccess(null)
    setSubscriptionDialogOpen(true)
  }

  async function confirmSubscriptionChange() {
    setSubscriptionPending(true)
    setSubscriptionError(null)
    try {
      await users.updateBriefingEmailSubscription(subscriptionTarget)
      await refetchUser()
      setSubscriptionSuccess(
        subscriptionTarget
          ? '이메일 브리핑 수신이 시작되었습니다.'
          : '이메일 브리핑 수신이 중지되었습니다.',
      )
      setSubscriptionDialogOpen(false)
    } catch {
      setSubscriptionError('수신 설정을 변경하지 못했습니다. 잠시 후 다시 시도해 주세요.')
    } finally {
      setSubscriptionPending(false)
    }
  }

  async function handleLogout() {
    setLoggingOut(true)
    try {
      await auth.logout()
    } finally {
      router.replace('/')
    }
  }

  async function handleDeleteAccount() {
    setDeleting(true)
    try {
      await users.deleteAccount()
    } finally {
      router.replace('/')
    }
  }

  // ── Preference form handlers ─────────────────────────────────
  function addToField(key: PreferenceKey, value: string) {
    const v = value.trim()
    if (!v) return
    const existing = formPref[key]
    if (existing.includes(v) || existing.length >= 20) return
    setFormPref((prev) => ({ ...prev, [key]: [...prev[key], v] }))
    setInputByField((prev) => ({ ...prev, [key]: '' }))
  }

  function removeFromField(key: PreferenceKey, value: string) {
    setFormPref((prev) => ({ ...prev, [key]: prev[key].filter((k) => k !== value) }))
  }

  async function savePref() {
    if (jobCategoryId === null) {
      setPrefError('카테고리 정보를 불러오지 못했습니다. 새로고침해 주세요.')
      return
    }
    setPrefSaving(true)
    setPrefError(null)
    try {
      const saved = await prefsApi.upsert({ categoryId: jobCategoryId, preference: formPref })
      setExistingPref(saved)
      setPrefMode('view')
    } catch {
      setPrefError('저장 중 오류가 발생했습니다. 다시 시도해 주세요.')
    } finally {
      setPrefSaving(false)
    }
  }

  async function deletePref() {
    if (!existingPref) return
    setPrefDeleting(true)
    try {
      await prefsApi.delete(existingPref.id)
      setExistingPref(null)
      setShowPrefDeleteConfirm(false)
      setPrefMode('view')
    } catch {
      setPrefError('삭제 중 오류가 발생했습니다. 다시 시도해 주세요.')
    } finally {
      setPrefDeleting(false)
    }
  }

  // ── Render ───────────────────────────────────────────────────
  if (pageLoading) {
    return (
      <div className="space-y-6 animate-pulse">
        <div className="h-8 w-32 rounded-lg bg-muted" />
        <div className="h-48 rounded-lg bg-muted" />
        <div className="h-64 rounded-lg bg-muted" />
      </div>
    )
  }

  if (pageError) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <p className="text-sm text-destructive">{pageError}</p>
        <Button variant="outline" className="mt-4" onClick={() => window.location.reload()}>
          새로고침
        </Button>
      </div>
    )
  }

  const displayName = user?.nickname ?? user?.email?.split('@')[0] ?? '사용자'
  const initial = displayName.slice(0, 1).toUpperCase()
  const filledPrefFields = existingPref
    ? PREFERENCE_FIELDS.filter((f) => (existingPref.preference[f.key]?.length ?? 0) > 0)
    : []

  return (
    <div className="space-y-8">
      <h1 className="text-2xl font-bold tracking-tight text-foreground">마이페이지</h1>

      {/* ── 계정 정보 ─────────────────────────────────────────── */}
      <Card>
        <CardContent className="p-6 sm:p-8">
          <div className="flex items-center justify-between">
            <h2 className="text-base font-semibold text-foreground">계정 정보</h2>
            {!editingInfo && (
              <Button variant="ghost" size="sm" onClick={startEditInfo}>
                <Pencil className="size-3.5" />
                수정하기
              </Button>
            )}
          </div>

          {/* Avatar + email */}
          <div className="mt-5 flex items-center gap-4">
            <span className="flex size-14 shrink-0 items-center justify-center rounded-full bg-primary/10 text-xl font-bold text-primary">
              {initial}
            </span>
            <div>
              <p className="text-sm font-medium text-foreground">{displayName}</p>
              <p className="text-xs text-muted-foreground">{user?.email}</p>
            </div>
          </div>

          {!editingInfo ? (
            /* 읽기 모드 */
            <div className="mt-6 space-y-3">
              <div className="flex items-center justify-between rounded-xl border border-border px-4 py-3">
                <span className="text-xs text-muted-foreground">닉네임</span>
                <span className="text-sm font-medium text-foreground">
                  {user?.nickname || <span className="text-muted-foreground">미설정</span>}
                </span>
              </div>
              <div className="flex items-center justify-between rounded-xl border border-border px-4 py-3">
                <span className="text-xs text-muted-foreground">리포트 수신 이메일</span>
                <span className="text-sm font-medium text-foreground">
                  {user?.reportEmail || <span className="text-muted-foreground">미설정</span>}
                </span>
              </div>
            </div>
          ) : (
            /* 수정 모드 */
            <div className="mt-6 space-y-4">
              <div>
                <label htmlFor="mp-nickname" className="text-sm font-medium text-foreground">
                  닉네임 <span className="font-normal text-muted-foreground">(선택)</span>
                </label>
                <Input
                  id="mp-nickname"
                  className="mt-2"
                  value={nickname}
                  onChange={(e) => setNickname(e.target.value)}
                  placeholder="닉네임을 입력하세요"
                  maxLength={100}
                />
              </div>
              <div>
                <label htmlFor="mp-report-email" className="text-sm font-medium text-foreground">
                  리포트 수신 이메일 <span className="text-destructive">*</span>
                </label>
                <Input
                  id="mp-report-email"
                  type="email"
                  className="mt-2"
                  value={reportEmail}
                  onChange={(e) => setReportEmail(e.target.value)}
                  placeholder="example@email.com"
                />
              </div>
              {infoError && <p className="text-sm text-destructive">{infoError}</p>}
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setEditingInfo(false)}
                  disabled={infoSaving}
                >
                  취소
                </Button>
                <Button size="sm" onClick={saveInfo} disabled={infoSaving}>
                  {infoSaving ? '저장 중…' : '저장하기'}
                </Button>
              </div>
            </div>
          )}

          {/* 로그아웃 / 회원탈퇴 */}
          <div className="mt-8 flex flex-col gap-3 border-t border-border pt-6">
            <Button
              variant="outline"
              className="w-full justify-start gap-2"
              onClick={handleLogout}
              disabled={loggingOut}
            >
              <LogOut className="size-4" />
              {loggingOut ? '로그아웃 중…' : '로그아웃'}
            </Button>

            {!showDeleteConfirm ? (
              <button
                onClick={() => setShowDeleteConfirm(true)}
                className="w-full text-left text-xs text-muted-foreground transition-colors hover:text-destructive"
              >
                회원탈퇴
              </button>
            ) : (
              <div className="rounded-xl border border-destructive/30 bg-destructive/5 p-4 text-sm">
                <p className="font-medium text-foreground">정말 탈퇴하시겠어요?</p>
                <p className="mt-1 text-xs text-muted-foreground">
                  모든 데이터가 삭제되며 복구할 수 없습니다.
                </p>
                <div className="mt-4 flex gap-2">
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setShowDeleteConfirm(false)}
                    disabled={deleting}
                  >
                    취소
                  </Button>
                  <Button
                    variant="destructive"
                    size="sm"
                    onClick={handleDeleteAccount}
                    disabled={deleting}
                  >
                    {deleting ? '처리 중…' : '탈퇴하기'}
                  </Button>
                </div>
              </div>
            )}
          </div>
        </CardContent>
      </Card>

      {/* ── 이메일 브리핑 수신 설정 ───────────────────────────────── */}
      <section id="briefing-email-settings">
        <Card>
          <CardContent className="p-6 sm:p-8">
            <h2 className="text-base font-semibold text-foreground">이메일 브리핑 수신 설정/해제</h2>

            <div className="mt-5 flex items-center justify-between gap-4">
              <div>
                {user?.briefingEmailEnabled ? (
                  <p className="text-sm text-muted-foreground">
                    매일 오전 8시에 설정한 조건을 기준으로 맞춤 브리핑을 보내드려요.
                  </p>
                ) : (
                  <p className="text-sm text-muted-foreground">
                    현재 이메일 브리핑 수신이 중지되어 있어요.
                  </p>
                )}
              </div>
              <Switch
                aria-label="이메일 브리핑 수신 설정"
                checked={user?.briefingEmailEnabled ?? true}
                onCheckedChange={(next) => openSubscriptionDialog(next)}
                disabled={subscriptionPending}
              />
            </div>

            {subscriptionSuccess && (
              <p className="mt-3 text-sm text-primary">{subscriptionSuccess}</p>
            )}
            {subscriptionError && (
              <p className="mt-3 text-sm text-destructive">{subscriptionError}</p>
            )}
          </CardContent>
        </Card>
      </section>

      {/* ── 수신 설정 확인 모달 ────────────────────────────────────── */}
      <AlertDialog open={subscriptionDialogOpen} onOpenChange={setSubscriptionDialogOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {subscriptionTarget
                ? '이메일 브리핑 수신을 다시 시작할까요?'
                : '이메일 브리핑 수신을 중지할까요?'}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {subscriptionTarget
                ? '다음 발송 일정부터 설정한 조건을 기준으로 맞춤 브리핑을 생성해 이메일로 보내드려요.'
                : '수신을 중지하면 다음 브리핑부터 자동 생성과 이메일 발송이 중단됩니다.\n기존 브리핑은 계속 확인할 수 있으며, 언제든 다시 수신할 수 있어요.'}
            </AlertDialogDescription>
          </AlertDialogHeader>
          {subscriptionError && (
            <p className="mt-3 text-sm text-destructive">{subscriptionError}</p>
          )}
          <AlertDialogFooter>
            <AlertDialogCancel disabled={subscriptionPending}>취소</AlertDialogCancel>
            <button
              className={cn(
                'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-md px-4 py-2 text-sm font-medium transition-colors',
                'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                'disabled:pointer-events-none disabled:opacity-50',
                subscriptionTarget
                  ? 'bg-primary text-primary-foreground hover:bg-primary/90'
                  : 'bg-destructive text-destructive-foreground hover:bg-destructive/90',
              )}
              disabled={subscriptionPending}
              onClick={confirmSubscriptionChange}
            >
              {subscriptionPending
                ? '처리 중…'
                : subscriptionTarget
                  ? '수신 시작'
                  : '수신 중지'}
            </button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* ── 브리핑 선호도 ──────────────────────────────────────── */}
      <Card>
        <CardContent className="p-6 sm:p-8">
          <div className="flex items-center justify-between">
            <h2 className="text-base font-semibold text-foreground">브리핑 선호도</h2>
            {existingPref && prefMode === 'view' && (
              <div className="flex gap-2">
                <Button variant="ghost" size="sm" onClick={enterFormMode}>
                  <Pencil className="size-3.5" />
                  수정하기
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  className="text-destructive hover:text-destructive"
                  onClick={() => {
                    setShowPrefDeleteConfirm(true)
                    setPrefError(null)
                  }}
                >
                  <Trash2 className="size-3.5" />
                  삭제하기
                </Button>
              </div>
            )}
          </div>

          {/* 선호도 없음 — 안내 + 생성하기 */}
          {!existingPref && prefMode === 'view' && (
            <div className="mt-6 flex flex-col items-center gap-4 py-8 text-center">
              <p className="text-sm text-muted-foreground">
                브리핑 선호 조건을 설정하지 않았습니다.
              </p>
              <Button size="sm" onClick={enterFormMode}>
                <Plus className="size-4" />
                생성하기
              </Button>
            </div>
          )}

          {/* 선호도 있음 — 읽기 모드 */}
          {existingPref && prefMode === 'view' && (
            <div className="mt-5 space-y-4">
              {filledPrefFields.length === 0 ? (
                <p className="text-sm text-muted-foreground">설정된 조건이 없습니다.</p>
              ) : (
                filledPrefFields.map(({ key, label }) => (
                  <div key={key}>
                    <p className="mb-2 text-xs font-medium text-muted-foreground">{label}</p>
                    <div className="flex flex-wrap gap-1.5">
                      {(existingPref.preference[key] ?? []).map((v) => (
                        <Badge key={v}>{v}</Badge>
                      ))}
                    </div>
                  </div>
                ))
              )}

              {/* 삭제 확인 */}
              {showPrefDeleteConfirm && (
                <div className="rounded-xl border border-destructive/30 bg-destructive/5 p-4 text-sm">
                  <p className="font-medium text-foreground">선호도를 삭제하시겠어요?</p>
                  <p className="mt-1 text-xs text-muted-foreground">
                    삭제 후에는 브리핑 조건이 초기화됩니다.
                  </p>
                  {prefError && <p className="mt-2 text-xs text-destructive">{prefError}</p>}
                  <div className="mt-4 flex gap-2">
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => {
                        setShowPrefDeleteConfirm(false)
                        setPrefError(null)
                      }}
                      disabled={prefDeleting}
                    >
                      취소
                    </Button>
                    <Button
                      variant="destructive"
                      size="sm"
                      onClick={deletePref}
                      disabled={prefDeleting}
                    >
                      {prefDeleting ? '삭제 중…' : '삭제하기'}
                    </Button>
                  </div>
                </div>
              )}
            </div>
          )}

          {/* 폼 모드 — 생성 / 수정 */}
          {prefMode === 'form' && (
            <div className="mt-6 space-y-5">
              {PREFERENCE_FIELDS.map(({ key, label, description }) => {
                const values = formPref[key]
                const inputValue = inputByField[key]
                const suggestions = JOB_KEYWORD_SUGGESTIONS[key] ?? []
                const unusedSuggestions = suggestions.filter((s) => !values.includes(s))

                return (
                  <div key={key} className="rounded-xl border border-border p-4">
                    <p className="text-sm font-medium text-foreground">{label}</p>
                    <p className="mt-0.5 text-xs text-muted-foreground">{description}</p>

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
                            setInputByField((prev) => ({ ...prev, [key]: e.target.value }))
                          }
                          placeholder={suggestions[0] ? `예: ${suggestions[0]}` : '직접 입력…'}
                          className="pl-9"
                        />
                      </div>
                      <Button type="submit" size="sm" className="h-10 px-3">
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
                        <p className="text-xs text-muted-foreground">추천</p>
                        <div className="mt-1.5 flex flex-wrap gap-1.5">
                          {unusedSuggestions.slice(0, 6).map((s) => (
                            <button
                              key={s}
                              type="button"
                              onClick={() => addToField(key, s)}
                              className={cn(
                                'inline-flex items-center gap-1 rounded-full border border-border bg-card px-2.5 py-0.5 text-xs text-muted-foreground',
                                'transition-colors hover:border-primary/40 hover:text-foreground',
                              )}
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

              {prefError && <p className="text-sm text-destructive">{prefError}</p>}

              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => {
                    setPrefMode('view')
                    setPrefError(null)
                  }}
                  disabled={prefSaving}
                >
                  취소
                </Button>
                <Button size="sm" onClick={savePref} disabled={prefSaving}>
                  {prefSaving ? '저장 중…' : '저장하기'}
                </Button>
              </div>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
