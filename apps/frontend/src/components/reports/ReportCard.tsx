import Link from "next/link";
import type { BriefingListItem } from "@/types/api";

interface ReportCardProps {
  report: BriefingListItem;
}

export function ReportCard({ report }: ReportCardProps) {
  return (
    <Link
      href={`/reports/${report.id}`}
      className="block p-4 rounded-lg border bg-card hover:bg-accent/40 transition-colors"
    >
      <div className="flex items-start justify-between gap-4">
        <div className="flex-1 min-w-0 space-y-1">
          <h3 className="font-medium leading-tight">{report.title}</h3>
          {report.summary && (
            <p className="text-sm text-muted-foreground line-clamp-2">
              {report.summary}
            </p>
          )}
        </div>
        <div className="shrink-0 text-right space-y-1">
          <p className="text-xs text-muted-foreground">{report.reportDate}</p>
          <p className="text-xs text-muted-foreground">
            {report.articleCount} articles
          </p>
        </div>
      </div>
    </Link>
  );
}
