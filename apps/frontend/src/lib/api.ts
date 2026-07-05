import type {
  User,
  BriefingCategory,
  BriefingPreference,
  UpsertBriefingPreferenceRequest,
  PatchBriefingPreferenceRequest,
  OnboardingResult,
  DashboardSummary,
  BriefingListItem,
  PaginatedResponse,
  BriefingDetail,
  GenerateResult,
  FeedbackType,
  FeedbackResult,
  CompanySearchResult,
} from "@/types/api";

export class ApiError extends Error {
  constructor(
    public readonly code: string,
    message: string
  ) {
    super(message);
    this.name = "ApiError";
  }
}

const BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "";

async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}/api${path}`, {
    ...init,
    credentials: "include",
  });
  const json = (await res.json()) as {
    success: boolean;
    data: T;
    error: { code: string; message: string } | null;
  };
  if (!json.success) {
    throw new ApiError(
      json.error?.code ?? "UNKNOWN",
      json.error?.message ?? "An unknown error occurred"
    );
  }
  return json.data;
}

export const auth = {
  oauthUrl: (): string => `${BASE}/api/oauth2/authorize/google`,
  logout: (): Promise<null> =>
    apiFetch<null>("/auth/logout", { method: "POST" }),
};

export const users = {
  me: (): Promise<User> => apiFetch<User>("/users/me"),
  completeOnboarding: (nickname?: string, reportEmail?: string): Promise<OnboardingResult> =>
    apiFetch<OnboardingResult>("/users/me/onboarding", {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ nickname, reportEmail }),
    }),
  deleteAccount: (): Promise<null> =>
    apiFetch<null>("/users/me", { method: "DELETE" }),
};

export const briefingCategories = {
  getAll: (): Promise<BriefingCategory[]> =>
    apiFetch<BriefingCategory[]>("/briefing-categories"),
};

export const briefingPreferences = {
  getMine: (): Promise<BriefingPreference[]> =>
    apiFetch<BriefingPreference[]>("/me/briefing-preferences"),
  upsert: (body: UpsertBriefingPreferenceRequest): Promise<BriefingPreference> =>
    apiFetch<BriefingPreference>("/me/briefing-preferences", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    }),
  patch: (
    id: number,
    body: PatchBriefingPreferenceRequest
  ): Promise<BriefingPreference> =>
    apiFetch<BriefingPreference>(`/me/briefing-preferences/${id}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    }),
  delete: (id: number): Promise<null> =>
    apiFetch<null>(`/me/briefing-preferences/${id}`, { method: "DELETE" }),
};

export const dashboard = {
  getSummary: (): Promise<DashboardSummary> =>
    apiFetch<DashboardSummary>("/dashboard"),
};

export const companies = {
  search: (q: string, limit = 10): Promise<CompanySearchResult[]> =>
    apiFetch<CompanySearchResult[]>(
      `/companies/search?q=${encodeURIComponent(q)}&limit=${limit}`
    ),
};

export const briefings = {
  list: (page = 0, size = 10): Promise<PaginatedResponse<BriefingListItem>> =>
    apiFetch<PaginatedResponse<BriefingListItem>>(
      `/briefings?page=${page}&size=${size}`
    ),
  get: (id: number): Promise<BriefingDetail> =>
    apiFetch<BriefingDetail>(`/briefings/${id}`),
  generate: (tone = "easy"): Promise<GenerateResult> =>
    apiFetch<GenerateResult>("/briefings/generate", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ tone }),
    }),
  feedback: (
    id: number,
    feedbackType: FeedbackType,
    comment?: string
  ): Promise<FeedbackResult> =>
    apiFetch<FeedbackResult>(`/briefings/${id}/feedback`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ feedbackType, comment }),
    }),
};
