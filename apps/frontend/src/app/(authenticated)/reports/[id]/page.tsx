"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import Link from "next/link";
import { briefings } from "@/lib/api";
import type { BriefingDetail } from "@/types/api";
import { MarkdownRenderer } from "@/components/reports/MarkdownRenderer";
import { FeedbackButtons } from "@/components/reports/FeedbackButtons";

export default function ReportDetailPage() {
  const { id } = useParams<{ id: string }>();
  const reportId = Number(id);
  const [report, setReport] = useState<BriefingDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!reportId) return;

    briefings
      .get(reportId)
      .then(setReport)
      .catch((err) => {
        // TODO: Remove mock fallback when GET /api/briefings/{id} is implemented (Backend step 8)
        if (process.env.NODE_ENV === "development") {
          setReport({
            id: reportId,
            title: "Sample Briefing",
            summary: "This is mock content. Replace when the briefings API is ready.",
            content:
              "## Sample Content\n\nThe real briefing content will be rendered here as Markdown.\n\nOnce `GET /api/briefings/{id}` is implemented, this will display the AI-generated briefing.",
            reportDate: new Date().toISOString().split("T")[0],
            articles: [],
          });
        } else {
          setError(err instanceof Error ? err.message : "Failed to load report.");
        }
      })
      .finally(() => setLoading(false));
  }, [reportId]);

  if (loading) {
    return (
      <div className="space-y-6 animate-pulse max-w-2xl">
        <div className="h-8 w-2/3 rounded bg-muted" />
        <div className="h-4 w-1/3 rounded bg-muted" />
        <div className="h-48 rounded-lg bg-muted" />
      </div>
    );
  }

  if (error) {
    return (
      <div className="text-center py-16 space-y-2">
        <p className="text-destructive text-sm">{error}</p>
        <Link
          href="/reports"
          className="text-xs text-muted-foreground hover:underline"
        >
          ← Back to reports
        </Link>
      </div>
    );
  }

  if (!report) return null;

  return (
    <div className="max-w-2xl space-y-8">
      {/* Breadcrumb + title */}
      <div className="space-y-3">
        <Link
          href="/reports"
          className="text-xs text-muted-foreground hover:text-foreground transition-colors"
        >
          ← Reports
        </Link>
        <h1 className="text-2xl font-bold tracking-tight">{report.title}</h1>
        <p className="text-sm text-muted-foreground">{report.reportDate}</p>
        {report.summary && (
          <p className="text-muted-foreground leading-relaxed text-sm">
            {report.summary}
          </p>
        )}
      </div>

      {/* Markdown content */}
      <div className="border-t pt-8">
        <MarkdownRenderer content={report.content} />
      </div>

      {/* Source articles */}
      {report.articles.length > 0 && (
        <div className="border-t pt-8 space-y-4">
          <h2 className="text-base font-semibold">Sources</h2>
          <div className="space-y-4">
            {report.articles.map((article, i) => (
              <div key={i} className="space-y-1">
                <a
                  href={article.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-sm font-medium hover:underline"
                >
                  {article.title}
                </a>
                <p className="text-xs text-muted-foreground">{article.source}</p>
                {article.whyItMatters && (
                  <p className="text-xs text-muted-foreground leading-relaxed">
                    {article.whyItMatters}
                  </p>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Feedback */}
      <div className="border-t pt-8">
        <FeedbackButtons reportId={reportId} />
      </div>
    </div>
  );
}
