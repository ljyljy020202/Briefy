package com.briefy.domain.user.entity;

import com.briefy.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "users",
    uniqueConstraints = {
      @UniqueConstraint(name = "uq_users_email", columnNames = "email"),
      @UniqueConstraint(
          name = "uq_users_provider",
          columnNames = {"provider", "provider_id"})
    },
    indexes = {@Index(name = "idx_users_status", columnList = "status")})
public class User extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true)
  private String email;

  @Column(length = 100)
  private String nickname;

  @Column(length = 500)
  private String profileImageUrl;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private AuthProvider provider;

  @Column(nullable = false)
  private String providerId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private UserRole role;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private UserStatus status;

  @Column(nullable = false)
  private boolean onboardingCompleted;

  @Column(length = 255)
  private String reportEmail;

  @Column(nullable = false, columnDefinition = "TINYINT(1) DEFAULT 1")
  private boolean briefingEmailEnabled = true;

  protected User() {}

  private User(
      String email,
      String nickname,
      String profileImageUrl,
      AuthProvider provider,
      String providerId,
      UserRole role,
      UserStatus status,
      boolean onboardingCompleted) {
    this.email = email;
    this.nickname = nickname;
    this.profileImageUrl = profileImageUrl;
    this.provider = provider;
    this.providerId = providerId;
    this.role = role;
    this.status = status;
    this.onboardingCompleted = onboardingCompleted;
    this.briefingEmailEnabled = true;
  }

  public static User create(
      String email,
      String nickname,
      String profileImageUrl,
      AuthProvider provider,
      String providerId) {
    return new User(
        email,
        nickname,
        profileImageUrl,
        provider,
        providerId,
        UserRole.USER,
        UserStatus.ACTIVE,
        false);
  }

  public void completeOnboarding(String nickname, String reportEmail) {
    if (nickname != null) {
      this.nickname = nickname;
    }
    if (reportEmail != null) {
      this.reportEmail = reportEmail;
    }
    this.onboardingCompleted = true;
  }

  public Long getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getNickname() {
    return nickname;
  }

  public String getProfileImageUrl() {
    return profileImageUrl;
  }

  public AuthProvider getProvider() {
    return provider;
  }

  public String getProviderId() {
    return providerId;
  }

  public UserRole getRole() {
    return role;
  }

  public UserStatus getStatus() {
    return status;
  }

  public boolean isOnboardingCompleted() {
    return onboardingCompleted;
  }

  public String getReportEmail() {
    return reportEmail;
  }

  public boolean isBriefingEmailEnabled() {
    return briefingEmailEnabled;
  }

  public void updateBriefingEmailEnabled(boolean enabled) {
    this.briefingEmailEnabled = enabled;
  }
}
