package io.vertesia;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class StudioResponseFixtures {
    private StudioResponseFixtures() {}

    static String read(String name) {
        String directory = System.getenv("VERTESIA_STUDIO_OPENAPI_FIXTURES");
        assumeTrue(
                directory != null && !directory.isBlank(),
                "Studio fixtures are supplied by SDK sync");
        try {
            return Files.readString(Path.of(directory, name));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read Studio fixture " + name, e);
        }
    }
}
