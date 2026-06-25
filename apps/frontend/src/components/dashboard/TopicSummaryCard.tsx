import { Badge } from "@/components/ui/badge";
import type { SubscribedTopicGroup } from "@/types/api";

interface TopicSummaryCardProps {
  topic: SubscribedTopicGroup;
}

export function TopicSummaryCard({ topic }: TopicSummaryCardProps) {
  return (
    <div className="rounded-lg border bg-card p-4 space-y-2">
      <h3 className="text-sm font-medium">{topic.topicName}</h3>
      <div className="flex flex-wrap gap-1">
        {topic.keywords.map((kw) => (
          <Badge key={kw} variant="secondary" className="text-xs font-normal">
            {kw}
          </Badge>
        ))}
      </div>
    </div>
  );
}
