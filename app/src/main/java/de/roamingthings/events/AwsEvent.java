package de.roamingthings.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.serde.annotation.Serdeable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Serdeable
@NoArgsConstructor
@AllArgsConstructor
public class AwsEvent<T> {

    @JsonProperty("detail")
    private T detail;

    @JsonProperty("detail-type")
    private String detailType;

    @JsonProperty("resources")
    private List<String> resources;

    @JsonProperty("id")
    private String id;

    @JsonProperty("source")
    private String source;

    @JsonProperty("time")
    private Instant time;

    @JsonProperty("region")
    private String region;

    @JsonProperty("version")
    private String version;

    @JsonProperty("account")
    private String account;
}
