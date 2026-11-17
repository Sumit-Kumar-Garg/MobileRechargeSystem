package com.app.recharge.recommendation;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationLogRepository extends JpaRepository<RecommendationLog, UUID> { }
