'use client'

import Link from 'next/link'
import { usePathname } from 'next/navigation'
import {
  LayoutDashboard,
  Newspaper,
  User,
  type LucideIcon,
} from 'lucide-react'

import { cn } from '@/lib/utils'
import { BriefyLogo } from '@/components/briefy/BriefyLogo'
import { useAuthContext } from '@/contexts/AuthContext'

type NavItem = {
  href: string
  label: string
  icon: LucideIcon
}

const NAV: NavItem[] = [
  { href: '/dashboard', label: '대시보드', icon: LayoutDashboard },
  { href: '/reports', label: '브리핑', icon: Newspaper },
  { href: '/mypage', label: '마이페이지', icon: User },
]

export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname()
  const { user } = useAuthContext()

  const displayName = user?.nickname ?? user?.email?.split('@')[0] ?? '사용자'
  const displayEmail = user?.email ?? ''
  const initial = displayName.slice(0, 1).toUpperCase()

  return (
    <div className="min-h-screen bg-background">
      {/* Sidebar — desktop */}
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-64 flex-col border-r border-border bg-[hsl(var(--sidebar))] px-4 py-6 lg:flex">
        <Link href="/dashboard" className="px-2">
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

        <div className="mt-auto px-2">
          <Link
            href="/mypage"
            className="flex items-center gap-3 rounded-xl px-2 py-2 transition-colors hover:bg-muted"
          >
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
          </Link>
        </div>
      </aside>

      {/* Mobile top bar */}
      <header className="sticky top-0 z-30 flex items-center justify-between border-b border-border bg-background px-4 py-3 lg:hidden">
        <Link href="/dashboard">
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
