'use client'

import { useEffect, useMemo, useState } from 'react'
import { Search } from 'lucide-react'

import { cn } from '@/lib/utils'
import { Input } from '@/components/ui/input'
import { briefings } from '@/lib/api'
import { ReportCard } from '@/components/briefy/ReportCard'
import { MOCK_REPORTS } from '@/lib/mock-data'

const FILTERS = [
  { id: 'all', label: '전체' },
  { id: 'delivered', label: '도착함' },
  { id: 'scheduled', label: '예약됨' },
] as const

type FilterId = (typeof FILTERS)[number]['id']

export default function ReportsPage() {
  const [filter, setFilter] = useState<FilterId>('all')
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(true)
  const [useMock, setUseMock] = useState(false)

  useEffect(() => {
    briefings
      .list(0, 50)
      .then(() => {
        // TODO: Replace mock fallback with real data when GET /api/briefings is implemented (Backend step 8)
      })
      .catch(() => {
        setUseMock(true)
      })
      .finally(() => setLoading(false))
  }, [])

  const reports = useMemo(() => {
    // TODO: Replace with real reports from API (Backend step 8)
    const source = useMock ? MOCK_REPORTS : MOCK_REPORTS
    return source.filter((r) => {
      const matchesFilter = filter === 'all' || r.status === filter
      const q = query.trim().toLowerCase()
      const matchesQuery =
        !q ||
        r.title.toLowerCase().includes(q) ||
        r.preview.toLowerCase().includes(q) ||
        r.topics.some((t) => t.toLowerCase().includes(q))
      return matchesFilter && matchesQuery
    })
  }, [filter, query, useMock])

  if (loading) {
    return (
      <div className="space-y-6 animate-pulse">
        <div className="h-10 w-48 rounded-lg bg-muted" />
        <div className="h-12 rounded-lg bg-muted" />
        <div className="grid sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {[...Array(6)].map((_, i) => (
            <div key={i} className="h-40 rounded-lg bg-muted" />
          ))}
        </div>
      </div>
    )
  }

  return (
    <div>
      <h1 className="text-2xl font-bold tracking-tight text-foreground sm:text-3xl">
        브리핑 보관함
      </h1>
      <p className="mt-1 text-sm text-muted-foreground">
        지금까지 받은 모든 아침 브리핑을 한곳에서 다시 읽어보세요.
      </p>

      {/* Controls */}
      <div className="mt-6 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-1.5 rounded-xl border border-border bg-card p-1">
          {FILTERS.map((f) => (
            <button
              key={f.id}
              type="button"
              onClick={() => setFilter(f.id)}
              className={cn(
                'rounded-lg px-3.5 py-1.5 text-sm font-medium transition-colors',
                filter === f.id
                  ? 'bg-accent text-accent-foreground'
                  : 'text-muted-foreground hover:text-foreground',
              )}
            >
              {f.label}
            </button>
          ))}
        </div>

        <div className="relative w-full sm:max-w-xs">
          <Search className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="제목, 주제로 검색"
            className="pl-9"
          />
        </div>
      </div>

      {/* List */}
      {reports.length > 0 ? (
        <div className="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {reports.map((r) => (
            <ReportCard key={r.id} report={r} />
          ))}
        </div>
      ) : (
        <div className="mt-16 text-center">
          <p className="text-sm font-medium text-foreground">결과가 없어요</p>
          <p className="mt-1 text-sm text-muted-foreground">
            다른 검색어나 필터를 시도해 보세요.
          </p>
        </div>
      )}
    </div>
  )
}
