import Link from 'next/link'

import { cn } from '@/lib/utils'
import { buttonVariants } from '@/components/ui/button'
import { BriefyLogo } from '@/components/briefy/BriefyLogo'

const LINKS = [
  { href: '#features', label: '기능' },
  { href: '#sample', label: '브리핑 예시' },
  { href: '#pricing', label: '요금' },
]

export function MarketingNav() {
  return (
    <header className="sticky top-0 z-40 border-b border-border bg-background">
      <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-4 sm:px-6 lg:px-8">
        <Link href="/" aria-label="Briefy 홈" className="shrink-0">
          <BriefyLogo />
        </Link>

        <nav className="hidden items-center justify-center gap-8 md:flex">
          {LINKS.map((link) => (
            <a
              key={link.href}
              href={link.href}
              className="text-sm font-medium text-muted-foreground transition-colors hover:text-foreground"
            >
              {link.label}
            </a>
          ))}
        </nav>

        <div className="flex shrink-0 items-center gap-2">
          <Link
            href="/login"
            className={cn(buttonVariants({ variant: 'ghost', size: 'sm' }))}
          >
            로그인
          </Link>
          <Link
            href="/onboarding"
            className={cn(buttonVariants({ size: 'sm' }))}
          >
            무료로 시작
          </Link>
        </div>
      </div>
    </header>
  )
}
