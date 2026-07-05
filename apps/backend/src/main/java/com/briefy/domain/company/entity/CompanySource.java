package com.briefy.domain.company.entity;

import com.briefy.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "company_sources",
    indexes = {
      @Index(name = "idx_company_sources_company_id", columnList = "company_id"),
      @Index(name = "idx_company_sources_status", columnList = "status")
    })
public class CompanySource extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "company_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_company_sources_company"))
  private Company company;

  @Column(name = "source_type", nullable = false, length = 50)
  private String sourceType;

  @Column(name = "source_url", length = 500)
  private String sourceUrl;

  @Column(name = "adapter_type", length = 50)
  private String adapterType;

  @Column(nullable = false, length = 30)
  private String status;

  @Column(name = "config_json", columnDefinition = "TEXT")
  private String configJson;

  @Column(name = "last_verified_at")
  private LocalDateTime lastVerifiedAt;

  @Column(name = "last_collected_at")
  private LocalDateTime lastCollectedAt;

  protected CompanySource() {}

  public static CompanySource create(
      Company company,
      String sourceType,
      String sourceUrl,
      String adapterType,
      String status,
      String configJson) {
    CompanySource s = new CompanySource();
    s.company = company;
    s.sourceType = sourceType;
    s.sourceUrl = sourceUrl;
    s.adapterType = adapterType;
    s.status = status;
    s.configJson = configJson;
    return s;
  }

  public Long getId() {
    return id;
  }

  public Company getCompany() {
    return company;
  }

  public String getSourceType() {
    return sourceType;
  }

  public String getSourceUrl() {
    return sourceUrl;
  }

  public String getAdapterType() {
    return adapterType;
  }

  public String getStatus() {
    return status;
  }

  public String getConfigJson() {
    return configJson;
  }

  public LocalDateTime getLastVerifiedAt() {
    return lastVerifiedAt;
  }

  public LocalDateTime getLastCollectedAt() {
    return lastCollectedAt;
  }

  public void recordCollection(LocalDateTime collectedAt) {
    this.lastCollectedAt = collectedAt;
  }

  public void recordVerification(LocalDateTime verifiedAt) {
    this.lastVerifiedAt = verifiedAt;
  }
}
