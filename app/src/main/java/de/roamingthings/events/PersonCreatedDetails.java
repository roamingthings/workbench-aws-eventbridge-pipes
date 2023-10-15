package de.roamingthings.events;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record PersonCreatedDetails(String id) {
}
