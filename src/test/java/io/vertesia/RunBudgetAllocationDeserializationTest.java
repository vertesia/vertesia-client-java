package io.vertesia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.vertesia.model.AllocateAgentRunBudgetPayload;
import io.vertesia.model.AllocateAgentRunBudgetPayloadAnyOf;
import io.vertesia.model.AllocateAgentRunBudgetPayloadAnyOf1;
import io.vertesia.model.ProcessBudgetState;
import org.junit.jupiter.api.Test;

class RunBudgetAllocationDeserializationTest {
    @Test
    void acceptsEitherAllowance() throws Exception {
        assertInstanceOf(
                AllocateAgentRunBudgetPayloadAnyOf.class,
                AllocateAgentRunBudgetPayload.fromJson("{\"additional_tokens\":1000}")
                        .getActualInstance());
        assertInstanceOf(
                AllocateAgentRunBudgetPayloadAnyOf1.class,
                AllocateAgentRunBudgetPayload.fromJson("{\"additional_usd\":0.25}")
                        .getActualInstance());
    }

    @Test
    void preservesDollarProcessStatusAndLegacyTokenStatus() throws Exception {
        ProcessBudgetState state =
                ProcessBudgetState.fromJson(
                        """
            {"mode":"dollar","limit_tokens":1000,"used_units":12,"exhausted":false,
             "limit_usd":1,"reported_usd":0.1,"estimated_usd":0.2,
             "accounting_status":"incomplete","unmeasured_calls":1}
            """);
        assertEquals("dollar", state.getMode().getValue());
        assertEquals(1.0, state.getLimitUsd().doubleValue());
        assertEquals(0.1, state.getReportedUsd().doubleValue());
        assertEquals(0.2, state.getEstimatedUsd().doubleValue());
        assertEquals("incomplete", state.getAccountingStatus().getValue());
        ProcessBudgetState legacy =
                ProcessBudgetState.fromJson(
                        "{\"limit_tokens\":1000,\"used_units\":250,\"exhausted\":false}");
        assertNull(legacy.getMode());
        assertNull(legacy.getLimitUsd());
    }
}
