package de.roamingthings.cdk.aws.pipes;

import lombok.Builder;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.services.events.EventPattern;
import software.amazon.awscdk.services.events.IApiDestination;
import software.amazon.awscdk.services.events.IEventBus;
import software.amazon.awscdk.services.events.IRule;
import software.amazon.awscdk.services.events.Rule;
import software.amazon.awscdk.services.events.targets.SqsQueue;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyDocument;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.IFunction;
import software.amazon.awscdk.services.pipes.CfnPipe;
import software.amazon.awscdk.services.sqs.DeadLetterQueue;
import software.amazon.awscdk.services.sqs.IQueue;
import software.amazon.awscdk.services.sqs.Queue;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class EnrichedEventApiDestinationPipe extends Construct {

    private static final Number DEFAULT_SOURCE_BATCH_SIZE = 1;
    private static final Number DEFAULT_MAXIMUM_BATCHING_WINDOW_IN_SECONDS = 6;

    private IQueue sourceQueue;
    private IQueue dlq;
    private IRule rule;

    private CfnPipe pipe;

    public EnrichedEventApiDestinationPipe(Construct scope, String id, EnrichedEventApiDestinationPipeProps props) {
        super(scope, id);
        Objects.requireNonNull(props.sourceEventBus, "'sourceEventBus' must be provided");
        Objects.requireNonNull(props.eventPattern, "'eventPattern' must be provided");
        Objects.requireNonNull(props.enrichmentFunction, "'enrichment' must be provided");
        Objects.requireNonNull(props.apiDestination, "'apiDestination' must be provided");
        Objects.requireNonNull(props.removalPolicy, "'removalPolicy' must be provided");
        Objects.requireNonNull(props.maxRetryCount, "'maxRetryCount' must be provided");
        Objects.requireNonNull(props.visibilityTimeout, "'visibilityTimeout' must be provided");
        Objects.requireNonNull(props.retryPeriod, "'retryPeriod' must be provided");
        Objects.requireNonNull(props.endpointUrl, "'endpointUrl' must be provided");

        createDlq(props);
        createSourceQueue(props);
        createRule(props);
        createPipe(props);
    }

    public IQueue getSourceQueue() {
        return sourceQueue;
    }

    public IQueue getDlq() {
        return dlq;
    }

    public IRule getRule() {
        return rule;
    }

    public CfnPipe getPipe() {
        return pipe;
    }

    private void createSourceQueue(EnrichedEventApiDestinationPipeProps props) {
        sourceQueue = Queue.Builder.create(this, "SourceQueue")
                .visibilityTimeout(props.visibilityTimeout)
                .retentionPeriod(props.retryPeriod)
                .removalPolicy(props.removalPolicy)
                .deadLetterQueue(DeadLetterQueue.builder()
                        .maxReceiveCount(props.maxRetryCount)
                        .queue(dlq)
                        .build())
                .build();
    }

    private void createDlq(EnrichedEventApiDestinationPipeProps props) {
        dlq = Queue.Builder.create(this, "Dlq")
                .removalPolicy(props.removalPolicy)
                .build();
    }

    private void createRule(EnrichedEventApiDestinationPipeProps props) {
        rule = Rule.Builder.create(this, "Rule")
                .eventBus(props.sourceEventBus)
                .eventPattern(props.eventPattern)
                .targets(List.of(SqsQueue.Builder.create(sourceQueue).build()))
                .build();
    }

    private void createPipe(EnrichedEventApiDestinationPipeProps props) {
        var sourceMaximumBatchingWindowInSeconds = Objects.requireNonNullElse(props.sourceMaximumBatchingWindowInSeconds, DEFAULT_MAXIMUM_BATCHING_WINDOW_IN_SECONDS);
        var targetHttpParameters = Objects.requireNonNullElse(props.targetHttpParameters, CfnPipe.PipeTargetHttpParametersProperty.builder().build());
        var pipeRole = createPipeRole(props);
        var sourceBatchSize = Objects.requireNonNullElse(props.sourceBatchSize, DEFAULT_SOURCE_BATCH_SIZE);
        pipe = CfnPipe.Builder.create(this, "Pipe")
                .source(sourceQueue.getQueueArn())
                .sourceParameters(CfnPipe.PipeSourceParametersProperty.builder()
                        .sqsQueueParameters(CfnPipe.PipeSourceSqsQueueParametersProperty.builder()
                                .batchSize(sourceBatchSize)
                                .maximumBatchingWindowInSeconds(sourceMaximumBatchingWindowInSeconds)
                                .build())
                        .build()
                )
                .enrichment(props.enrichmentFunction.getFunctionArn())
                .enrichmentParameters(CfnPipe.PipeEnrichmentParametersProperty.builder()
                        .inputTemplate(props.enrichmentInputTemplate)
                        .build())
                .target(props.apiDestination.getApiDestinationArn())
                .targetParameters(CfnPipe.PipeTargetParametersProperty.builder()
                        .httpParameters(targetHttpParameters)
                        .inputTemplate(props.targetInputTemplate)
                        .build())
                .roleArn(pipeRole.getRoleArn())
                .build();
    }

    private Role createPipeRole(EnrichedEventApiDestinationPipeProps props) {
        var sourcePolicy = createSourcePolicy(sourceQueue, dlq);
        var enrichmentPolicy = createEnrichmentPolicy(props.enrichmentFunction);
        var targetPolicy = createTargetPolicy(props.apiDestination);

        return Role.Builder.create(this, "Role")
                .inlinePolicies(Map.of(
                        "sourcePolicy", sourcePolicy,
                        "enrichmentPolicy", enrichmentPolicy,
                        "targetPolicy", targetPolicy
                ))
                .assumedBy(new ServicePrincipal("pipes.amazonaws.com"))
                .build();
    }

    private static PolicyDocument createTargetPolicy(IApiDestination apiDestinationTarget) {
        var apiDestinationArn = apiDestinationTarget.getApiDestinationArn();
        return PolicyDocument.Builder.create()
                .statements(List.of(
                        PolicyStatement.Builder.create()
                                .effect(Effect.ALLOW)
                                .actions(List.of("events:InvokeApiDestination"))
                                .resources(List.of(apiDestinationArn))
                                .build()
                ))
                .build();
    }

    private static PolicyDocument createEnrichmentPolicy(IFunction enrichment) {
        var functionArn = enrichment.getFunctionArn();
        return PolicyDocument.Builder.create()
                .statements(List.of(
                        PolicyStatement.Builder.create()
                                .effect(Effect.ALLOW)
                                .actions(List.of("lambda:InvokeFunction"))
                                .resources(List.of(functionArn))
                                .build()
                ))
                .build();
    }

    private static PolicyDocument createSourcePolicy(IQueue queue, IQueue dlq) {
        var queueArn = queue.getQueueArn();
        var dlqArn = dlq.getQueueArn();
        return PolicyDocument.Builder.create()
                .statements(List.of(
                        PolicyStatement.Builder.create()
                                .effect(Effect.ALLOW)
                                .actions(List.of("sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"))
                                .resources(List.of(queueArn, dlqArn))
                                .build()
                ))
                .build();
    }

    @Builder
    public static class EnrichedEventApiDestinationPipeProps {
        /**
         * The event bus that is the source for the pipe.
         */
        private final IEventBus sourceEventBus;
        /**
         * The event pattern to filter events sent to the pipe.
         */
        private final EventPattern eventPattern;
        /**
         * The Lambda function that enriches the event.
         */
        private final IFunction enrichmentFunction;
        /**
         * The API destination that is the target for the pipe.
         */
        private final IApiDestination apiDestination;
        /**
         * The maximum number of times that an event can be retried after the first failure.
         */
        private final Number maxRetryCount;
        /**
         * The size of the batches that are sent to the pipe.
         */
        private final Number sourceBatchSize;
        /**
         * The maximum amount of time, in seconds, to wait for a full batch of events before sending the batch to the pipe.
         */
        private final Number sourceMaximumBatchingWindowInSeconds;
        /**
         * Timeout of processing a single event.
         * <p>
         * After dequeuing, the enrichment has this much time to handle the event before it becomes visible again for dequeueing by another enrichment.
         * Default: Duration.seconds(30)
         */
        private final Duration visibilityTimeout;
        /**
         * The duration of the retry period.
         * <p>
         * After the initial failure, the event is retried with exponential backoff, up to the duration specified by this property.
         * Default: Duration.minutes(5)
         */
        private final Duration retryPeriod;
        /**
         * The URL of the API destination.
         */
        private final String endpointUrl;
        /**
         * The parameters to pass to the API destination.
         */
        private final CfnPipe.PipeTargetHttpParametersProperty targetHttpParameters;
        /**
         * The input template to pass to the enrichment Lambda function for transformation of the event.
         */
        private final String enrichmentInputTemplate;
        /**
         * The input template to pass to the API destination for transformation of the enriched event.
         */
        private final String targetInputTemplate;
        /**
         * The removal policy to apply to the pipe.
         */
        private final RemovalPolicy removalPolicy;
    }
}
