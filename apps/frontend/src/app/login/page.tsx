'use client'

import { Suspense, useEffect, useState } from 'react'
import Link from 'next/link'
import { useRouter, useSearchParams } from 'next/navigation'

import { users, ApiError } from '@/lib/api'
import { Card, CardContent } from '@/components/ui/card'
import { BriefyLogo } from '@/components/briefy/BriefyLogo'
import { GoogleLoginButton } from '@/components/auth/GoogleLoginButton'
import { KakaoLoginButton } from '@/components/auth/KakaoLoginButton'

const ERROR_MESSAGES: Record<string, string> = {
  oauth_cancelled: '로그인을 취소했습니다. 다시 시도해 주세요.',
  oauth_state_invalid: '보안 검증에 실패했습니다. 다시 시도해 주세요.',
  kakao_email_required:
    '카카오 계정에 이메일 정보가 없습니다. 카카오 계정 설정에서 이메일을 동의해 주세요.',
  kakao_email_invalid: '카카오 계정의 이메일이 유효하지 않거나 인증되지 않았습니다.',
  oauth_provider_conflict: '이미 다른 소셜 계정으로 가입된 이메일입니다.',
  oauth_failed: '로그인 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
}

function getErrorMessage(error: string | null): string | null {
  if (!error) return null
  const baseCode = error.split('&')[0]
  return ERROR_MESSAGES[baseCode] ?? ERROR_MESSAGES['oauth_failed']
}

function ErrorBanner() {
  const searchParams = useSearchParams()
  const errorParam = searchParams.get('error')
  const message = getErrorMessage(errorParam)

  if (!message) return null

  return (
    <div
      role="alert"
      className="mt-4 rounded-lg border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive"
    >
      {message}
    </div>
  )
}

function LoginContent() {
  const router = useRouter()
  const [pageLoading, setPageLoading] = useState(true)

  useEffect(() => {
    users
      .me()
      .then((user) => {
        if (user.onboardingCompleted) {
          router.replace('/dashboard')
        } else {
          router.replace('/onboarding')
        }
      })
      .catch((err) => {
        if (err instanceof ApiError && err.code === 'UNAUTHORIZED') {
          setPageLoading(false)
        } else {
          setPageLoading(false)
        }
      })
  }, [router])

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
        <div className="mx-auto flex h-16 max-w-3xl items-center px-4 sm:px-6">
          <Link href="/">
            <BriefyLogo />
          </Link>
        </div>
      </header>

      <main className="mx-auto max-w-3xl px-4 py-10 sm:px-6">
        <Card>
          <CardContent className="p-6 sm:p-8">
            <h1 className="text-2xl font-bold tracking-tight text-foreground">로그인</h1>
            <p className="mt-2 text-sm leading-relaxed text-muted-foreground">
              소셜 계정으로 간편하게 로그인하세요.
            </p>

            <Suspense>
              <ErrorBanner />
            </Suspense>

            <div className="mt-6 flex max-w-sm flex-col gap-3">
              <GoogleLoginButton label="Google 로그인" />
              <KakaoLoginButton label="카카오 로그인" />
            </div>

            <p className="mt-6 text-center text-sm text-muted-foreground">
              아직 계정이 없으신가요?{' '}
              <Link
                href="/onboarding"
                className="font-medium text-foreground underline-offset-4 hover:underline"
              >
                무료로 시작하기
              </Link>
            </p>
          </CardContent>
        </Card>
      </main>
    </div>
  )
}

export default function LoginPage() {
  return (
    <Suspense
      fallback={
        <div className="min-h-screen flex items-center justify-center">
          <p className="text-sm text-muted-foreground">불러오는 중…</p>
        </div>
      }
    >
      <LoginContent />
    </Suspense>
  )
}
