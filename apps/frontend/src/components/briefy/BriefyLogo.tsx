import { cn } from '@/lib/utils'

type LogoProps = {
  className?: string
  showWordmark?: boolean
  size?: 'sm' | 'md' | 'lg'
}

const markSize = {
  sm: 'size-7 rounded-lg',
  md: 'size-9 rounded-xl',
  lg: 'size-11 rounded-2xl',
}

const textSize = {
  sm: 'text-lg',
  md: 'text-xl',
  lg: 'text-2xl',
}

export function BriefyLogo({
  className,
  showWordmark = true,
  size = 'md',
}: LogoProps) {
  return (
    <span className={cn('inline-flex items-center gap-2.5', className)}>
      <span
        aria-hidden="true"
        className={cn(
          'relative flex items-center justify-center overflow-hidden bg-primary text-primary-foreground shadow-sm',
          markSize[size],
        )}
      >
        <span className="flex flex-col gap-[3px]">
          <span className="block h-[2px] w-4 rounded-full bg-primary-foreground/90" />
          <span className="block h-[2px] w-3 rounded-full bg-primary-foreground/60" />
          <span className="block h-[2px] w-3.5 rounded-full bg-primary-foreground/40" />
        </span>
        <span className="absolute -bottom-1.5 -right-1.5 size-4 rounded-full bg-accent-foreground/0 ring-2 ring-primary-foreground/40" />
        <span className="absolute bottom-1 right-1 size-1.5 rounded-full bg-primary-foreground" />
      </span>
      {showWordmark && (
        <span
          className={cn(
            'font-semibold tracking-tight text-foreground',
            textSize[size],
          )}
        >
          Briefy
        </span>
      )}
    </span>
  )
}
