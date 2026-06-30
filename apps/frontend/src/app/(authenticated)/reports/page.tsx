'use client'

import { useEffect, useMemo, useState } from 'react'
import { Search } from 'lucide-react'

import { Input } from '@/components/ui/input'
import { briefings } from '@/lib/api'
import { ReportCard } from '@/components/briefy/ReportCard'
import type { BriefingListItem } from '@/types/api'
import type { MockReport } from '@/lib/mock-data'

function briefingItemToCard(item: BriefingListItem): MockReport {
  return {
    id: String(item.id),
    title: item.title,
    date: item.reportDate,
    readTime: item.articleCount > 0 ? `${item.articleCount}건` : '',
    status: 'delivered',
    topics: [],
    preview: item.summary ?? '',
    highlights: [],
    sections: [],
  }
}

export default function ReportsPage() {
  const [query, setQuery] = useState('')
  const [items, setItems] = useState<BriefingListItem[]>([])
  const [loading, setLoading] = useState(true)
  const [fetchError, setFetchError] = useState(false)

  useEffect(() => {
    briefings
      .list(0, 50)
      .then((res) => setItems(res.content))
      .catch(() => setFetchError(true))
      .finally(() => setLoading(false))
  }, [])

  const reports = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return items
    return items.filter(
      (item) =>
        item.title.toLowerCase().includes(q) ||
        (item.summary ?? '').toLowerCase().includes(q),
    )
  }, [items, query])

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

      {/* Search */}
      <div className="relative mt-6 w-full sm:max-w-xs">
        <Search className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="제목으로 검색"
          className="pl-9"
        />
      </div>

      {/* List */}
      {fetchError ? (
        <div className="mt-16 text-center">
          <p className="text-sm font-medium text-foreground">브리핑을 불러오지 못했습니다.</p>
          <p className="mt-1 text-sm text-muted-foreground">새로고침 후 다시 시도해 주세요.</p>
        </div>
      ) : items.length === 0 ? (
        <div className="mt-16 text-center">
          <p className="text-sm font-medium text-foreground">아직 받은 브리핑이 없습니다.</p>
          <p className="mt-1 text-sm text-muted-foreground">
            선호도를 설정하면 매일 아침 맞춤 브리핑이 전달됩니다.
          </p>
        </div>
      ) : reports.length > 0 ? (
        <div className="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {reports.map((item) => (
            <ReportCard key={item.id} report={briefingItemToCard(item)} />
          ))}
        </div>
      ) : (
        <div className="mt-16 text-center">
          <p className="text-sm font-medium text-foreground">검색 결과가 없습니다.</p>
          <p className="mt-1 text-sm text-muted-foreground">다른 검색어를 시도해 보세요.</p>
        </div>
      )}
    </div>
  )
}
