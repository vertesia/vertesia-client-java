package io.vertesia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vertesia.model.BillingMethod;
import io.vertesia.model.StripeBillingDisabled;
import io.vertesia.model.StripeBillingEnabled;
import io.vertesia.model.StripeBillingStatusResponse;
import org.junit.jupiter.api.Test;

/**
 * Deserializes the exact payloads GET /account/stripe-billing-status returns.
 *
 * Offline by design — no environment, no credentials. What it checks is a property of the
 * generated code, so running it against a live server would only add flakiness. It belongs with
 * the generated client rather than the schema tests because the failure it guards against is
 * invisible from TypeScript: a schema that validates fine can still generate a client that cannot
 * tell one variant from another.
 *
 * StripeBillingStatusResponse is published as a oneOf with a `status` discriminator. When it was a
 * single flat object with portal_url and reason both optional, this class had no way to express
 * which one was populated, and callers guessed by null-checking.
 */
class StripeUnionDeserializationTest {

    private static final String ENABLED =
            "{\"status\":\"enabled\",\"billing_method\":\"stripe\","
                    + "\"portal_url\":\"https://billing.stripe.com/p/session_1\"}";
    private static final String DISABLED =
            "{\"status\":\"disabled\",\"billing_method\":null,\"reason\":\"No billing method configured\"}";

    @Test
    void accountKeyBillingStatusDoesNotContainAPortal() {
        String body = "{\"status\":\"enabled\",\"billing_method\":\"stripe\",\"portal_url\":null}";
        StripeBillingStatusResponse response =
                JSON.getGson().fromJson(body, StripeBillingStatusResponse.class);
        StripeBillingEnabled enabled =
                assertInstanceOf(StripeBillingEnabled.class, response.getActualInstance());
        assertNull(enabled.getPortalUrl());
    }

    @Test
    void deserializesTheEnabledBranchIntoItsOwnType() {
        StripeBillingStatusResponse response =
                JSON.getGson().fromJson(ENABLED, StripeBillingStatusResponse.class);

        Object actual = response.getActualInstance();
        assertInstanceOf(StripeBillingEnabled.class, actual);
        StripeBillingEnabled enabled = (StripeBillingEnabled) actual;
        assertEquals(StripeBillingEnabled.StatusEnum.ENABLED, enabled.getStatus());
        assertEquals("https://billing.stripe.com/p/session_1", enabled.getPortalUrl());
    }

    @Test
    void deserializesTheDisabledBranchWithANullBillingMethod() {
        StripeBillingStatusResponse response =
                JSON.getGson().fromJson(DISABLED, StripeBillingStatusResponse.class);

        Object actual = response.getActualInstance();
        assertInstanceOf(StripeBillingDisabled.class, actual);
        StripeBillingDisabled disabled = (StripeBillingDisabled) actual;
        assertEquals(StripeBillingDisabled.StatusEnum.DISABLED, disabled.getStatus());
        assertEquals("No billing method configured", disabled.getReason());
        assertNull(disabled.getBillingMethod());
    }

    @Test
    void roundTripsWithoutLosingTheBranch() {
        StripeBillingStatusResponse response =
                JSON.getGson().fromJson(ENABLED, StripeBillingStatusResponse.class);
        String serialized = JSON.getGson().toJson(response);
        StripeBillingStatusResponse again =
                JSON.getGson().fromJson(serialized, StripeBillingStatusResponse.class);
        assertInstanceOf(StripeBillingEnabled.class, again.getActualInstance());
    }

    /**
     * Payloads whose discriminator contradicts their body. Unlike the mixed payload above, each of
     * these matches exactly one branch structurally, so accepting them would mean the client picked
     * a branch without consulting the discriminator it was given.
     *
     * The Java generator turns the `const` on `status` into a StatusEnum with a Gson adapter, so it
     * rejects them. The Go client does NOT — see the note in the Go test; that difference is a
     * generator property, not a spec one.
     */
    @Test
    void rejectsAPayloadWhoseDiscriminatorContradictsItsBody() {
        String disabledWithPortal =
                "{\"status\":\"disabled\",\"billing_method\":\"stripe\",\"portal_url\":\"https://x\"}";
        assertThrows(
                Exception.class,
                () ->
                        JSON.getGson()
                                .fromJson(disabledWithPortal, StripeBillingStatusResponse.class));

        String enabledWithReason =
                "{\"status\":\"enabled\",\"billing_method\":\"stripe\",\"reason\":\"nope\"}";
        assertThrows(
                Exception.class,
                () ->
                        JSON.getGson()
                                .fromJson(enabledWithReason, StripeBillingStatusResponse.class));

        String neitherBranchProperty = "{\"status\":\"enabled\",\"billing_method\":\"stripe\"}";
        assertThrows(
                Exception.class,
                () ->
                        JSON.getGson()
                                .fromJson(
                                        neitherBranchProperty, StripeBillingStatusResponse.class));
    }

    @Test
    void billingMethodIsOneSharedEnumType() {
        // The schemas give BillingMethod/AccountType/QuotaTier an `id` so they hoist into named
        // components. Inline enums would generate an anonymous type per property instead, and
        // every account field carrying a billing method would have an incompatible one.
        assertEquals("stripe", BillingMethod.STRIPE.getValue());
    }
}
