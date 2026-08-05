import type { Metadata, Viewport } from 'next'
import localFont from 'next/font/local'
import './globals.css'

const pretendard = localFont({
  src: '../../node_modules/pretendard/dist/web/variable/woff2/PretendardVariable.woff2',
  variable: '--font-sans',
  display: 'swap',
  weight: '45 920',
})

export const metadata: Metadata = {
  title: 'Briefy — 흩어진 채용 공고를 매일 아침 한 번에',
  description:
    'Briefy는 당신이 선택한 주제와 키워드를 기반으로 매일 아침 AI가 정리한 맞춤형 브리핑을 이메일로 보내드립니다.',
}

export const viewport: Viewport = {
  colorScheme: 'light dark',
  themeColor: [
    { media: '(prefers-color-scheme: light)', color: 'white' },
    { media: '(prefers-color-scheme: dark)', color: 'black' },
  ],
}

export default function RootLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <html
      lang="ko"
      suppressHydrationWarning
      className={pretendard.variable}
    >
      <body className="min-h-screen bg-background font-sans antialiased">
        {children}
      </body>
    </html>
  )
}
