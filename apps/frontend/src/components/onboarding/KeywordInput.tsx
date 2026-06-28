"use client";

import { useState, type KeyboardEvent } from "react";
import { X } from "lucide-react";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";

interface KeywordInputProps {
  keywords: string[];
  onChange: (keywords: string[]) => void;
  placeholder?: string;
}

export function KeywordInput({
  keywords,
  onChange,
  placeholder = "직접 입력 후 Enter…",
}: KeywordInputProps) {
  const [value, setValue] = useState("");

  const add = () => {
    const trimmed = value.trim();
    if (!trimmed || keywords.includes(trimmed) || keywords.length >= 10) return;
    onChange([...keywords, trimmed]);
    setValue("");
  };

  const remove = (kw: string) => {
    onChange(keywords.filter((k) => k !== kw));
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter" || e.key === ",") {
      e.preventDefault();
      add();
    }
    if (e.key === "Backspace" && !value && keywords.length > 0) {
      remove(keywords[keywords.length - 1]);
    }
  };

  return (
    <div className="space-y-2">
      <Input
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={handleKeyDown}
        placeholder={placeholder}
        className="text-xs h-8"
        maxLength={100}
      />
      {keywords.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {keywords.map((kw) => (
            <Badge
              key={kw}
              variant="secondary"
              className="gap-1 pr-1 text-xs cursor-default"
            >
              {kw}
              <button
                type="button"
                onClick={() => remove(kw)}
                className="hover:text-foreground transition-colors"
                aria-label={`Remove ${kw}`}
              >
                <X className="w-3 h-3" />
              </button>
            </Badge>
          ))}
        </div>
      )}
      <p className="text-xs text-muted-foreground">
        {keywords.length}/10개
      </p>
    </div>
  );
}
