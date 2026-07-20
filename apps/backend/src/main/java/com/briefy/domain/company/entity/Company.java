package com.briefy.domain.company.entity;

import com.briefy.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "companies",
    uniqueConstraints = {
      @UniqueConstraint(name = "uq_companies_normalized_name", columnNames = "normalized_name")
    },
    indexes = {
      @Index(name = "idx_companies_canonical_name", columnList = "canonical_name"),
      @Index(name = "idx_companies_is_active", columnList = "is_active")
    })
public class Company extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "canonical_name", nullable = false, length = 200)
  private String canonicalName;

  @Column(name = "normalized_name", nullable = false, length = 200)
  private String normalizedName;

  @Column(name = "company_size", length = 30)
  private String companySize;

  @Column(name = "industry_codes", columnDefinition = "TEXT")
  private String industryCodes;

  @Column(name = "is_active", nullable = false)
  private boolean active = true;

  protected Company() {}

  public static Company create(
      String canonicalName, String normalizedName, String companySize, String industryCodes) {
    Company c = new Company();
    c.canonicalName = canonicalName;
    c.normalizedName = normalizedName;
    c.companySize = companySize;
    c.industryCodes = industryCodes;
    c.active = true;
    return c;
  }

  public Long getId() {
    return id;
  }

  public String getCanonicalName() {
    return canonicalName;
  }

  public String getNormalizedName() {
    return normalizedName;
  }

  public String getCompanySize() {
    return companySize;
  }

  public String getIndustryCodes() {
    return industryCodes;
  }

  public boolean isActive() {
    return active;
  }

  public void activate() {
    this.active = true;
  }

  public void deactivate() {
    this.active = false;
  }

  public void update(
      String canonicalName, String normalizedName, String companySize, String industryCodes) {
    this.canonicalName = canonicalName;
    this.normalizedName = normalizedName;
    this.companySize = companySize;
    this.industryCodes = industryCodes;
  }
}
