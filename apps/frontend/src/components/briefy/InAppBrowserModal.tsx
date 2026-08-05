'use client'

import { useState } from 'react'
import { Copy, Check, X } from 'lucide-react'
import { getOS, getExternalBrowserUrl } from '@/lib/detect-inapp'

type Props = {
  onClose: () => void
}

export function InAppBrowserModal({ onClose }: Props) {
  const [copied, setCopied] = useState(false)
  const os = getOS()

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(window.location.href)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      // clipboard API not available — fallback: select text
    }
  }

  const handleOpenExternal = () => {
    window.location.href = getExternalBrowserUrl()
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-end justify-center bg-black/50 sm:items-center"
      onClick={onClose}
    >
      <div
        className="w-full max-w-sm rounded-t-2xl bg-card p-6 shadow-xl sm:rounded-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between">
          <div>
            <p className="text-base font-semibold text-foreground">
              외부 브라우저에서 열어주세요
            </p>
            <p className="mt-1 text-sm text-muted-foreground">
              카카오톡 내부 브라우저에서는 로그인이 지원되지 않습니다.
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="ml-4 shrink-0 rounded-md p-1 text-muted-foreground hover:text-foreground"
            aria-label="닫기"
          >
            <X className="size-4" />
          </button>
        </div>

        <div className="mt-5 space-y-3">
          {os === 'android' ? (
            <button
              type="button"
              onClick={handleOpenExternal}
              className="inline-flex h-11 w-full items-center justify-center rounded-xl bg-primary text-sm font-medium text-primary-foreground"
            >
              Chrome에서 열기
            </button>
          ) : (
            <div className="rounded-xl border border-border bg-secondary/50 px-4 py-3 text-sm text-muted-foreground">
              <p className="font-medium text-foreground">Safari에서 여는 방법</p>
              <ol className="mt-1.5 list-decimal space-y-1 pl-4">
                <li>하단 또는 상단의 <span className="font-medium text-foreground">공유 버튼(⬆)</span>을 탭하세요.</li>
                <li><span className="font-medium text-foreground">Safari에서 열기</span>를 선택하세요.</li>
              </ol>
            </div>
          )}

          <button
            type="button"
            onClick={handleCopy}
            className="inline-flex h-11 w-full items-center justify-center gap-2 rounded-xl border border-border bg-card text-sm font-medium text-foreground"
          >
            {copied ? (
              <>
                <Check className="size-4 text-green-500" />
                URL 복사됨
              </>
            ) : (
              <>
                <Copy className="size-4" />
                URL 복사하기
              </>
            )}
          </button>
        </div>
      </div>
    </div>
  )
}
