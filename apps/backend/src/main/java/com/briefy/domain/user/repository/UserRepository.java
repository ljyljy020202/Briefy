package com.briefy.domain.user.repository;

import com.briefy.domain.user.entity.AuthProvider;
import com.briefy.domain.user.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

  Optional<User> findByEmail(String email);

  Optional<User> findByProviderAndProviderId(AuthProvider provider, String providerId);
}
