package com.briefy.domain.collection.dto;

import java.time.LocalDate;
import java.util.List;

public record TriggerDailyCollectionRequest(LocalDate collectDate, List<String> categories) {}
