package com.briefy.domain.company.repository;

import com.briefy.domain.company.entity.Company;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CompanyRepository extends JpaRepository<Company, Long> {

  @Query(
      """
      SELECT c FROM Company c
      WHERE c.active = true
      AND (
          LOWER(c.canonicalName) LIKE LOWER(CONCAT('%', :q, '%'))
          OR EXISTS (
              SELECT a.id FROM CompanyAlias a
              WHERE a.company = c
              AND LOWER(a.alias) LIKE LOWER(CONCAT('%', :q, '%'))
          )
      )
      ORDER BY c.canonicalName ASC
      """)
  List<Company> searchByQuery(@Param("q") String q, Pageable pageable);
}
