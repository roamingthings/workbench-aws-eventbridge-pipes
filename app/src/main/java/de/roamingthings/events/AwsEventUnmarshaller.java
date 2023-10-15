package de.roamingthings.events;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.type.Argument;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

@Singleton
@Introspected
@RequiredArgsConstructor
public class AwsEventUnmarshaller {

    private final ObjectMapper objectMapper;

    public <T> AwsEvent<T> unmarshall(String json, Class<T> detailType) {
        try {
            return objectMapper.readValue(json, Argument.of(AwsEvent.class, detailType));
        } catch (Exception e) {
            throw new UnmarshallingException(e);
        }
    }
}
