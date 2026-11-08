package io.vertesia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vertesia.model.AudioResult;
import io.vertesia.model.CompletionResult;
import org.junit.jupiter.api.Test;

class AudioResultDeserializationTest {
    private static final String AUDIO =
            """
            {"type":"audio","value":"gs://bucket/speech.wav","mime_type":"audio/wav","container":"wav"}
            """;

    @Test
    void roundTripsAudioThroughTheCompletionUnion() {
        CompletionResult result = JSON.getGson().fromJson(AUDIO, CompletionResult.class);
        AudioResult audio = assertInstanceOf(AudioResult.class, result.getActualInstance());
        assertEquals("gs://bucket/speech.wav", audio.getValue());
        assertEquals("audio/wav", audio.getMimeType());
        CompletionResult again =
                JSON.getGson().fromJson(JSON.getGson().toJson(result), CompletionResult.class);
        assertInstanceOf(AudioResult.class, again.getActualInstance());
    }

    @Test
    void rejectsAudioWithoutRequiredMetadata() {
        assertThrows(
                Exception.class,
                () ->
                        JSON.getGson()
                                .fromJson(
                                        "{\"type\":\"audio\",\"value\":\"gs://bucket/speech.wav\"}",
                                        AudioResult.class));
    }
}
