"use client";

import { Button } from "@/components/ui/button";
import type { DashboardSummary } from "@/types/api";

interface DashboardHeaderProps {
  summary: DashboardSummary;
  onGenerate: () => void;
  generating: boolean;
}

export function DashboardHeader({
  summary,
  onGenerate,
  generating,
}: DashboardHeaderProps) {
  const greeting = summary.user.nickname
    ? `Hi, ${summary.user.nickname}`
    : "Hi there";

  const formatDelivery = (iso: string | null) => {
    if (!iso) return "Not scheduled";
    return new Date(iso).toLocaleTimeString([], {
      hour: "2-digit",
      minute: "2-digit",
    });
  };

  return (
    <div className="flex items-start justify-between gap-4 flex-wrap">
      <div className="space-y-1">
        <h1 className="text-2xl font-bold tracking-tight">{greeting}</h1>
        <p className="text-sm text-muted-foreground">
          Next delivery:{" "}
          <span className="font-medium text-foreground">
            {formatDelivery(summary.nextDeliveryTime)}
          </span>
        </p>
        {summary.latestDeliveryStatus && (
          <p className="text-xs text-muted-foreground">
            Last delivery status:{" "}
            <span className="font-medium">{summary.latestDeliveryStatus}</span>
          </p>
        )}
      </div>
      <Button onClick={onGenerate} disabled={generating}>
        {generating ? "Generating…" : "Generate Now"}
      </Button>
    </div>
  );
}
