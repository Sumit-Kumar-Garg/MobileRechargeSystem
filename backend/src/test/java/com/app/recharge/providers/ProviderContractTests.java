package com.app.recharge.providers;

import com.app.recharge.catalog.spi.PlanProvider;
import com.app.recharge.catalog.spi.PlanProviderContract;
import com.app.recharge.payment.MockPspClient;
import com.app.recharge.payment.spi.PaymentProviderContract;
import com.app.recharge.payment.spi.PspClient;
import com.app.recharge.providers.altpay.AltPayClient;
import com.app.recharge.providers.travelsim.TravelSimFulfilment;
import com.app.recharge.providers.travelsim.TravelSimPlanProvider;
import com.app.recharge.provisioning.MockOcsClient;
import com.app.recharge.provisioning.spi.FulfilmentProviderContract;
import com.app.recharge.provisioning.spi.OcsClient;
import org.junit.jupiter.api.Nested;

/** Every provider adapter in this deployment, run against the contract kit of the port it implements. */
class ProviderContractTests {

    @Nested
    class MockPay extends PaymentProviderContract {
        private final PspClient client = new MockPspClient();
        @Override protected PspClient provider() { return client; }
        @Override protected String approvedToken() { return "tok_success"; }
        @Override protected String declinedToken() { return "tok_decline"; }
    }

    @Nested
    class AltPay extends PaymentProviderContract {
        private final PspClient client = new AltPayClient();
        @Override protected PspClient provider() { return client; }
        @Override protected String approvedToken() { return "tok_success"; }
        @Override protected String declinedToken() { return "tok_decline"; }
    }

    @Nested
    class OperatorFulfilment extends FulfilmentProviderContract {
        private final OcsClient client = new MockOcsClient();
        @Override protected OcsClient provider() { return client; }
    }

    @Nested
    class TravelSimFulfilmentContract extends FulfilmentProviderContract {
        private final OcsClient client = new TravelSimFulfilment();
        @Override protected OcsClient provider() { return client; }
    }

    @Nested
    class TravelSimPlans extends PlanProviderContract {
        private final PlanProvider provider = new TravelSimPlanProvider();
        @Override protected PlanProvider provider() { return provider; }
    }
}
