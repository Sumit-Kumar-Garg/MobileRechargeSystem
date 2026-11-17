package com.app.recharge.telemetry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Last line of defense against PII in telemetry: clients should never send it, but we do not rely on that.
 * Keys are restricted, values are limited to short primitives, and anything that looks like an email or a
 * phone number is replaced.
 */
@Component
public class PiiGuard {

    public record Result(Map<String, Object> props, int redactions) { }

    static final int MAX_PROPS = 20;
    static final int MAX_VALUE_LENGTH = 100;
    private static final Pattern KEY = Pattern.compile("^[a-zA-Z0-9_]{1,40}$");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    private static final Pattern PHONE = Pattern.compile("(?:\\+|00)?(?:\\d[\\s().-]?){9,15}");

    /**
     * Returns a cleaned copy of the event properties: at most 20 keys with simple names, numbers and booleans as they
     * are, strings cut to 100 characters, and strings that look like an email or phone number replaced with
     * "[redacted]". Nested values and nulls are dropped. Also returns how many values were redacted.
     */
    public Result sanitize(Map<String, Object> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        int redactions = 0;
        if (input == null) {
            return new Result(out, 0);
        }
        for (Map.Entry<String, Object> e : input.entrySet()) {
            if (out.size() >= MAX_PROPS || e.getKey() == null || !KEY.matcher(e.getKey()).matches()) {
                continue;
            }
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) {
                out.put(e.getKey(), v);
            } else if (v instanceof String s) {
                if (EMAIL.matcher(s).find() || PHONE.matcher(s).find()) {
                    out.put(e.getKey(), "[redacted]");
                    redactions++;
                } else {
                    out.put(e.getKey(), s.length() > MAX_VALUE_LENGTH ? s.substring(0, MAX_VALUE_LENGTH) : s);
                }
            }
            // nested objects, arrays and nulls are dropped
        }
        return new Result(out, redactions);
    }
}
