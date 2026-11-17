package com.app.recharge.number;

import com.app.recharge.common.BusinessException;
import com.app.recharge.common.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Phone number helpers. MSISDNs are always handled in E.164 form (+491701234567). */
public final class Msisdn {

    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");
    private static final Map<String, String> MARKETS = new LinkedHashMap<>();

    static {
        MARKETS.put("+420", "CZ");
        MARKETS.put("+49", "DE");
        MARKETS.put("+48", "PL");
        MARKETS.put("+43", "AT");
        MARKETS.put("+31", "NL");
    }

    private Msisdn() { }

    /**
     * Strips spaces, dashes, dots and brackets and checks the result is E.164 (+ and 8 to 15 digits). Throws
     * REQUEST_INVALID otherwise.
     */
    public static String normalize(String raw) {
        String cleaned = raw == null ? "" : raw.replaceAll("[\\s\\-().]", "");
        if (!E164.matcher(cleaned).matches()) {
            throw new BusinessException(ErrorCode.REQUEST_INVALID, "Phone number must be in international format");
        }
        return cleaned;
    }

    /**
     * The market (country code such as DE) of a normalized number, from its dialling prefix. Throws REQUEST_INVALID for
     * unsupported countries.
     */
    public static String market(String e164) {
        for (Map.Entry<String, String> e : MARKETS.entrySet()) {
            if (e164.startsWith(e.getKey())) {
                return e.getValue();
            }
        }
        throw new BusinessException(ErrorCode.REQUEST_INVALID, "Numbers from this country are not supported");
    }

    /** "+491701234567" becomes "+49 *** ***4567". Safe for logs, push text and API responses. */
    public static String mask(String e164) {
        int n = e164.length();
        return e164.substring(0, 3) + " *** ***" + e164.substring(n - 4);
    }
}
