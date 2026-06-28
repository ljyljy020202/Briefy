import { Badge } from "@/components/ui/badge";
import type { BriefingPreferenceSummary } from "@/types/api";

const FIELD_LABELS: Record<string, string> = {
  roles: "목표 직무",
  companies: "관심 기업",
  skills: "기술/역량",
  locations: "선호 지역",
  experienceLevels: "경력 수준",
  employmentTypes: "고용 형태",
};

interface PreferenceSummaryCardProps {
  preference: BriefingPreferenceSummary;
}

export function TopicSummaryCard({ preference }: PreferenceSummaryCardProps) {
  return (
    <div className="rounded-lg border bg-card p-4 space-y-3">
      <h3 className="text-sm font-medium">{preference.categoryDisplayName}</h3>
      {Object.entries(preference.preference).map(([key, values]) => {
        if (!Array.isArray(values) || values.length === 0) return null;
        return (
          <div key={key} className="space-y-1">
            <p className="text-xs text-muted-foreground">
              {FIELD_LABELS[key] ?? key}
            </p>
            <div className="flex flex-wrap gap-1">
              {values.map((v: string) => (
                <Badge
                  key={v}
                  variant="secondary"
                  className="text-xs font-normal"
                >
                  {v}
                </Badge>
              ))}
            </div>
          </div>
        );
      })}
    </div>
  );
}
