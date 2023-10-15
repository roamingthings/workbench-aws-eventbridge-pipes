package de.roamingthings;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import de.roamingthings.events.AwsEvent;
import de.roamingthings.events.AwsEventUnmarshaller;
import de.roamingthings.events.PersonCreatedDetails;
import de.roamingthings.person.PersonRepository;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.function.aws.MicronautRequestHandler;
import jakarta.inject.Inject;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

@Slf4j
@Introspected
@NoArgsConstructor
public class ThirdPartyApiRequestEnricherHandler extends MicronautRequestHandler<List<SQSEvent.SQSMessage>, List<ThirdPartyApiRequestDetails>> {

    @Inject
    private AwsEventUnmarshaller awsEventUnmarshaller;
    @Inject
    private PersonRepository personRepository;

    public ThirdPartyApiRequestEnricherHandler(ApplicationContext applicationContext) {
        super(applicationContext);
    }

    @Override
    protected ApplicationContextBuilder newApplicationContextBuilder() {
        return super.newApplicationContextBuilder()
                .eagerInitSingletons(true);
    }

    @Override
    public List<ThirdPartyApiRequestDetails> execute(List<SQSEvent.SQSMessage> input) {
        return Optional.of(input)
                .orElseGet(List::of)
                .stream()
                .map(this::processMessage)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    private Optional<ThirdPartyApiRequestDetails> processMessage(SQSEvent.SQSMessage message) {
        try {
            return Optional.of(message)
                    .map(this::unmarshallEvent)
                    .map(AwsEvent::getDetail)
                    .map(PersonCreatedDetails::id)
                    .flatMap(personRepository::findById)
                    .map(person -> new ThirdPartyApiRequestDetails(person.id(), person.firstName(), person.lastName()));
        } catch (Exception e) {
            log.error("Error processing message: {}", message.getBody(), e);
            throw new MessageProcessingFailedException(e);
        }
    }

    private AwsEvent<PersonCreatedDetails> unmarshallEvent(SQSEvent.SQSMessage message) {
        return awsEventUnmarshaller.unmarshall(message.getBody(), PersonCreatedDetails.class);
    }
}
