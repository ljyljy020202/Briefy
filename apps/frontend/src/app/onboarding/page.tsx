"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { users, topics as topicsApi, ApiError } from "@/lib/api";
import type { Topic } from "@/types/api";
import { TopicCard } from "@/components/onboarding/TopicCard";
import { KeywordInput } from "@/components/onboarding/KeywordInput";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

export default function OnboardingPage() {
  const router = useRouter();
  const [pageLoading, setPageLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [availableTopics, setAvailableTopics] = useState<Topic[]>([]);
  // Map<topicId, keywords[]>
  const [selections, setSelections] = useState<Map<number, string[]>>(
    new Map()
  );
  const [nickname, setNickname] = useState("");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([users.me(), topicsApi.getAll()])
      .then(([user, allTopics]) => {
        if (user.onboardingCompleted) {
          router.replace("/dashboard");
          return;
        }
        setNickname(user.nickname ?? "");
        setAvailableTopics(allTopics);
        setPageLoading(false);
      })
      .catch((err) => {
        if (err instanceof ApiError && err.code === "UNAUTHORIZED") {
          router.replace("/");
        } else {
          setError("Failed to load. Please refresh and try again.");
          setPageLoading(false);
        }
      });
  }, [router]);

  const toggleTopic = (topicId: number) => {
    setSelections((prev) => {
      const next = new Map(prev);
      if (next.has(topicId)) {
        next.delete(topicId);
      } else {
        next.set(topicId, []);
      }
      return next;
    });
  };

  const setKeywords = (topicId: number, keywords: string[]) => {
    setSelections((prev) => new Map(prev).set(topicId, keywords));
  };

  const handleSubmit = async () => {
    if (selections.size === 0) {
      setError("Please select at least one topic.");
      return;
    }
    setSubmitting(true);
    setError(null);

    try {
      const bulkTopics = Array.from(selections.entries()).map(
        ([topicId, keywords]) => {
          const topic = availableTopics.find((t) => t.id === topicId);
          return {
            topicId,
            // Fall back to topic name if the user left keywords empty
            keywords: keywords.length > 0 ? keywords : [topic?.name ?? "general"],
          };
        }
      );

      await topicsApi.subscribeBulk({ topics: bulkTopics });
      await users.completeOnboarding(nickname.trim() || undefined);
      router.push("/dashboard");
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Something went wrong. Try again."
      );
      setSubmitting(false);
    }
  };

  if (pageLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <p className="text-sm text-muted-foreground">Loading…</p>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-muted/20 py-12 px-4">
      <div className="max-w-2xl mx-auto space-y-8">
        {/* Header */}
        <div className="text-center space-y-2">
          <h1 className="text-3xl font-bold tracking-tight">
            Set up your briefing
          </h1>
          <p className="text-muted-foreground">
            Choose topics to follow. Add keywords to narrow each one down.
          </p>
        </div>

        {/* Nickname */}
        <div className="space-y-1.5">
          <label htmlFor="nickname" className="text-sm font-medium">
            What should we call you?{" "}
            <span className="text-muted-foreground font-normal">(optional)</span>
          </label>
          <Input
            id="nickname"
            value={nickname}
            onChange={(e) => setNickname(e.target.value)}
            placeholder="Your name"
            className="max-w-xs"
            maxLength={100}
          />
        </div>

        {/* Topic grid */}
        <div className="space-y-3">
          <p className="text-sm font-medium">
            Topics{" "}
            <span className="text-muted-foreground font-normal">
              — select all that apply
            </span>
          </p>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            {availableTopics.map((topic) => (
              <div key={topic.id} className="space-y-2">
                <TopicCard
                  topic={topic}
                  selected={selections.has(topic.id)}
                  onToggle={() => toggleTopic(topic.id)}
                />
                {selections.has(topic.id) && (
                  <div className="pl-2">
                    <KeywordInput
                      keywords={selections.get(topic.id) ?? []}
                      onChange={(kws) => setKeywords(topic.id, kws)}
                      placeholder={`Keywords for ${topic.name}…`}
                    />
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>

        {error && <p className="text-sm text-destructive">{error}</p>}

        <div className="flex justify-between items-center pt-2">
          <p className="text-xs text-muted-foreground">
            {selections.size} topic{selections.size !== 1 ? "s" : ""} selected
          </p>
          <Button
            onClick={handleSubmit}
            disabled={submitting || selections.size === 0}
            size="lg"
          >
            {submitting ? "Setting up…" : "Start my briefing →"}
          </Button>
        </div>
      </div>
    </div>
  );
}
