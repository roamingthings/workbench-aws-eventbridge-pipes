package de.roamingthings;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import de.roamingthings.person.Person;
import de.roamingthings.person.PersonRepository;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

@MicronautTest
class ThirdPartyApiRequestEnricherTest {

    static final PersonRepository personRepositoryMock = mock(PersonRepository.class);
    static final String PERSON_ID = "12345678-1234-1234-1234-123456789012";
    static final String FIRST_NAME = "John";
    static final String LAST_NAME = "Doe";

    @Inject
    ApplicationContext applicationContext;

    ThirdPartyApiRequestEnricherHandler handler;

    @BeforeEach
    void setup() {
        reset(personRepositoryMock);
        handler = new ThirdPartyApiRequestEnricherHandler(applicationContext);
    }

    @Test
    void should_return_an_enriched_event() {
        repositoryWillReturnAPerson();
        var message = new SQSEvent.SQSMessage();
        message.setBody("""
                {
                  "version": "0",
                  "id": "a7e4d8b5-0f3d-4e6d-9cdc-2b2c0e0fe83c",
                  "detail-type": "PersonCreated",
                  "source": "de.roamingthings.person",
                  "account": "123456789012",
                  "time": "2021-08-01T12:34:56Z",
                  "region": "eu-central-1",
                  "resources": [],
                  "detail": {
                    "id": "%s"
                  }
                }
                """.formatted(PERSON_ID));
        List<ThirdPartyApiRequestDetails> details = handler.execute(List.of(message));

        assertThat(details).hasSize(1);
        assertThat(details.get(0).id()).isEqualTo(PERSON_ID);
        assertThat(details.get(0).firstName()).isEqualTo(FIRST_NAME);
        assertThat(details.get(0).lastName()).isEqualTo(LAST_NAME);
    }

    private void repositoryWillReturnAPerson() {
        var person = new Person(PERSON_ID, FIRST_NAME, LAST_NAME);
        doReturn(Optional.of(person)).when(personRepositoryMock).findById(PERSON_ID);
    }

    @Replaces(PersonRepository.class)
    @MockBean(PersonRepository.class)
    public PersonRepository personRepository() {
        return personRepositoryMock;
    }
}
