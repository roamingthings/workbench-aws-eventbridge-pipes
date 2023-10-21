package de.roamingthings;

import de.roamingthings.cdk.aws.pipes.EnrichedEventApiDestinationPipe;
import io.micronaut.aws.cdk.function.MicronautFunction;
import io.micronaut.aws.cdk.function.MicronautFunctionFile;
import io.micronaut.starter.application.ApplicationType;
import io.micronaut.starter.options.BuildTool;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnParameter;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Fn;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.SecretValue;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.apigateway.CfnStage;
import software.amazon.awscdk.services.apigateway.HttpIntegration;
import software.amazon.awscdk.services.apigateway.IntegrationOptions;
import software.amazon.awscdk.services.apigateway.MethodOptions;
import software.amazon.awscdk.services.apigateway.PassthroughBehavior;
import software.amazon.awscdk.services.apigateway.ProxyResourceOptions;
import software.amazon.awscdk.services.apigateway.RestApi;
import software.amazon.awscdk.services.apigateway.StageOptions;
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
import software.amazon.awscdk.services.lambda.Alias;
import software.amazon.awscdk.services.lambda.Architecture;
import software.amazon.awscdk.services.lambda.CfnFunction;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.IFunction;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.Tracing;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.pipes.CfnPipe;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

import static de.roamingthings.InfraConstants.EVENT_BUS_NAME_EXPORT_NAME;
import static de.roamingthings.InfraConstants.PERSON_TABLE_NAME_EXPORT_NAME;
import static software.amazon.awscdk.services.apigateway.EndpointType.REGIONAL;

public class AppStack extends Stack {

    public AppStack(Construct parent, String id) {
        this(parent, id, null);
    }

    public AppStack(Construct parent, String id, StackProps props) {
        super(parent, id, props);

        var endpointUrl = CfnParameter.Builder.create(this, "endpointUrl")
                .type("String")
                .description("The URL of the endpoint")
                .build()
                .getValueAsString();

        var proxyEndpointUrl = createApiGatewayProxy(endpointUrl);

        var apiDestinationTarget = createApiDestinationTarget(
                proxyEndpointUrl,
                Authorization.basic("Toniuser", SecretValue.unsafePlainText("SomeSecret"))
        );

        var personTable = createPersonTable();
        var eventBus = createEventBridgeBus();
        var enrichmentFunction = createEnrichmentFunction(personTable);
        var pipeProps = EnrichedEventApiDestinationPipe.EnrichedEventApiDestinationPipeProps.builder()
                .sourceEventBus(eventBus)
                .eventPattern(EventPattern.builder().source(List.of("*")).build())
                .enrichmentFunction(enrichmentFunction)
                .apiDestination(apiDestinationTarget)
                .maxRetryCount(1)
                .sourceBatchSize(1)
                .sourceMaximumBatchingWindowInSeconds(6)
                .visibilityTimeout(Duration.seconds(30))
                .retryPeriod(Duration.minutes(5))
                .targetHttpParameters(CfnPipe.PipeTargetHttpParametersProperty.builder()
                        .pathParameterValues(List.of("$.id"))
                        .build())
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        var pipe = new EnrichedEventApiDestinationPipe(this, "EnrichedEventApiDestinationPipe", pipeProps);
        // Patch the default rule to catch all events
        var ruleDefaultChild = pipe.getRule().getNode().getDefaultChild();
        if (ruleDefaultChild instanceof CfnRule cfnRule) {
            cfnRule.setEventPattern(Map.of(
                    "source", List.of(Map.of(
                            "prefix", ""
                    ))
            ));
        }

        CfnOutput.Builder.create(this, "PersonTableName")
                .exportName(PERSON_TABLE_NAME_EXPORT_NAME)
                .value(personTable.getTableName())
                .build();
        CfnOutput.Builder.create(this, "EventBusName")
                .exportName(EVENT_BUS_NAME_EXPORT_NAME)
                .value(eventBus.getEventBusName())
                .build();
    }

    private String createApiGatewayProxy(String endpointUrl) {
        var proxyEndpointUrl = Fn.join("", List.of(Fn.select(0, Fn.split("/*", endpointUrl)), "/{proxy}"));

        var description = "Forwarding requests to third party service";
        var stageName = "prod";
        var dataTraceEnabled = true;
        var metricsEnabled = true;
        var restApi = RestApi.Builder.create(this, "ApiProxy")
                .description(description)
                .endpointTypes(List.of(REGIONAL))
                .deployOptions(StageOptions.builder()
                        .stageName(stageName)
                        .build())
                .build();

        var stage = restApi.getDeploymentStage().getNode().getDefaultChild();
        if (stage instanceof CfnStage cfnStage) {
            cfnStage.setMethodSettings(List.of(
                    CfnStage.MethodSettingProperty.builder()
                            .resourcePath("/*")
                            .httpMethod("*")
                            .loggingLevel("INFO")
                            .dataTraceEnabled(dataTraceEnabled)
                            .metricsEnabled(metricsEnabled)
                            .build()
            ));
        }
        restApi.getRoot()
                .addProxy(ProxyResourceOptions.builder()
                        .anyMethod(true)
                        .defaultIntegration(HttpIntegration.Builder.create(proxyEndpointUrl)
                                .httpMethod("ANY")
                                .proxy(true)
                                .options(IntegrationOptions.builder()
                                        .credentialsPassthrough(true)
                                        .passthroughBehavior(PassthroughBehavior.WHEN_NO_MATCH)
                                        .requestParameters(Map.of(
                                                "integration.request.path.proxy", "method.request.path.proxy"
                                        ))
                                        .build())
                                .build())
                        .defaultMethodOptions(MethodOptions.builder()
                                .requestParameters(Map.of(
                                        "method.request.path.proxy", true
                                ))
                                .build())
                        .build());
        return "https://%s.execute-api.%s.amazonaws.com/%s/*".formatted(restApi.getRestApiId(), this.getRegion(), stageName);
    }

    private ITable createPersonTable() {
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

    private EventBus createEventBridgeBus() {
        return EventBus.Builder.create(this, "WorkbenchEventBridgePipesBus")
                .build();
    }

    private IFunction createEnrichmentFunction(ITable personTable) {
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
}
