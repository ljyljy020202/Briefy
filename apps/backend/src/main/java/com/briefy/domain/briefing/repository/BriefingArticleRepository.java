package com.briefy.domain.briefing.repository;

import com.briefy.domain.briefing.entity.BriefingArticle;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BriefingArticleRepository extends JpaRepository<BriefingArticle, Long> {

  /** Projection returned by {@link #findRecentExposuresByUserId}. */
  interface ExposedUrlInfo {
    String getUrl();

    LocalDate getLastExposedDate();
  }

  /**
   * Returns the most recent report date each URL was shown to a user within the given look-back
   * period. URLs with multiple appearances collapse into a single row via {@code MAX(reportDate)}.
   * Only articles from reports on or after {@code since} are included.
   */
  @Query(
      "SELECT ba.url AS url, MAX(br.reportDate) AS lastExposedDate"
          + " FROM BriefingArticle ba JOIN ba.briefingReport br"
          + " WHERE br.userId = :userId AND br.reportDate >= :since"
          + " GROUP BY ba.url")
  List<ExposedUrlInfo> findRecentExposuresByUserId(
      @Param("userId") Long userId, @Param("since") LocalDate since);
}
