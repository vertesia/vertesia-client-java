package io.vertesia.gson;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;

/** Writes required nullable fields without changing serialization of absent optional fields. */
public final class RequiredNullableTypeAdapterFactory implements TypeAdapterFactory {
    @Override
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
        TypeAdapter<T> delegate = gson.getAdapter(type);
        return new TypeAdapter<T>() {
            @Override
            public void write(JsonWriter out, T value) throws IOException {
                if (value != null) {
                    delegate.write(out, value);
                    return;
                }
                boolean previousSerializeNulls = out.getSerializeNulls();
                out.setSerializeNulls(true);
                try {
                    out.nullValue();
                } finally {
                    out.setSerializeNulls(previousSerializeNulls);
                }
            }

            @Override
            public T read(JsonReader in) throws IOException {
                return delegate.read(in);
            }
        };
    }
}
