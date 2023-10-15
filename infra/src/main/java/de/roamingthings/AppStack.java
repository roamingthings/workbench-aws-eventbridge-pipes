package de.roamingthings;

import io.micronaut.aws.cdk.function.MicronautFunction;
import io.micronaut.aws.cdk.function.MicronautFunctionFile;
import io.micronaut.starter.application.ApplicationType;
import io.micronaut.starter.options.BuildTool;
import lombok.Data;
import org.jetbrains.annotations.NotNull;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnParameter;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.IStackSynthesizer;
import software.amazon.awscdk.PermissionsBoundary;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.SecretValue;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
import software.amazon.awscdk.services.dynamodb.ITable;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.events.ApiDestination;
import software.amazon.awscdk.services.events.Authorization;
import software.amazon.awscdk.services.events.CfnRule;
import software.amazon.awscdk.services.events.Connection;
import software.amazon.awscdk.services.events.EventBus;
import software.amazon.awscdk.services.events.EventPattern;
import software.amazon.awscdk.services.events.HttpMethod;
import software.amazon.awscdk.services.events.Rule;
import software.amazon.awscdk.services.events.targets.SqsQueue;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyDocument;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Alias;
import software.amazon.awscdk.services.lambda.Architecture;
import software.amazon.awscdk.services.lambda.CfnFunction;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.IFunction;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.Tracing;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.pipes.CfnPipe;
import software.amazon.awscdk.services.sqs.DeadLetterQueue;
import software.amazon.awscdk.services.sqs.IQueue;
import software.amazon.awscdk.services.sqs.Queue;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

import static de.roamingthings.InfraConstants.EVENT_BUS_NAME_EXPORT_NAME;
import static de.roamingthings.InfraConstants.PERSON_TABLE_NAME_EXPORT_NAME;

public class AppStack extends Stack {

    private static final int MAX_RETRY_COUNT = 1;

    public AppStack(Construct parent, String id) {
        this(parent, id, null);
    }

    public AppStack(Construct parent, String id, AppStackProps props) {
        super(parent, id, props);

        var endpointUrl = CfnParameter.Builder.create(this, "endpointUrl")
                .type("String")
                .description("The URL of the endpoint")
                .build();

        var personTable = createPesonTable();
        var eventBus = createEventBridgeBus();
        var dlq = createDlq();
        var pipeSource = createQueue(MAX_RETRY_COUNT, dlq);
        var enrichment = createEnricherFunction(personTable);
        var apiDestinationTarget = createApiDestinationTarget(
                endpointUrl.getValueAsString(),
                Authorization.basic("Toniuser", SecretValue.unsafePlainText("SomeSecret"))
        );
        createCatchAllRule(eventBus, pipeSource);
        createPipe(pipeSource, enrichment, apiDestinationTarget, dlq);

        CfnOutput.Builder.create(this, "PersonTableName")
                .exportName(PERSON_TABLE_NAME_EXPORT_NAME)
                .value(personTable.getTableName())
                .build();
        CfnOutput.Builder.create(this, "EventBusName")
                .exportName(EVENT_BUS_NAME_EXPORT_NAME)
                .value(eventBus.getEventBusName())
                .build();
    }

    private ITable createPesonTable() {
        return Table.Builder.create(this, "PersonTable")
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .partitionKey(Attribute.builder()
                        .name("PK")
                        .type(AttributeType.STRING)
                        .build())
                .sortKey(Attribute.builder()
                        .name("SK")
                        .type(AttributeType.STRING)
                        .build())
                .timeToLiveAttribute("expiresAt")
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();
    }

    private void createPipe(IQueue pipeSource, IFunction enrichment, ApiDestination apiDestinationTarget, IQueue dlq) {
        var pipeRole = createPipeRole(pipeSource, enrichment, apiDestinationTarget, dlq);
        CfnPipe.Builder.create(this, "Pipe")
                .source(pipeSource.getQueueArn())
                .sourceParameters(CfnPipe.PipeSourceParametersProperty.builder()
                        .sqsQueueParameters(CfnPipe.PipeSourceSqsQueueParametersProperty.builder()
                                .batchSize(1)
                                .maximumBatchingWindowInSeconds(6)
                                .build())
                        .build()
                )
                .enrichment(enrichment.getFunctionArn())
                .enrichmentParameters(CfnPipe.PipeEnrichmentParametersProperty.builder().build())
                .target(apiDestinationTarget.getApiDestinationArn())
                .targetParameters(CfnPipe.PipeTargetParametersProperty.builder()
                        .httpParameters(CfnPipe.PipeTargetHttpParametersProperty.builder()
                                .pathParameterValues(List.of("$.id"))
                                .build())
                        .build())
                .roleArn(pipeRole.getRoleArn())
                .build();
    }

    @NotNull
    private Role createPipeRole(IQueue pipeSource, IFunction enrichment, ApiDestination apiDestinationTarget, IQueue dlq) {
        var sourcePolicy = createSourcePolicy(pipeSource, dlq);
        var enrichmentPolicy = createEnrichmentPolicy(enrichment);
        var targetPolicy = createTargetPolicy(apiDestinationTarget);

        return Role.Builder.create(this, "Role")
                .inlinePolicies(Map.of(
                        "sourcePolicy", sourcePolicy,
                        "enrichmentPolicy", enrichmentPolicy,
                        "targetPolicy", targetPolicy
                ))
                .assumedBy(new ServicePrincipal("pipes.amazonaws.com"))
                .build();
    }

    private static PolicyDocument createTargetPolicy(ApiDestination apiDestinationTarget) {
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

    private static PolicyDocument createSourcePolicy(IQueue pipeSource, IQueue dlq) {
        var queueArn = pipeSource.getQueueArn();
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

    private ApiDestination createApiDestinationTarget(String endpointUrl, Authorization authorization) {
        var connection = Connection.Builder.create(this, "ThirdPartyService")
                .authorization(authorization)
                .description("Connection with Third Party API")
                .build();

        return ApiDestination.Builder.create(this, "ThirdPartyServiceApiDestination")
                .endpoint(endpointUrl)
                .httpMethod(HttpMethod.POST)
                .rateLimitPerSecond(5)
                .connection(connection)
                .build();
    }

    private void createCatchAllRule(EventBus eventBus, Queue source) {
        var rule = Rule.Builder.create(this, "CatchAllRule")
                .eventBus(eventBus)
                .eventPattern(EventPattern.builder().source(List.of("*")).build())
                .targets(List.of(SqsQueue.Builder.create(source).build()))
                .build();
        var ruleDefaultChild = rule.getNode().getDefaultChild();
        if (ruleDefaultChild instanceof CfnRule cfnRule) {
            cfnRule.setEventPattern(Map.of(
                    "source", List.of(Map.of(
                            "prefix", ""
                    ))
            ));
        }
    }

    private EventBus createEventBridgeBus() {
        return EventBus.Builder.create(this, "WorkbenchEventBridgePipesBus")
                .build();
    }

    private Queue createQueue(int maxRetryCount, IQueue dlq) {
        return Queue.Builder.create(this, "ThirdPartyApiRequestsQueue")
                .visibilityTimeout(Duration.seconds(60))
                .retentionPeriod(Duration.seconds(60))
                .removalPolicy(RemovalPolicy.DESTROY)
                .deadLetterQueue(DeadLetterQueue.builder()
                        .maxReceiveCount(maxRetryCount)
                        .queue(dlq)
                        .build())
                .build();
    }

    private Queue createDlq() {
        return Queue.Builder.create(this, "ThirdPartyApiRequestsDlq")
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();
    }

    private IFunction createEnricherFunction(ITable personTable) {
        var environmentVariables = Map.of(
                "PERSON_TABLE_NAME", personTable.getTableName()
        );
        var function = MicronautFunction.create(ApplicationType.DEFAULT,
                        false,
                        this,
                        "enricher-function")
                .runtime(Runtime.JAVA_17)
                .handler("de.roamingthings.ThirdPartyApiRequestEnricherHandler")
                .environment(environmentVariables)
                .code(Code.fromAsset(functionPath()))
                .timeout(Duration.seconds(10))
                .memorySize(2048)
                .logRetention(RetentionDays.ONE_DAY)
                .tracing(Tracing.ACTIVE)
                .architecture(Architecture.X86_64)
                .build();

        personTable.grantReadData(function);

        var defaultChild = function.getNode().getDefaultChild();
        if (defaultChild instanceof CfnFunction cfnFunction) {
            cfnFunction.setSnapStart(CfnFunction.SnapStartProperty.builder()
                    .applyOn("PublishedVersions")
                    .build());
        }
        return Alias.Builder.create(this, "EnricherAlias")
                .aliasName("LIVE")
                .version(function.getCurrentVersion())
                .build();
    }

    public static String functionPath() {
        return "../app/build/libs/" + functionFilename();
    }

    public static String functionFilename() {
        return MicronautFunctionFile.builder()
                .optimized()
                .graalVMNative(false)
                .version("0.1")
                .archiveBaseName("app")
                .buildTool(BuildTool.GRADLE)
                .build();
    }

    @Data
    @lombok.Builder
    public static class AppStackProps implements StackProps {
        private final Boolean analyticsReporting;
        private final Boolean crossRegionReferences;
        private final String description;
        private final Environment env;
        private final PermissionsBoundary permissionsBoundary;
        private final String stackName;
        private final Boolean suppressTemplateIndentation;
        private final IStackSynthesizer synthesizer;
        private final Map<String, String> tags;
        private final Boolean terminationProtection;
    }
}
