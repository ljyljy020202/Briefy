"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { dashboard, briefings } from "@/lib/api";
import type { DashboardSummary } from "@/types/api";
import { useAuthContext } from "@/contexts/AuthContext";
import { DashboardHeader } from "@/components/dashboard/DashboardHeader";
import { TopicSummaryCard } from "@/components/dashboard/TopicSummaryCard";

export default function DashboardPage() {
  const router = useRouter();
  const { user, loading: authLoading } = useAuthContext();
  const [summary, setSummary] = useState<DashboardSummary | null>(null);
  const [dataLoading, setDataLoading] = useState(true);
  const [generating, setGenerating] = useState(false);
  const [generateError, setGenerateError] = useState<string | null>(null);

  useEffect(() => {
    if (authLoading) return;

    dashboard
      .getSummary()
      .then(setSummary)
      .catch(() => {
        // TODO: Remove mock fallback when GET /api/dashboard is implemented (Backend step 12)
        if (user) {
          setSummary({
            user: {
              nickname: user.nickname,
              email: user.email,
              onboardingCompleted: user.onboardingCompleted,
            },
            subscribedTopics: [],
            nextDeliveryTime: null,
            latestBriefing: null,
            latestDeliveryStatus: null,
          });
        }
      })
      .finally(() => setDataLoading(false));
  }, [authLoading, user]);

  const handleGenerate = async () => {
    setGenerating(true);
    setGenerateError(null);
    try {
      // TODO: POST /api/briefings/generate is implemented in Backend step 8
      const result = await briefings.generate();
      router.push(`/reports/${result.briefingReportId}`);
    } catch (err) {
      setGenerateError(
        err instanceof Error ? err.message : "Generation failed. Try again."
      );
      setGenerating(false);
    }
  };

  const loading = authLoading || dataLoading;

  if (loading) {
    return (
      <div className="space-y-6 animate-pulse">
        <div className="h-24 rounded-lg bg-muted" />
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-4">
          {[...Array(3)].map((_, i) => (
            <div key={i} className="h-28 rounded-lg bg-muted" />
          ))}
        </div>
      </div>
    );
  }

  if (!summary) return null;

  return (
    <div className="space-y-10">
      <DashboardHeader
        summary={summary}
        onGenerate={handleGenerate}
        generating={generating}
      />

      {generateError && (
        <p className="text-sm text-destructive">{generateError}</p>
      )}

      <section className="space-y-4">
        <h2 className="text-lg font-semibold">Your Topics</h2>
        {summary.subscribedTopics.length === 0 ? (
          <div className="rounded-lg border border-dashed p-8 text-center text-sm text-muted-foreground">
            No topics yet.{" "}
            <Link href="/onboarding" className="underline hover:text-foreground">
              Add some on the onboarding page.
            </Link>
          </div>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
            {summary.subscribedTopics.map((t) => (
              <TopicSummaryCard key={t.topicName} topic={t} />
            ))}
          </div>
        )}
      </section>

      {summary.latestBriefing && (
        <section className="space-y-4">
          <h2 className="text-lg font-semibold">Latest Report</h2>
          <Link
            href={`/reports/${summary.latestBriefing.id}`}
            className="block rounded-lg border bg-card p-4 hover:bg-accent/40 transition-colors"
          >
            <p className="font-medium">{summary.latestBriefing.title}</p>
            <p className="text-sm text-muted-foreground mt-1">
              {summary.latestBriefing.reportDate}
            </p>
          </Link>
        </section>
      )}
    </div>
  );
}
