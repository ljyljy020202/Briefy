'use client'

import * as React from 'react'
import { cn } from '@/lib/utils'
import { buttonVariants } from '@/components/ui/button'

// ── Context ──────────────────────────────────────────────────────────────────

interface AlertDialogContextValue {
  open: boolean
  onOpenChange: (open: boolean) => void
}

const AlertDialogContext = React.createContext<AlertDialogContextValue | null>(null)

function useAlertDialog() {
  const ctx = React.useContext(AlertDialogContext)
  if (!ctx) throw new Error('AlertDialog compound components must be used within <AlertDialog>')
  return ctx
}

// ── Root ─────────────────────────────────────────────────────────────────────

interface AlertDialogProps {
  open?: boolean
  onOpenChange?: (open: boolean) => void
  children: React.ReactNode
}

function AlertDialog({ open = false, onOpenChange = () => {}, children }: AlertDialogProps) {
  return (
    <AlertDialogContext.Provider value={{ open, onOpenChange }}>
      {children}
    </AlertDialogContext.Provider>
  )
}

// ── Trigger ───────────────────────────────────────────────────────────────────

function AlertDialogTrigger({ children }: { children: React.ReactNode }) {
  const { onOpenChange } = useAlertDialog()
  return React.cloneElement(children as React.ReactElement<{ onClick?: () => void }>, {
    onClick: () => onOpenChange(true),
  })
}

// ── Content ───────────────────────────────────────────────────────────────────

function AlertDialogContent({ className, children, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  const { open, onOpenChange } = useAlertDialog()

  React.useEffect(() => {
    if (!open) return
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onOpenChange(false)
    }
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [open, onOpenChange])

  if (!open) return null

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div
        className="fixed inset-0 bg-black/50"
        aria-hidden="true"
        onClick={() => onOpenChange(false)}
      />
      <div
        role="alertdialog"
        aria-modal="true"
        className={cn(
          'relative z-50 mx-4 w-full max-w-md rounded-lg bg-background p-6 shadow-lg',
          className,
        )}
        {...props}
      >
        {children}
      </div>
    </div>
  )
}

// ── Header ────────────────────────────────────────────────────────────────────

function AlertDialogHeader({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('flex flex-col gap-2', className)} {...props} />
}

// ── Footer ────────────────────────────────────────────────────────────────────

function AlertDialogFooter({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn('mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end', className)}
      {...props}
    />
  )
}

// ── Title ─────────────────────────────────────────────────────────────────────

function AlertDialogTitle({ className, ...props }: React.HTMLAttributes<HTMLHeadingElement>) {
  return <h2 className={cn('text-lg font-semibold text-foreground', className)} {...props} />
}

// ── Description ───────────────────────────────────────────────────────────────

function AlertDialogDescription({ className, ...props }: React.HTMLAttributes<HTMLParagraphElement>) {
  return <p className={cn('text-sm text-muted-foreground', className)} {...props} />
}

// ── Action / Cancel ───────────────────────────────────────────────────────────

function AlertDialogAction({
  className,
  onClick,
  ...props
}: React.ButtonHTMLAttributes<HTMLButtonElement>) {
  const { onOpenChange } = useAlertDialog()
  return (
    <button
      className={cn(buttonVariants(), className)}
      onClick={(e) => {
        onClick?.(e)
        onOpenChange(false)
      }}
      {...props}
    />
  )
}

function AlertDialogCancel({
  className,
  onClick,
  ...props
}: React.ButtonHTMLAttributes<HTMLButtonElement>) {
  const { onOpenChange } = useAlertDialog()
  return (
    <button
      className={cn(buttonVariants({ variant: 'outline' }), className)}
      onClick={(e) => {
        onClick?.(e)
        onOpenChange(false)
      }}
      {...props}
    />
  )
}

export {
  AlertDialog,
  AlertDialogTrigger,
  AlertDialogContent,
  AlertDialogHeader,
  AlertDialogFooter,
  AlertDialogTitle,
  AlertDialogDescription,
  AlertDialogAction,
  AlertDialogCancel,
}
