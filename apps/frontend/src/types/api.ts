// TypeScript interfaces matching the backend API contract in docs/api.md

export interface User {
  id: number;
  email: string;
  nickname: string;
  profileImageUrl: string | null;
  role: "USER" | "ADMIN";
  onboardingCompleted: boolean;
}

export interface Topic {
  id: number;
  name: string;
  slug: string;
  category: string;
  description: string;
  displayOrder: number;
}

export interface UserTopic {
  id: number;
  topicId: number;
  topicName: string;
  keyword: string;
  priority: number;
  isActive: boolean;
}

export interface BulkTopicItem {
  topicId: number;
  keywords: string[];
}

export interface BulkTopicRequest {
  topics: BulkTopicItem[];
}

export interface OnboardingResult {
  onboardingCompleted: boolean;
}

export interface SubscribedTopicGroup {
  topicName: string;
  keywords: string[];
}

export interface DashboardSummary {
  user: {
    nickname: string;
    email: string;
    onboardingCompleted: boolean;
  };
  subscribedTopics: SubscribedTopicGroup[];
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
