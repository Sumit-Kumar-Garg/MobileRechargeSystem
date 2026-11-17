package com.app.recharge.recommendation.agent;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoredAdviceRepository extends JpaRepository<StoredAdvice, UUID> { }
