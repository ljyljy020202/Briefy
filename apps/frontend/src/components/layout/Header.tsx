"use client";

import Link from "next/link";
import { useAuthContext } from "@/contexts/AuthContext";
import { UserMenu } from "./UserMenu";

export function Header() {
  const { user } = useAuthContext();

  return (
    <header className="sticky top-0 z-50 border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
      <div className="max-w-5xl mx-auto px-4 h-16 flex items-center justify-between">
        <Link
          href="/dashboard"
          className="font-bold text-lg tracking-tight hover:opacity-80 transition-opacity"
        >
          Briefy
        </Link>
        <nav className="flex items-center gap-6">
          <Link
            href="/dashboard"
            className="text-sm text-muted-foreground hover:text-foreground transition-colors"
          >
            Dashboard
          </Link>
          <Link
            href="/reports"
            className="text-sm text-muted-foreground hover:text-foreground transition-colors"
          >
            Reports
          </Link>
          {user && <UserMenu user={user} />}
        </nav>
      </div>
    </header>
  );
}
