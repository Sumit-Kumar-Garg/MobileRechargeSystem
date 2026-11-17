package com.app.recharge.recommendation.agent;

import com.app.recharge.catalog.PlanView;
import com.app.recharge.order.OrderStatus;
import com.app.recharge.order.OrderStore;
import com.app.recharge.order.RechargeOrder;
import com.app.recharge.recommendation.UsageSummary;
import com.app.recharge.recommendation.UsageSummaryRepository;
import com.app.recharge.recommendation.spi.LlmToolCall;
import com.app.recharge.recommendation.spi.LlmToolSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The tools of ONE agent run, bound to ONE subscriber number by the server.
 *
 * Security properties, all by construction:
 * - The model never chooses whose data is read. No tool takes a user, number or order identifier; the number
 *   is fixed when the toolbox is created, after the ownership and consent checks.
 * - Every tool is read-only. The agent cannot order, pay or change anything.
 * - Tool results contain no personal identifiers: no phone number, no user or number id, only aggregates,
 *   plan codes and months.
 * - Text that comes from partners (plan names, benefit labels) is cleaned before the model sees it, so a
 *   partner feed cannot smuggle instructions into the prompt.
 */
public final class AdvisorToolbox {

    public static final String USAGE = "get_usage_summary";
    public static final String HISTORY = "get_recharge_history";
    public static final String PLANS = "list_available_plans";
    public static final String SUBMIT = "submit_recommendation";

    private static final String NO_ARGS = "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
    static final int MAX_TEXT = 60;

    static final List<LlmToolSpec> SPECS = List.of(
            new LlmToolSpec(USAGE,
                    "Monthly mobile data, voice minutes and SMS the customer used in the last months, newest first.",
                    NO_ARGS),
            new LlmToolSpec(HISTORY,
                    "The customer's last completed recharges, newest first: plan code, price, validity and month.",
                    NO_ARGS),
            new LlmToolSpec(PLANS,
                    "All plans the customer can buy now: code, name, price in minor units, validity days, "
                            + "allowances (null = not metered separately) and benefits.",
                    NO_ARGS),
            new LlmToolSpec(SUBMIT,
                    "Give the final recommendation. Call this exactly once, after looking at the customer's data "
                            + "and the available plans.",
                    "{\"type\":\"object\",\"properties\":{"
                            + "\"planCode\":{\"type\":\"string\",\"description\":\"code of one plan from list_available_plans\"},"
                            + "\"explanation\":{\"type\":\"string\",\"description\":\"one short sentence for the customer\"}},"
                            + "\"required\":[\"planCode\",\"explanation\"],\"additionalProperties\":false}"));

    private final UUID numberId;
    private final List<PlanView> catalog;
    private final OrderStore orders;
    private final UsageSummaryRepository usage;
    private final ObjectMapper mapper;
    private final Set<String> called = new HashSet<>();

    /** Creates the toolbox for one run, bound to a number whose ownership and consent were already checked. */
    AdvisorToolbox(UUID numberId, List<PlanView> catalog, OrderStore orders, UsageSummaryRepository usage,
                   ObjectMapper mapper) {
        this.numberId = numberId;
        this.catalog = catalog;
        this.orders = orders;
        this.usage = usage;
        this.mapper = mapper;
    }

    /** The tool definitions given to the model. */
    List<LlmToolSpec> specs() {
        return SPECS;
    }

    /** True once the agent has looked at the customer's own data (usage or history). */
    boolean customerDataWasRead() {
        return called.contains(USAGE) || called.contains(HISTORY);
    }

    /** True once the agent has listed the available plans. */
    boolean plansWereRead() {
        return called.contains(PLANS);
    }

    /** Runs a data tool. Arguments are ignored on purpose: no data tool needs any. Unknown tools get an error result. */
    String run(LlmToolCall call) {
        String result = switch (call.name()) {
            case USAGE -> usageSummary();
            case HISTORY -> rechargeHistory();
            case PLANS -> availablePlans();
            default -> null;
        };
        if (result == null) {
            return error("Unknown tool '" + clean(call.name()) + "'. Use only the tools you were given.");
        }
        called.add(call.name());
        return result;
    }

    /** A JSON tool result with an error message for the model. */
    String error(String message) {
        return mapper.createObjectNode().put("error", message).toString();
    }

    /** Tool result: the number's last 6 months of data, voice and SMS use, newest first, or a note if there is none. */
    private String usageSummary() {
        ArrayNode months = mapper.createArrayNode();
        for (UsageSummary u : usage.findTop6ByNumberIdOrderByMonthDesc(numberId)) {
            months.addObject()
                    .put("month", YearMonth.from(u.getMonth()).toString())
                    .put("dataMbUsed", u.getDataMbUsed())
                    .put("voiceMinUsed", u.getVoiceMinUsed())
                    .put("smsUsed", u.getSmsUsed());
        }
        ObjectNode root = mapper.createObjectNode();
        root.set("months", months);
        if (months.isEmpty()) {
            root.put("note", "No usage data is available for this customer.");
        }
        return root.toString();
    }

    /**
     * Tool result: the number's last completed recharges (plan code, price, validity, month only), or a note if there
     * are none.
     */
    private String rechargeHistory() {
        ArrayNode recharges = mapper.createArrayNode();
        for (RechargeOrder o : orders.recentByNumber(numberId, OrderStatus.COMPLETED)) {
            ObjectNode row = recharges.addObject()
                    .put("planCode", o.getPlanCode())
                    .put("priceMinor", o.getAmountMinor())
                    .put("currency", o.getCurrency())
                    .put("validityDays", o.getValidityDays());
            if (o.getCompletedAt() != null) {
                // month only: the exact time of a purchase is not needed to give advice
                row.put("month", YearMonth.from(o.getCompletedAt().atOffset(ZoneOffset.UTC)).toString());
            }
        }
        ObjectNode root = mapper.createObjectNode();
        root.set("recharges", recharges);
        if (recharges.isEmpty()) {
            root.put("note", "This customer has no completed recharges yet.");
        }
        return root.toString();
    }

    /**
     * Tool result: every plan on sale with code, cleaned name, price, validity, allowances and cleaned benefit labels.
     */
    private String availablePlans() {
        ArrayNode plans = mapper.createArrayNode();
        for (PlanView p : catalog) {
            ObjectNode row = plans.addObject()
                    .put("code", p.code())
                    .put("name", clean(p.name()))
                    .put("priceMinor", p.price().amountMinor())
                    .put("currency", p.price().currency())
                    .put("validityDays", p.validityDays());
            row.put("dataMb", p.allowances().dataMb());
            row.put("voiceMin", p.allowances().voiceMin());
            row.put("sms", p.allowances().sms());
            ArrayNode benefits = row.putArray("benefits");
            p.benefits().forEach(b -> benefits.add(clean(b.label())));
        }
        ObjectNode root = mapper.createObjectNode();
        root.set("plans", plans);
        return root.toString();
    }

    /** Keeps letters, digits and basic punctuation; cuts the length. Removes anything that could act as markup. */
    static String clean(String text) {
        if (text == null) {
            return "";
        }
        String safe = text.replaceAll("[^\\p{L}\\p{N} .,%+&/()-]", " ").replaceAll("\\s+", " ").trim();
        return safe.length() <= MAX_TEXT ? safe : safe.substring(0, MAX_TEXT);
    }
}
