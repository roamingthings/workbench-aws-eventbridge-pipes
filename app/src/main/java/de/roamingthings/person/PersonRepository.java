package de.roamingthings.person;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Introspected;
import jakarta.inject.Singleton;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@Singleton
@Introspected
public class PersonRepository {

    public static final int EXPIRE_IN_SECONDS = 120;
    private final String tableName;
    private final DynamoDbClient dynamoDbClient;
    private final Clock systemClock;

    public PersonRepository(
            @Value("${personTableName}") String tableName,
            DynamoDbClient dynamoDbClient,
            Clock systemClock) {
        this.tableName = tableName;
        this.dynamoDbClient = dynamoDbClient;
        this.systemClock = systemClock;
    }

    public Optional<Person> findById(String id) {
        var item = dynamoDbClient.getItem(request -> request
                .tableName(tableName)
                .key(Map.of(
                        "PK", AttributeValue.fromS("person#" + id),
                        "SK", AttributeValue.fromS("DETAILS")
                )));
        if (!item.hasItem()) {
            return Optional.empty();
        } else {
            var itemMap = item.item();
            return Optional.of(new Person(
                    id,
                    itemMap.get("firstName").s(),
                    itemMap.get("lastName").s()
            ));
        }
    }

    public void save(Person person) {
        dynamoDbClient.putItem(request -> request
                .tableName(tableName)
                .item(Map.of(
                        "PK", AttributeValue.fromS("person#" + person.id()),
                        "SK", AttributeValue.fromS("DETAILS"),
                        "firstName", AttributeValue.fromS(person.firstName()),
                        "lastName", AttributeValue.fromS(person.lastName()),
                        "expiresAt", AttributeValue.fromN(calculateExpirationEpochSecs())
                )));
    }

    private String calculateExpirationEpochSecs() {
        return String.valueOf(Instant.now(systemClock).plusSeconds(EXPIRE_IN_SECONDS).getEpochSecond());
    }
}
