package com.app.recharge.recommendation;

import com.app.recharge.number.NumberService;
import com.app.recharge.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Demo only: lets you set this month's data usage for your own number to show usage-based recommendations. */
@RestController
@Profile("demo")
@RequestMapping("/v1/demo/usage")
public class DemoUsageController {

    private final NumberService numbers;
    private final UsageSummaryRepository usage;
    private final Clock clock;

    public DemoUsageController(NumberService numbers, UsageSummaryRepository usage, Clock clock) {
        this.numbers = numbers;
        this.usage = usage;
        this.clock = clock;
    }

    /** POST /v1/demo/usage: sets this month's data usage for one of the caller's numbers. Returns 204. */
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void set(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UsageRequest request) {
        numbers.requireOwned(CurrentUser.id(jwt), request.numberId());
        LocalDate month = LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
        UsageSummary row = usage.findByNumberIdAndMonth(request.numberId(), month)
                .orElseGet(() -> new UsageSummary(request.numberId(), month, 0));
        row.setDataMbUsed(request.dataMbUsed());
        usage.save(row);
    }

    public record UsageRequest(@NotNull UUID numberId, @Min(0) @Max(1_000_000) int dataMbUsed) { }
}
