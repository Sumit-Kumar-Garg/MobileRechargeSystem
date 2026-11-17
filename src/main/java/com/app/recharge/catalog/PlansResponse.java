package com.app.recharge.catalog;

import java.util.List;

public record PlansResponse(String market, List<PlanView> plans) { }
