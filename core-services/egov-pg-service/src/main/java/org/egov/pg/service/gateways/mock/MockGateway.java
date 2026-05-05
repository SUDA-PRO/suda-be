package org.egov.pg.service.gateways.mock;

import lombok.extern.slf4j.Slf4j;
import org.egov.pg.constants.PgConstants;
import org.egov.pg.models.Transaction;
import org.egov.pg.service.Gateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Map;

/**
 * Mock Gateway for development/testing — auto-approves all payments.
 * Enable by setting mock.active=true in application.properties.
 */
@Component
@Slf4j
public class MockGateway implements Gateway {

    private static final String GATEWAY_NAME = "MOCK";
    private static final String TXN_ID_KEY = "mock_txnid";

    private final boolean ACTIVE;

    @Autowired
    public MockGateway(Environment environment) {
        ACTIVE = Boolean.parseBoolean(environment.getProperty("mock.active", "false"));
    }

    @Override
    public URI generateRedirectURI(Transaction transaction) {
        log.info("MockGateway: auto-approving transaction {}", transaction.getTxnId());
        try {
            // Redirect directly back to the callback URL with success params
            String callbackUrl = transaction.getCallbackUrl();
            String separator = callbackUrl.contains("?") ? "&" : "?";
            return URI.create(callbackUrl + separator + TXN_ID_KEY + "=" + transaction.getTxnId());
        } catch (Exception e) {
            log.error("MockGateway: error generating redirect URI", e);
            return URI.create(transaction.getCallbackUrl());
        }
    }

    @Override
    public Transaction fetchStatus(Transaction currentStatus, Map<String, String> params) {
        log.info("MockGateway: returning SUCCESS for transaction {}", currentStatus.getTxnId());
        currentStatus.setTxnStatus(Transaction.TxnStatusEnum.SUCCESS);
        currentStatus.setTxnStatusMsg(PgConstants.TXN_SUCCESS);
        currentStatus.setGatewayPaymentMode("MOCK");
        currentStatus.setGatewayStatusCode("0000");
        currentStatus.setGatewayStatusMsg("Mock payment successful");
        return currentStatus;
    }

    @Override
    public boolean isActive() {
        return ACTIVE;
    }

    @Override
    public String gatewayName() {
        return GATEWAY_NAME;
    }

    @Override
    public String transactionIdKeyInResponse() {
        return TXN_ID_KEY;
    }

    @Override
    public String generateRedirectFormData(Transaction transaction) {
        return "";
    }
}
