'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'

import { users, ApiError } from '@/lib/api'
import { Card, CardContent } from '@/components/ui/card'
import { BriefyLogo } from '@/components/briefy/BriefyLogo'
import { GoogleLoginButton } from '@/components/auth/GoogleLoginButton'

export default function LoginPage() {
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

      <main className="mx-auto flex max-w-sm flex-col items-center px-4 py-20 sm:px-6">
        <Card className="w-full">
          <CardContent className="p-6 sm:p-8">
            <h1 className="text-2xl font-bold tracking-tight text-foreground">
              로그인
            </h1>
            <p className="mt-2 text-sm leading-relaxed text-muted-foreground">
              Google 계정으로 로그인하세요.
            </p>
            <div className="mt-6">
              <GoogleLoginButton label="Google 로그인" />
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
