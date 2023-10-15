package de.roamingthings;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record ThirdPartyApiRequestDetails(String id, String firstName, String lastName) {
}
