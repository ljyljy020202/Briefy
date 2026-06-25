"use client";

import { useEffect, useState } from "react";
import { briefings } from "@/lib/api";
import type { BriefingListItem, PaginatedResponse } from "@/types/api";
import { ReportCard } from "@/components/reports/ReportCard";
import { Button } from "@/components/ui/button";

export default function ReportsPage() {
  const [data, setData] = useState<PaginatedResponse<BriefingListItem> | null>(
    null
  );
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    briefings
      .list(page)
      .then(setData)
      .catch(() => {
        // TODO: Remove mock fallback when GET /api/briefings is implemented (Backend step 8)
        setData({
          content: [],
          page,
          size: 10,
          totalElements: 0,
          totalPages: 0,
        });
      })
      .finally(() => setLoading(false));
  }, [page]);

  if (loading) {
    return (
      <div className="space-y-4 animate-pulse">
        <div className="h-8 w-32 rounded bg-muted" />
        {[...Array(3)].map((_, i) => (
          <div key={i} className="h-24 rounded-lg bg-muted" />
        ))}
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold tracking-tight">Reports</h1>

      {!data || data.content.length === 0 ? (
        <div className="rounded-lg border border-dashed py-16 text-center">
          <p className="text-muted-foreground text-sm">No reports yet.</p>
          <p className="text-muted-foreground text-xs mt-1">
            Generate your first briefing from the dashboard.
          </p>
        </div>
      ) : (
        <div className="space-y-3">
          {data.content.map((report) => (
            <ReportCard key={report.id} report={report} />
          ))}
        </div>
      )}

      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-center gap-3">
          <Button
            variant="outline"
            size="sm"
            onClick={() => setPage((p) => p - 1)}
            disabled={page === 0}
          >
            ← Previous
          </Button>
          <span className="text-sm text-muted-foreground">
            {page + 1} / {data.totalPages}
          </span>
          <Button
            variant="outline"
            size="sm"
            onClick={() => setPage((p) => p + 1)}
            disabled={page + 1 >= data.totalPages}
          >
            Next →
          </Button>
        </div>
      )}
    </div>
  );
}
