'use client'

import Link from 'next/link'
import { ArrowUpRight, Clock, CalendarClock } from 'lucide-react'

import { Badge } from '@/components/ui/badge'
import { Card } from '@/components/ui/card'
import type { MockReport } from '@/lib/mock-data'

const STATUS_LABEL: Record<
  MockReport['status'],
  { label: string; variant: 'success' | 'default' | 'muted' }
> = {
  delivered: { label: '도착함', variant: 'success' },
  scheduled: { label: '예약됨', variant: 'default' },
  draft: { label: '임시저장', variant: 'muted' },
}

export function ReportCard({ report }: { report: MockReport }) {
  const status = STATUS_LABEL[report.status]
  const isScheduled = report.status === 'scheduled'

  const inner = (
    <Card className="group h-full p-5 transition-shadow hover:shadow-md">
      <div className="flex items-start justify-between gap-3">
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          {isScheduled ? (
            <CalendarClock className="size-3.5" />
          ) : (
            <Clock className="size-3.5" />
          )}
          {report.date}
        </div>
        <Badge variant={status.variant}>{status.label}</Badge>
      </div>

      <h3 className="mt-3 flex items-center gap-1 text-base font-semibold text-foreground">
        {report.title}
        {!isScheduled && (
          <ArrowUpRight className="size-4 text-muted-foreground transition-transform group-hover:translate-x-0.5 group-hover:-translate-y-0.5" />
        )}
      </h3>

      <p className="mt-2 line-clamp-2 text-sm leading-relaxed text-muted-foreground">
        {report.preview}
      </p>

      <div className="mt-4 flex flex-wrap items-center gap-1.5">
        {report.topics.map((t) => (
          <Badge key={t} variant="muted">
            {t}
          </Badge>
        ))}
      </div>

      {!isScheduled && (
        <p className="mt-4 text-xs text-muted-foreground">{report.readTime}</p>
      )}
    </Card>
  )

  if (isScheduled) return inner

  return (
    <Link href={`/reports/${report.id}`} className="block">
      {inner}
    </Link>
  )
}
