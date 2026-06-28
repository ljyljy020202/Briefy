"use client";

import { cn } from "@/lib/utils";
import type { BriefingCategory } from "@/types/api";

interface TopicCardProps {
  category: BriefingCategory;
  selected: boolean;
  onToggle: () => void;
}

export function TopicCard({ category, selected, onToggle }: TopicCardProps) {
  return (
    <button
      onClick={onToggle}
      className={cn(
        "w-full text-left p-4 rounded-lg border-2 transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2",
        selected
          ? "border-primary bg-primary/5"
          : "border-border hover:border-primary/40 hover:bg-muted/50"
      )}
    >
      <div className="flex items-start justify-between gap-2">
        <div className="space-y-1 flex-1 min-w-0">
          <p className="font-medium text-sm">{category.displayName}</p>
        </div>
        <span
          className={cn(
            "shrink-0 w-4 h-4 rounded-full border-2 mt-0.5 transition-colors",
            selected ? "border-primary bg-primary" : "border-muted-foreground"
          )}
          aria-hidden="true"
        />
      </div>
    </button>
  );
}
