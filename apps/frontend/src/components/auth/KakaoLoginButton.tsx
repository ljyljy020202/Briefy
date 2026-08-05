'use client'

import { useState } from 'react'
import { cn } from '@/lib/utils'
import { isInAppBrowser } from '@/lib/detect-inapp'
import { InAppBrowserModal } from '@/components/briefy/InAppBrowserModal'

function KakaoSymbol({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" className={className} aria-hidden="true" fill="none">
      {/* Kakao speech bubble balloon shape */}
      <path
        fill="#000000"
        d="M12 3C6.477 3 2 6.589 2 11.012c0 2.87 1.808 5.398 4.552 6.91L5.5 21l4.23-2.784c.74.1 1.499.152 2.27.152 5.523 0 10-3.589 10-8.012C22 6.59 17.523 3 12 3z"
      />
    </svg>
  )
}

type Props = {
  label?: string
  className?: string
}

export function KakaoLoginButton({ label = '카카오 로그인', className }: Props) {
  const [showModal, setShowModal] = useState(false)
  const [loading, setLoading] = useState(false)

  const handleClick = () => {
    if (isInAppBrowser()) {
      setShowModal(true)
      return
    }
    if (loading) return
    setLoading(true)
    const base = process.env.NEXT_PUBLIC_API_BASE_URL ?? ''
    window.location.href = `${base}/api/oauth2/authorize/kakao`
  }

  return (
    <>
      {showModal && <InAppBrowserModal onClose={() => setShowModal(false)} />}
      <button
        type="button"
        onClick={handleClick}
        disabled={loading}
        aria-disabled={loading}
        aria-label="카카오 계정으로 로그인"
        className={cn(
          'inline-flex h-11 w-full items-center justify-center gap-3 rounded-xl text-sm font-medium shadow-sm transition-colors',
          'bg-[#FEE500] text-black hover:bg-[#F5DC00]',
          loading && 'cursor-not-allowed opacity-60',
          className,
        )}
      >
        <KakaoSymbol className="size-5" />
        {label}
      </button>
    </>
  )
}
