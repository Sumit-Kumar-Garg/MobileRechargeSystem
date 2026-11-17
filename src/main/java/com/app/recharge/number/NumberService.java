package com.app.recharge.number;

import com.app.recharge.common.BusinessException;
import com.app.recharge.common.ErrorCode;
import com.app.recharge.crypto.PiiCrypto;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Numbers a user may recharge. Ownership is the core authorization rule of the whole service:
 * every order is tied to a number the caller has linked, and "not yours" looks exactly like "does not exist".
 * Production note: linking must be verified (OTP to the number / SIM check); that is out of scope here.
 */
@Service
public class NumberService {

    private final SubscriberNumberRepository numbers;
    private final PiiCrypto crypto;
    private final NumberProperties props;
    private final Clock clock;

    public NumberService(SubscriberNumberRepository numbers, PiiCrypto crypto, NumberProperties props, Clock clock) {
        this.numbers = numbers;
        this.crypto = crypto;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Links a phone number to the user: normalizes it, returns the existing record if already linked, enforces the
     * per-user limit (app.numbers.max-per-user), and stores it encrypted, hashed and masked. Safe under concurrent calls.
     */
    public NumberView link(UUID userId, String rawMsisdn) {
        String msisdn = Msisdn.normalize(rawMsisdn);
        String hash = crypto.hash("msisdn:" + msisdn);

        var existing = numbers.findByUserIdAndMsisdnHash(userId, hash);
        if (existing.isPresent()) {
            return NumberView.from(existing.get());
        }
        if (numbers.countByUserId(userId) >= props.maxPerUser()) {
            throw new BusinessException(ErrorCode.NUMBER_LIMIT_EXCEEDED,
                    "You can link at most " + props.maxPerUser() + (props.maxPerUser() == 1 ? " number" : " numbers"));
        }
        String market = Msisdn.market(msisdn);
        SubscriberNumber created = new SubscriberNumber(
                userId, crypto.encrypt(msisdn), hash, Msisdn.mask(msisdn), market, clock.instant());
        try {
            return NumberView.from(numbers.saveAndFlush(created));
        } catch (DataIntegrityViolationException race) {
            return NumberView.from(numbers.findByUserIdAndMsisdnHash(userId, hash).orElseThrow(() -> race));
        }
    }

    /** The user's linked numbers, oldest first. */
    public List<NumberView> list(UUID userId) {
        return numbers.findByUserIdOrderByCreatedAtAsc(userId).stream().map(NumberView::from).toList();
    }

    /**
     * Returns the number if it belongs to the user; otherwise RESOURCE_NOT_FOUND, so other users' numbers look
     * nonexistent.
     */
    public SubscriberNumber requireOwned(UUID userId, UUID numberId) {
        return numbers.findByIdAndUserId(numberId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Number not found"));
    }

    /** Internal use only (provisioning, SMS). Never expose the result through an API. */
    public String plainMsisdn(UUID numberId) {
        SubscriberNumber n = numbers.findById(numberId)
                .orElseThrow(() -> new IllegalStateException("Unknown number " + numberId));
        return crypto.decrypt(n.getMsisdnEnc());
    }
}
