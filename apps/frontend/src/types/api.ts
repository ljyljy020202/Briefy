// TypeScript interfaces matching the backend API contract in docs/api.md

export interface User {
  id: number;
  email: string;
  nickname: string;
  profileImageUrl: string | null;
  role: "USER" | "ADMIN";
  onboardingCompleted: boolean;
}

export interface BriefingCategory {
  id: number;
  code: string;
  displayName: string;
  phase: string;
  active: boolean;
}

export interface JobPostingPreference {
  roles?: string[];
  companies?: string[];
  skills?: string[];
  locations?: string[];
  experienceLevels?: string[];
  employmentTypes?: string[];
}

export interface BriefingPreference {
  id: number;
  categoryCode: string;
  categoryDisplayName: string;
  preference: JobPostingPreference;
  active: boolean;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface UpsertBriefingPreferenceRequest {
  categoryId: number;
  preference: JobPostingPreference;
}

export interface PatchBriefingPreferenceRequest {
  preference: Partial<JobPostingPreference>;
}

export interface OnboardingResult {
  onboardingCompleted: boolean;
}

export interface BriefingPreferenceSummary {
  categoryCode: string;
  categoryDisplayName: string;
  preference: Record<string, string[]>;
}

export interface DashboardSummary {
  user: {
    nickname: string;
    email: string;
    onboardingCompleted: boolean;
  };
  briefingPreferences: BriefingPreferenceSummary[];
  nextDeliveryTime: string | null;
  latestBriefing: BriefingListItem | null;
  latestDeliveryStatus: "SENT" | "PENDING" | "FAILED" | null;
  recentReports: BriefingListItem[];
}

export interface BriefingListItem {
  id: number;
  title: string;
  summary: string;
  reportDate: string;
  articleCount: number;
  createdAt: string;
}

export interface PaginatedResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface BriefingArticle {
  title: string;
  source: string;
  url: string;
  summary: string;
  whyItMatters: string;
  publishedAt: string;
}

export interface BriefingDetail {
  id: number;
  title: string;
  summary: string;
  content: string;
  reportDate: string;
  articles: BriefingArticle[];
}

export interface GenerateResult {
  briefingReportId: number;
  jobId: number;
  status: string;
}

export type FeedbackType = "USEFUL" | "NOT_USEFUL" | "WANT_MORE" | "LESS_LIKE_THIS";

export interface FeedbackResult {
  id: number;
  feedbackType: FeedbackType;
  comment: string | null;
}
