package io.vertesia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import io.vertesia.model.User;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Checks shared user responses and account member lists, including required timestamps. */
class UserDeserializationTest {

    /** Every optional field populated, so each generated accessor is exercised. */
    private static String populated() {
        return StudioResponseFixtures.read("user-populated.json");
    }

    /**
     * A user Mongo holds with neither an email nor timestamps. The mapper supplies all three, which
     * is what makes this response deserializable at all — the pre-migration handler omitted them.
     */
    private static String legacyNormalized() {
        return StudioResponseFixtures.read("user-legacy-normalized.json");
    }

    private static final Type USER_LIST = new TypeToken<List<User>>() {}.getType();

    @Test
    void deserializesAFullyPopulatedUser() {
        User user = JSON.getGson().fromJson(populated(), User.class);

        assertEquals("68b1779130afe5403a1589ba", user.getId());
        assertEquals("someone@acme.com", user.getEmail());
        assertEquals("Someone", user.getName());
        assertEquals(User.SourceEnum.SCIM, user.getSource());
        assertEquals(new BigDecimal(3), user.getClearance());
        assertEquals(List.of("eu"), user.getCompartments());
        assertEquals(List.of("internal"), user.getAnnotations());
        assertEquals("+33100000000", user.getPhone());
    }

    @Test
    void mapsTheTimestampsToOffsetDateTime() {
        // The point of `format: date-time` on the schema. Without it the generator emits a plain
        // String and every caller parses the timestamp itself.
        User user = JSON.getGson().fromJson(populated(), User.class);

        OffsetDateTime created = user.getCreatedAt();
        assertNotNull(created);
        assertEquals(OffsetDateTime.of(2026, 7, 30, 10, 0, 0, 0, ZoneOffset.UTC), created);
        assertEquals(
                OffsetDateTime.of(2026, 7, 30, 11, 0, 0, 0, ZoneOffset.UTC), user.getUpdatedAt());
    }

    @Test
    void deserializesTheNormalizedLegacyResponse() {
        // The three normalizations the mapper performs, seen from the client: an empty email
        // instead of an absent one, and timestamps derived from the ObjectId.
        User user = JSON.getGson().fromJson(legacyNormalized(), User.class);

        assertEquals("", user.getEmail());
        assertEquals(
                OffsetDateTime.of(2025, 8, 29, 9, 49, 5, 0, ZoneOffset.UTC), user.getCreatedAt());
        assertEquals(
                OffsetDateTime.of(2025, 8, 29, 9, 49, 5, 0, ZoneOffset.UTC), user.getUpdatedAt());
        // Absent optionals stay null rather than becoming empty strings.
        assertNull(user.getPhone());
        assertNull(user.getPicture());
    }

    @Test
    void rejectsAResponseMissingARequiredTimestamp() {
        // `openapiRequiredFields` is validated on deserialization, so this is a thrown exception in
        // a Java caller rather than a null field. It is the reason declaring created_at/updated_at
        // obliges the server to send them on every path, including each member of a listing.
        assertTrue(User.openapiRequiredFields.contains("created_at"));
        assertTrue(User.openapiRequiredFields.contains("updated_at"));

        // Removed through the parser rather than by string surgery, so how the fixture file happens
        // to be formatted cannot silently turn this into a no-op.
        JsonObject withoutCreatedAt = JsonParser.parseString(populated()).getAsJsonObject();
        withoutCreatedAt.remove("created_at");
        assertThrows(Exception.class, () -> JSON.getGson().fromJson(withoutCreatedAt, User.class));
    }

    @Test
    void deserializesTheAccountMembersListing() {
        // UserArray items $ref User, so the listing is checked member by member. A mixed page —
        // one current document, one normalized legacy document — is the realistic case.
        String members = "[" + populated() + "," + legacyNormalized() + "]";
        List<User> users = JSON.getGson().fromJson(members, USER_LIST);

        assertEquals(2, users.size());
        assertEquals("someone@acme.com", users.get(0).getEmail());
        assertEquals("", users.get(1).getEmail());
        assertNotNull(users.get(0).getCreatedAt());
        assertNotNull(users.get(1).getCreatedAt());
    }

    @Test
    void failsTheWholeListingWhenOneMemberViolatesTheContract() {
        // What GET /account/members would have done before it was mapped through toUserResponse:
        // a single legacy member without timestamps takes the entire response down.
        String invalidMember =
                "{\"id\":\"68b1779130afe5403a1589bc\",\"email\":\"x@acme.com\",\"name\":\"NoStamps\"}";
        String members = "[" + populated() + "," + invalidMember + "]";
        assertThrows(
                Exception.class, () -> JSON.getGson().<List<User>>fromJson(members, USER_LIST));
    }
}
