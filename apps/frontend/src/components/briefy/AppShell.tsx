'use client'

import Link from 'next/link'
import { usePathname, useRouter } from 'next/navigation'
import { useState } from 'react'
import {
  LayoutDashboard,
  LogOut,
  Newspaper,
  Settings,
  type LucideIcon,
} from 'lucide-react'

import { cn } from '@/lib/utils'
import { BriefyLogo } from '@/components/briefy/BriefyLogo'
import { useAuthContext } from '@/contexts/AuthContext'
import { auth, users } from '@/lib/api'

type NavItem = {
  href: string
  label: string
  icon: LucideIcon
}

const NAV: NavItem[] = [
  { href: '/dashboard', label: '대시보드', icon: LayoutDashboard },
  { href: '/reports', label: '브리핑', icon: Newspaper },
  { href: '/onboarding', label: '주제 설정', icon: Settings },
]

export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname()
  const router = useRouter()
  const { user } = useAuthContext()
  const [loggingOut, setLoggingOut] = useState(false)
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false)
  const [deleting, setDeleting] = useState(false)

  const displayName = user?.nickname ?? user?.email?.split('@')[0] ?? '사용자'
  const displayEmail = user?.email ?? ''
  const initial = displayName.slice(0, 1).toUpperCase()

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

  return (
    <div className="min-h-screen bg-background">
      {/* Sidebar — desktop */}
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-64 flex-col border-r border-border bg-[hsl(var(--sidebar))] px-4 py-6 lg:flex">
        <Link href="/" className="px-2">
          <BriefyLogo />
        </Link>

        <nav className="mt-8 flex flex-1 flex-col gap-1">
          {NAV.map((item) => {
            const active =
              pathname === item.href || pathname.startsWith(item.href + '/')
            return (
              <Link
                key={item.href}
                href={item.href}
                className={cn(
                  'flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition-colors',
                  active
                    ? 'bg-accent text-accent-foreground'
                    : 'text-muted-foreground hover:bg-muted hover:text-foreground',
                )}
              >
                <item.icon className="size-[18px]" />
                {item.label}
              </Link>
            )
          })}
        </nav>

        <div className="mt-auto space-y-3 px-2">
          {/* User info + logout */}
          <div className="flex items-center gap-3">
            <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-primary/10 text-sm font-semibold text-primary">
              {initial}
            </span>
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium text-foreground">
                {displayName}
              </p>
              <p className="truncate text-xs text-muted-foreground">
                {displayEmail}
              </p>
            </div>
            <button
              onClick={handleLogout}
              disabled={loggingOut}
              title="로그아웃"
              className="flex size-8 shrink-0 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-muted hover:text-foreground disabled:opacity-50"
            >
              <LogOut className="size-4" />
            </button>
          </div>

          {/* Delete account */}
          {!showDeleteConfirm ? (
            <button
              onClick={() => setShowDeleteConfirm(true)}
              className="w-full text-left text-xs text-muted-foreground transition-colors hover:text-destructive"
            >
              회원탈퇴
            </button>
          ) : (
            <div className="rounded-xl border border-destructive/30 bg-destructive/5 p-3 text-xs">
              <p className="font-medium text-foreground">정말 탈퇴하시겠어요?</p>
              <p className="mt-0.5 text-muted-foreground">모든 데이터가 삭제되며 복구할 수 없습니다.</p>
              <div className="mt-3 flex gap-2">
                <button
                  onClick={() => setShowDeleteConfirm(false)}
                  disabled={deleting}
                  className="flex-1 rounded-lg border border-border bg-card py-1.5 text-foreground transition-colors hover:bg-muted disabled:opacity-50"
                >
                  취소
                </button>
                <button
                  onClick={handleDeleteAccount}
                  disabled={deleting}
                  className="flex-1 rounded-lg bg-destructive py-1.5 text-destructive-foreground transition-opacity hover:opacity-90 disabled:opacity-50"
                >
                  {deleting ? '처리 중...' : '탈퇴하기'}
                </button>
              </div>
            </div>
          )}
        </div>
      </aside>

      {/* Mobile top bar */}
      <header className="sticky top-0 z-30 flex items-center justify-between border-b border-border bg-background px-4 py-3 lg:hidden">
        <Link href="/">
          <BriefyLogo size="sm" />
        </Link>
        <nav className="flex items-center gap-1">
          {NAV.map((item) => {
            const active =
              pathname === item.href || pathname.startsWith(item.href + '/')
            return (
              <Link
                key={item.href}
                href={item.href}
                aria-label={item.label}
                className={cn(
                  'flex size-9 items-center justify-center rounded-lg transition-colors',
                  active
                    ? 'bg-accent text-accent-foreground'
                    : 'text-muted-foreground hover:bg-muted',
                )}
              >
                <item.icon className="size-[18px]" />
              </Link>
            )
          })}
          <button
            onClick={handleLogout}
            disabled={loggingOut}
            aria-label="로그아웃"
            className="flex size-9 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-muted disabled:opacity-50"
          >
            <LogOut className="size-[18px]" />
          </button>
        </nav>
      </header>

      <main className="lg:pl-64">
        <div className="mx-auto max-w-5xl px-4 py-8 sm:px-6 lg:px-10 lg:py-10">
          {children}
        </div>
      </main>
    </div>
  )
}
