package com.briefy.domain.company.repository;

import com.briefy.domain.company.entity.Company;
import com.briefy.domain.company.entity.CompanySource;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanySourceRepository extends JpaRepository<CompanySource, Long> {

  List<CompanySource> findAllByCompanyAndStatus(Company company, String status);
}
