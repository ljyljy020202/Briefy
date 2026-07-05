package com.briefy.domain.delivery.repository;

import com.briefy.domain.delivery.entity.DeliveryLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryLogRepository extends JpaRepository<DeliveryLog, Long> {}
