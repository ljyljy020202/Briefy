"use client";

import { useState } from "react";
import { briefings } from "@/lib/api";
import type { FeedbackType } from "@/types/api";
import { Button } from "@/components/ui/button";

const FEEDBACK_OPTIONS: { type: FeedbackType; label: string }[] = [
  { type: "USEFUL", label: "👍 Useful" },
  { type: "NOT_USEFUL", label: "👎 Not useful" },
  { type: "WANT_MORE", label: "🔁 Want more" },
  { type: "LESS_LIKE_THIS", label: "🔕 Less like this" },
];

interface FeedbackButtonsProps {
  reportId: number;
}

export function FeedbackButtons({ reportId }: FeedbackButtonsProps) {
  const [selected, setSelected] = useState<FeedbackType | null>(null);
  const [submitted, setSubmitted] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleFeedback = async (type: FeedbackType) => {
    if (loading || submitted) return;
    setSelected(type);
    setLoading(true);
    setError(null);
    try {
      await briefings.feedback(reportId, type);
      setSubmitted(true);
    } catch {
      setSelected(null);
      setError("Failed to submit feedback. Please try again.");
    } finally {
      setLoading(false);
    }
  };

  if (submitted) {
    return (
      <p className="text-sm text-muted-foreground">
        Thank you for your feedback!
      </p>
    );
  }

  return (
    <div className="space-y-3">
      <p className="text-sm font-medium">Was this briefing helpful?</p>
      <div className="flex flex-wrap gap-2">
        {FEEDBACK_OPTIONS.map(({ type, label }) => (
          <Button
            key={type}
            variant={selected === type ? "default" : "outline"}
            size="sm"
            onClick={() => handleFeedback(type)}
            disabled={loading}
          >
            {label}
          </Button>
        ))}
      </div>
      {error && <p className="text-xs text-destructive">{error}</p>}
    </div>
  );
}
