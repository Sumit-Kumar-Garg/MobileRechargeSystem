package com.app.recharge.common;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/** Cache names and Caffeine spec live in application.yml. */
@Configuration
@EnableCaching
public class CacheConfig {
}
