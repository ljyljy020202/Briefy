package com.briefy.domain.company.repository;

import com.briefy.domain.company.entity.CompanyAlias;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.lang.NonNull;

public interface CompanyAliasRepository extends JpaRepository<CompanyAlias, Long> {

  /**
   * Return aliases whose normalized form matches any of the given names, eagerly fetching the
   * parent Company to avoid N+1 queries. Only aliases belonging to active companies are included.
   */
  @Query(
      "SELECT a FROM CompanyAlias a"
          + " JOIN FETCH a.company c"
          + " WHERE c.active = true"
          + " AND a.normalizedAlias IN :normalizedAliases")
  List<CompanyAlias> findAllByNormalizedAliasIn(
      @Param("normalizedAliases") Collection<String> normalizedAliases);

  boolean existsByNormalizedAlias(@NonNull String normalizedAlias);

  List<CompanyAlias> findAllByCompany_Id(Long companyId);

  List<CompanyAlias> findAllByCompany_IdIn(Collection<Long> companyIds);
}
