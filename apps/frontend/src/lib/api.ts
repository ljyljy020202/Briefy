import type {
  User,
  Topic,
  UserTopic,
  BulkTopicRequest,
  OnboardingResult,
  DashboardSummary,
  BriefingListItem,
  PaginatedResponse,
  BriefingDetail,
  GenerateResult,
  FeedbackType,
  FeedbackResult,
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
  completeOnboarding: (nickname?: string): Promise<OnboardingResult> =>
    apiFetch<OnboardingResult>("/users/me/onboarding", {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ nickname }),
    }),
};

export const topics = {
  getAll: (): Promise<Topic[]> => apiFetch<Topic[]>("/topics"),
  getMine: (): Promise<UserTopic[]> => apiFetch<UserTopic[]>("/me/topics"),
  subscribeBulk: (body: BulkTopicRequest): Promise<{ createdCount: number }> =>
    apiFetch<{ createdCount: number }>("/me/topics/bulk", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    }),
  unsubscribe: (id: number): Promise<null> =>
    apiFetch<null>(`/me/topics/${id}`, { method: "DELETE" }),
};

export const dashboard = {
  getSummary: (): Promise<DashboardSummary> =>
    apiFetch<DashboardSummary>("/dashboard"),
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
