package com.briefy.domain.company.repository;

import com.briefy.domain.company.entity.Company;
import com.briefy.domain.company.entity.CompanySource;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CompanySourceRepository extends JpaRepository<CompanySource, Long> {

  List<CompanySource> findAllByCompanyAndStatus(Company company, String status);

  @Query(
      "SELECT cs FROM CompanySource cs"
          + " WHERE cs.company.id IN :companyIds AND cs.status = :status")
  List<CompanySource> findActiveByCompanyIds(
      @Param("companyIds") List<Long> companyIds, @Param("status") String status);

  List<CompanySource> findAllByCompany_Id(Long companyId);

  List<CompanySource> findAllByCompany_IdIn(Collection<Long> companyIds);

  boolean existsByCompany_IdAndSourceTypeAndSourceUrl(
      Long companyId, String sourceType, String sourceUrl);

  boolean existsByCompany_IdAndSourceTypeAndSourceUrlAndIdNot(
      Long companyId, String sourceType, String sourceUrl, Long id);
}
