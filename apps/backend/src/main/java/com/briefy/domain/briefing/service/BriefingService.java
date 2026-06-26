package com.briefy.domain.briefing.service;

import com.briefy.domain.briefing.client.AgentClient;
import com.briefy.domain.briefing.client.dto.AgentBriefingRequest;
import com.briefy.domain.briefing.client.dto.AgentBriefingResponse;
import com.briefy.domain.briefing.dto.BriefingDetailResponse;
import com.briefy.domain.briefing.dto.BriefingListItem;
import com.briefy.domain.briefing.dto.GenerateBriefingRequest;
import com.briefy.domain.briefing.dto.GenerateResult;
import com.briefy.domain.briefing.entity.BriefingArticle;
import com.briefy.domain.briefing.entity.BriefingJob;
import com.briefy.domain.briefing.entity.BriefingReport;
import com.briefy.domain.briefing.repository.BriefingJobRepository;
import com.briefy.domain.briefing.repository.BriefingReportRepository;
import com.briefy.domain.topic.entity.UserTopic;
import com.briefy.domain.topic.repository.UserTopicRepository;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import com.briefy.global.response.PageResult;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BriefingService {

  private final BriefingJobRepository briefingJobRepository;
  private final BriefingReportRepository briefingReportRepository;
  private final UserTopicRepository userTopicRepository;
  private final AgentClient agentClient;

  public BriefingService(
      BriefingJobRepository briefingJobRepository,
      BriefingReportRepository briefingReportRepository,
      UserTopicRepository userTopicRepository,
      AgentClient agentClient) {
    this.briefingJobRepository = briefingJobRepository;
    this.briefingReportRepository = briefingReportRepository;
    this.userTopicRepository = userTopicRepository;
    this.agentClient = agentClient;
  }

  /**
   * noRollbackFor ensures the job FAILED status is committed to the DB even when an exception is
   * re-thrown, so failure state is always visible for debugging and retries.
   */
  @Transactional(noRollbackFor = Exception.class)
  public GenerateResult generateBriefing(Long userId, GenerateBriefingRequest request) {
    List<UserTopic> userTopics = userTopicRepository.findAllByUserIdAndActiveTrue(userId);

    BriefingJob job = BriefingJob.createManual(userId);
    job.startProcessing();
    briefingJobRepository.save(job);

    try {
      AgentBriefingRequest agentRequest = buildAgentRequest(userId, userTopics, request);
      AgentBriefingResponse agentResponse = agentClient.generate(agentRequest);

      BriefingReport report = buildReport(userId, job, request.resolvedTone(), agentResponse);
      BriefingReport saved = briefingReportRepository.save(report);

      job.complete();
      return new GenerateResult(saved.getId(), job.getId(), job.getStatus().name());

    } catch (BusinessException e) {
      job.fail(e.getMessage());
      throw e;
    } catch (Exception e) {
      job.fail(e.getMessage() != null ? e.getMessage() : "Unknown error");
      throw new BusinessException(ErrorCode.BRIEFING_JOB_FAILED);
    }
  }

  @Transactional(readOnly = true)
  public PageResult<BriefingListItem> listBriefings(Long userId, int page, int size) {
    int cappedSize = Math.min(size, 50);
    Page<BriefingReport> reports =
        briefingReportRepository.findAllByUserIdOrderByReportDateDesc(
            userId, PageRequest.of(page, cappedSize));
    return PageResult.from(reports.map(BriefingListItem::from));
  }

  @Transactional(readOnly = true)
  public BriefingDetailResponse getBriefingDetail(Long userId, Long reportId) {
    BriefingReport report =
        briefingReportRepository
            .findById(reportId)
            .orElseThrow(() -> new BusinessException(ErrorCode.BRIEFING_REPORT_NOT_FOUND));

    if (!report.getUserId().equals(userId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    return BriefingDetailResponse.from(report);
  }

  private AgentBriefingRequest buildAgentRequest(
      Long userId, List<UserTopic> userTopics, GenerateBriefingRequest request) {
    Map<String, List<String>> grouped =
        userTopics.stream()
            .collect(
                Collectors.groupingBy(
                    ut -> ut.getTopic().getName(),
                    Collectors.mapping(UserTopic::getKeyword, Collectors.toList())));

    List<AgentBriefingRequest.TopicInput> topicInputs =
        grouped.entrySet().stream()
            .map(e -> new AgentBriefingRequest.TopicInput(e.getKey(), e.getValue()))
            .toList();

    return new AgentBriefingRequest(
        userId, topicInputs, LocalDate.now().toString(), request.resolvedTone());
  }

  private BriefingReport buildReport(
      Long userId, BriefingJob job, String tone, AgentBriefingResponse agentResponse) {
    List<AgentBriefingResponse.AgentArticle> agentArticles =
        agentResponse.articles() != null ? agentResponse.articles() : List.of();

    Integer tokenInput = null;
    Integer tokenOutput = null;
    if (agentResponse.tokenUsage() != null) {
      tokenInput = agentResponse.tokenUsage().inputTokens();
      tokenOutput = agentResponse.tokenUsage().outputTokens();
    }

    BriefingReport report =
        BriefingReport.create(
            userId,
            job,
            agentResponse.title(),
            agentResponse.summary(),
            agentResponse.content(),
            LocalDate.now(),
            tone,
            tokenInput,
            tokenOutput,
            agentArticles.size());

    for (int i = 0; i < agentArticles.size(); i++) {
      AgentBriefingResponse.AgentArticle a = agentArticles.get(i);
      report.addArticle(
          BriefingArticle.create(
              report,
              a.title(),
              a.source(),
              a.url(),
              a.summary(),
              a.whyItMatters(),
              parsePublishedAt(a.publishedAt()),
              i));
    }

    return report;
  }

  private LocalDateTime parsePublishedAt(String publishedAt) {
    if (publishedAt == null || publishedAt.isBlank()) {
      return null;
    }
    try {
      return LocalDateTime.parse(publishedAt);
    } catch (Exception e) {
      return null;
    }
  }
}
