package com.briefy.domain.company.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
    name = "company_aliases",
    uniqueConstraints = {
      @UniqueConstraint(name = "uq_company_aliases_normalized", columnNames = "normalized_alias")
    },
    indexes = {
      @Index(name = "idx_company_aliases_company_id", columnList = "company_id"),
      @Index(name = "idx_company_aliases_alias", columnList = "alias")
    })
public class CompanyAlias {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "company_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_company_aliases_company"))
  private Company company;

  @Column(nullable = false, length = 200)
  private String alias;

  @Column(name = "normalized_alias", nullable = false, length = 200)
  private String normalizedAlias;

  @CreatedDate
  @Column(name = "created_at", updatable = false)
  private LocalDateTime createdAt;

  protected CompanyAlias() {}

  public static CompanyAlias create(Company company, String alias, String normalizedAlias) {
    CompanyAlias a = new CompanyAlias();
    a.company = company;
    a.alias = alias;
    a.normalizedAlias = normalizedAlias;
    return a;
  }

  public Long getId() {
    return id;
  }

  public Company getCompany() {
    return company;
  }

  public String getAlias() {
    return alias;
  }

  public String getNormalizedAlias() {
    return normalizedAlias;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
