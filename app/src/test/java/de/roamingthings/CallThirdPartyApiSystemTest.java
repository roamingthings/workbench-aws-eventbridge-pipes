package de.roamingthings;

import de.roamingthings.person.Person;
import de.roamingthings.person.PersonRepository;
import io.micronaut.context.annotation.Property;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.instancio.Instancio;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Export;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

import java.util.Map;

import static de.roamingthings.InfraConstants.EVENT_BUS_NAME_EXPORT_NAME;
import static de.roamingthings.InfraConstants.PERSON_TABLE_NAME_EXPORT_NAME;


@Tag("system")
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CallThirdPartyApiSystemTest implements TestPropertyProvider {

    static EventBridgeClient eventBridgeClient;

    @Inject
    PersonRepository personRepository;

    @Property(name = "eventBusName")
    String eventBusName;

    @BeforeAll
    static void setup() {
        eventBridgeClient = EventBridgeClient.builder().build();
    }

    @AfterAll
    static void teardown() {
        eventBridgeClient.close();
    }

    @Test
    void should_call_third_party_api() {
        var person = Instancio.of(Person.class).create();
        System.out.println("Person for test: " + person);

        personRepository.save(person);

        eventBridgeClient.putEvents(request -> request
                .entries(
                        PutEventsRequestEntry.builder()
                                .eventBusName(eventBusName)
                                .source("de.roamingthings.person")
                                .detailType("PersonCreated")
                                .detail("{ \"id\": \"" + person.id() + "\" }")
                                .build()
                )
        );
    }

    @Override
    public @NonNull Map<String, String> getProperties() {
        try (var cloudFormationClient = CloudFormationClient.builder().build()){
            var exports = cloudFormationClient.listExportsPaginator().exports();
            var personTableName = fetchValue(exports, PERSON_TABLE_NAME_EXPORT_NAME);
            var eventBusName = fetchValue(exports, EVENT_BUS_NAME_EXPORT_NAME);
            return Map.of(
                    "personTableName", personTableName,
                    "eventBusName", eventBusName
            );
        }
    }

    private static String fetchValue(SdkIterable<Export> exports, String exportName) {
        return exports.stream().filter(export -> export.name().equals(exportName)).findFirst()
                .map(Export::value)
                .orElseThrow(() -> new IllegalStateException("Export '%s' not found".formatted(exportName)));
    }
}
