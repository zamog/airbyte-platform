/*
 * Copyright (c) 2020-2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.test.acceptance;

import static io.airbyte.test.acceptance.AcceptanceTestsResources.IS_GKE;
import static io.airbyte.test.acceptance.AcceptanceTestsResources.TRUE;
import static io.airbyte.test.acceptance.AcceptanceTestsResources.WITHOUT_SCD_TABLE;
import static io.airbyte.test.utils.AcceptanceTestHarness.COLUMN_ID;
import static io.airbyte.test.utils.AcceptanceTestHarness.COLUMN_NAME;
import static io.airbyte.test.utils.AcceptanceTestHarness.PUBLIC_SCHEMA_NAME;
import static io.airbyte.test.utils.AcceptanceTestHarness.STREAM_NAME;
import static io.airbyte.test.utils.AcceptanceTestHarness.waitWhileJobHasStatus;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Lists;
import io.airbyte.api.client.AirbyteApiClient;
import io.airbyte.api.client.generated.WebBackendApi;
import io.airbyte.api.client.invoker.generated.ApiException;
import io.airbyte.api.client.model.generated.AirbyteCatalog;
import io.airbyte.api.client.model.generated.AirbyteStream;
import io.airbyte.api.client.model.generated.AirbyteStreamAndConfiguration;
import io.airbyte.api.client.model.generated.AirbyteStreamConfiguration;
import io.airbyte.api.client.model.generated.ConnectionIdRequestBody;
import io.airbyte.api.client.model.generated.ConnectionStatus;
import io.airbyte.api.client.model.generated.DestinationDefinitionIdWithWorkspaceId;
import io.airbyte.api.client.model.generated.DestinationDefinitionSpecificationRead;
import io.airbyte.api.client.model.generated.DestinationRead;
import io.airbyte.api.client.model.generated.DestinationSyncMode;
import io.airbyte.api.client.model.generated.JobInfoRead;
import io.airbyte.api.client.model.generated.JobStatus;
import io.airbyte.api.client.model.generated.OperationCreate;
import io.airbyte.api.client.model.generated.OperationRead;
import io.airbyte.api.client.model.generated.OperatorConfiguration;
import io.airbyte.api.client.model.generated.OperatorType;
import io.airbyte.api.client.model.generated.OperatorWebhook;
import io.airbyte.api.client.model.generated.OperatorWebhook.WebhookTypeEnum;
import io.airbyte.api.client.model.generated.OperatorWebhookDbtCloud;
import io.airbyte.api.client.model.generated.SourceDefinitionSpecificationRead;
import io.airbyte.api.client.model.generated.SourceDiscoverSchemaRead;
import io.airbyte.api.client.model.generated.SourceRead;
import io.airbyte.api.client.model.generated.SyncMode;
import io.airbyte.api.client.model.generated.WebhookConfigWrite;
import io.airbyte.api.client.model.generated.WorkspaceRead;
import io.airbyte.api.client.model.generated.WorkspaceUpdate;
import io.airbyte.commons.json.Jsons;
import io.airbyte.db.jdbc.JdbcUtils;
import io.airbyte.test.utils.AcceptanceTestHarness;
import io.airbyte.test.utils.Asserts;
import io.airbyte.test.utils.TestConnectionCreate;
import java.io.IOException;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class tests for basic api functionality.
 * <p>
 * Due to the number of tests here, this set runs only on the docker deployment for speed. The tests
 * here are disabled for Kubernetes as operations take much longer due to Kubernetes pod spin up
 * times and there is little value in re-running these tests since this part of the system does not
 * vary between deployments.
 * <p>
 * We order tests such that earlier tests test more basic behavior relied upon in later tests. e.g.
 * We test that we can create a destination before we test whether we can sync data to it.
 * <p>
 * Suppressing DataFlowIssue to remove linting of NPEs. It removes a ton of noise and in the case of
 * these tests, the assert statement we would need to put in to check nullability is just as good as
 * throwing the NPE as they will be effectively the same at run time.
 */
@SuppressWarnings({"PMD.JUnitTestsShouldIncludeAssert", "DataFlowIssue", "SqlDialectInspection", "SqlNoDataSourceInspection",
  "PMD.AvoidDuplicateLiterals"})
@DisabledIfEnvironmentVariable(named = "SKIP_BASIC_ACCEPTANCE_TESTS",
                               matches = "true")
@TestInstance(Lifecycle.PER_CLASS)
class ApiAcceptanceTests {

  private static final Logger LOGGER = LoggerFactory.getLogger(ApiAcceptanceTests.class);

  private static final AcceptanceTestsResources testResources = new AcceptanceTestsResources();

  static final String DUPLICATE_TEST_IN_GKE =
      "TODO(https://github.com/airbytehq/airbyte-platform-internal/issues/5182): eliminate test duplication";
  static final String PUBLIC = "public";
  AcceptanceTestHarness testHarness;
  AirbyteApiClient apiClient;
  WebBackendApi webBackendApi;
  UUID workspaceId;

  @BeforeAll
  void init() throws URISyntaxException, IOException, InterruptedException, ApiException {
    testResources.init();
    testHarness = testResources.getTestHarness();
    apiClient = testResources.getApiClient();
    webBackendApi = testResources.getWebBackendApi();
    workspaceId = testResources.getWorkspaceId();
  }

  @BeforeEach
  void setup() throws SQLException, URISyntaxException, IOException {
    testResources.setup();
  }

  @AfterEach
  void tearDown() {
    testResources.tearDown();
  }

  @AfterAll
  static void end() {
    testResources.end();
  }

  @Test
  void testGetDestinationSpec() {
    final UUID destinationDefinitionId = testHarness.getPostgresDestinationDefinitionId();
    final DestinationDefinitionSpecificationRead spec = testHarness.getDestinationDefinitionSpec(destinationDefinitionId,
        workspaceId);
    assertEquals(destinationDefinitionId, spec.getDestinationDefinitionId());
    assertNotNull(spec.getConnectionSpecification());
  }

  @Test
  void testFailedGet404() {
    final var e = assertThrows(ApiException.class, () -> apiClient.getDestinationDefinitionSpecificationApi()
        .getDestinationDefinitionSpecification(
            new DestinationDefinitionIdWithWorkspaceId().destinationDefinitionId(UUID.randomUUID()).workspaceId(UUID.randomUUID())));
    assertEquals(404, e.getCode());
  }

  @Test
  @DisabledIfEnvironmentVariable(named = IS_GKE,
                                 matches = TRUE,
                                 disabledReason = DUPLICATE_TEST_IN_GKE)
  void testGetSourceSpec() {
    final UUID sourceDefId = testHarness.getPostgresSourceDefinitionId();
    final SourceDefinitionSpecificationRead spec = testHarness.getSourceDefinitionSpec(sourceDefId);
    assertNotNull(spec.getConnectionSpecification());
  }

  @Test
  @DisabledIfEnvironmentVariable(named = IS_GKE,
                                 matches = TRUE,
                                 disabledReason = DUPLICATE_TEST_IN_GKE)
  void testCreateDestination() {
    final UUID destinationDefId = testHarness.getPostgresDestinationDefinitionId();
    final JsonNode destinationConfig = testHarness.getDestinationDbConfig();
    final String name = "AccTestDestinationDb-" + UUID.randomUUID();

    final DestinationRead createdDestination = testHarness.createDestination(
        name,
        workspaceId,
        destinationDefId,
        destinationConfig);

    assertEquals(name, createdDestination.getName());
    assertEquals(destinationDefId, createdDestination.getDestinationDefinitionId());
    assertEquals(workspaceId, createdDestination.getWorkspaceId());
    assertEquals(testHarness.getDestinationDbConfigWithHiddenPassword(), createdDestination.getConnectionConfiguration());
  }

  @Test
  @DisabledIfEnvironmentVariable(named = IS_GKE,
                                 matches = TRUE,
                                 disabledReason = DUPLICATE_TEST_IN_GKE)
  void testCreateSource() {
    final String dbName = "acc-test-db";
    final UUID postgresSourceDefinitionId = testHarness.getPostgresSourceDefinitionId();
    final JsonNode sourceDbConfig = testHarness.getSourceDbConfig();
    final SourceRead response = testHarness.createSource(
        dbName,
        workspaceId,
        postgresSourceDefinitionId,
        sourceDbConfig);

    final JsonNode expectedConfig = Jsons.jsonNode(sourceDbConfig);
    // expect replacement of secret with magic string.
    ((ObjectNode) expectedConfig).put(JdbcUtils.PASSWORD_KEY, "**********");
    assertEquals(dbName, response.getName());
    assertEquals(workspaceId, response.getWorkspaceId());
    assertEquals(postgresSourceDefinitionId, response.getSourceDefinitionId());
    assertEquals(expectedConfig, response.getConnectionConfiguration());
  }

  @Test
  @DisabledIfEnvironmentVariable(named = IS_GKE,
                                 matches = TRUE,
                                 disabledReason = DUPLICATE_TEST_IN_GKE)
  void testDiscoverSourceSchema() {
    final UUID sourceId = testHarness.createPostgresSource().getSourceId();

    final AirbyteCatalog actual = testHarness.discoverSourceSchema(sourceId);

    final JsonNode expectedSchema = Jsons.deserialize("""
                                                      {
                                                        "type": "object",
                                                        "properties": {
                                                          "%s": {
                                                            "type": "number",
                                                            "airbyte_type": "integer"
                                                          },
                                                          "%s": {
                                                            "type": "string"
                                                          }
                                                        }
                                                      }
                                                      """.formatted(COLUMN_ID, COLUMN_NAME));
    final AirbyteStream expectedStream = new AirbyteStream()
        .name(STREAM_NAME)
        .namespace(PUBLIC)
        .jsonSchema(expectedSchema)
        .sourceDefinedCursor(null)
        .defaultCursorField(Collections.emptyList())
        .sourceDefinedPrimaryKey(Collections.emptyList())
        .supportedSyncModes(List.of(SyncMode.FULL_REFRESH, SyncMode.INCREMENTAL));
    final AirbyteStreamConfiguration expectedStreamConfig = new AirbyteStreamConfiguration()
        .syncMode(SyncMode.FULL_REFRESH)
        .cursorField(Collections.emptyList())
        .destinationSyncMode(DestinationSyncMode.OVERWRITE)
        .primaryKey(Collections.emptyList())
        .aliasName(STREAM_NAME.replace(".", "_"))
        .selected(true)
        .suggested(true);
    final AirbyteCatalog expected = new AirbyteCatalog()
        .streams(Lists.newArrayList(new AirbyteStreamAndConfiguration()
            .stream(expectedStream)
            .config(expectedStreamConfig)));

    assertEquals(expected, actual);
  }

  @Test
  void testDeleteConnection() throws Exception {
    final UUID sourceId = testHarness.createPostgresSource().getSourceId();
    final UUID destinationId = testHarness.createPostgresDestination().getDestinationId();
    final SourceDiscoverSchemaRead discoverResult = testHarness.discoverSourceSchemaWithId(sourceId);
    final AirbyteCatalog catalog = discoverResult.getCatalog();
    final SyncMode srcSyncMode = SyncMode.INCREMENTAL;
    final DestinationSyncMode dstSyncMode = DestinationSyncMode.APPEND_DEDUP;
    catalog.getStreams().forEach(s -> s.getConfig()
        .syncMode(srcSyncMode)
        .selected(true)
        .cursorField(List.of(COLUMN_ID))
        .destinationSyncMode(dstSyncMode)
        .primaryKey(List.of(List.of(COLUMN_NAME))));

    final UUID connectionId =
        testHarness.createConnection(new TestConnectionCreate.Builder(
            sourceId,
            destinationId,
            catalog,
            discoverResult.getCatalogId())
                .build())
            .getConnectionId();

    final JobInfoRead connectionSyncRead = testHarness.syncConnection(connectionId);
    waitWhileJobHasStatus(apiClient.getJobsApi(), connectionSyncRead.getJob(), Set.of(JobStatus.RUNNING));

    // test normal deletion of connection
    LOGGER.info("Calling delete connection...");
    testHarness.deleteConnection(connectionId);
    testHarness.removeConnection(connectionId); // NOTE: make sure we don't try to delete it again in test teardown.

    ConnectionStatus connectionStatus = testHarness.getConnection(connectionId).getStatus();
    assertEquals(ConnectionStatus.DEPRECATED, connectionStatus);

    // test that repeated deletion call for same connection is successful
    LOGGER.info("Calling delete connection a second time to test repeat call behavior...");
    assertDoesNotThrow(() -> testHarness.deleteConnection(connectionId));

    // TODO: break this into a separate testcase which we can disable for GKE.
    if (!System.getenv().containsKey("IS_GKE")) {
      // test deletion of connection when temporal workflow is in a bad state
      LOGGER.info("Testing connection deletion when temporal is in a terminal state");
      final var anotherConnectionId =
          testHarness.createConnection(new TestConnectionCreate.Builder(
              sourceId,
              destinationId,
              catalog,
              discoverResult.getCatalogId())
                  .build())
              .getConnectionId();

      testHarness.terminateTemporalWorkflow(anotherConnectionId);

      // we should still be able to delete the connection when the temporal workflow is in this state
      testHarness.deleteConnection(anotherConnectionId);

      connectionStatus = testHarness.getConnection(anotherConnectionId).getStatus();
      assertEquals(ConnectionStatus.DEPRECATED, connectionStatus);
    }
  }

  @Test
  @DisabledIfEnvironmentVariable(named = IS_GKE,
                                 matches = TRUE,
                                 disabledReason = "GKE deployment applies extra validation")
  void testWebhookOperationExecutesSuccessfully() throws Exception {
    // create workspace webhook config
    final WorkspaceRead workspaceRead = apiClient.getWorkspaceApi().updateWorkspace(
        new WorkspaceUpdate().workspaceId(workspaceId).addWebhookConfigsItem(
            new WebhookConfigWrite().name("reqres test")));
    // create a webhook operation
    final OperationRead operationRead = apiClient.getOperationApi().createOperation(new OperationCreate()
        .workspaceId(workspaceId)
        .name("reqres test")
        .operatorConfiguration(new OperatorConfiguration()
            .operatorType(OperatorType.WEBHOOK)
            .webhook(new OperatorWebhook()
                .webhookConfigId(workspaceRead.getWebhookConfigs().get(0).getId())
                // NOTE: this dbt Cloud config won't actually work, but the sync should still succeed.
                .webhookType(WebhookTypeEnum.DBTCLOUD)
                .dbtCloud(new OperatorWebhookDbtCloud().accountId(123).jobId(456)))));
    // create a connection with the new operation.
    final UUID sourceId = testHarness.createPostgresSource().getSourceId();
    final UUID destinationId = testHarness.createPostgresDestination().getDestinationId();
    // NOTE: this is a normalization operation.
    final UUID normalizationOpId = testHarness.createNormalizationOperation().getOperationId();
    final SourceDiscoverSchemaRead discoverResult = testHarness.discoverSourceSchemaWithId(sourceId);
    final AirbyteCatalog catalog = discoverResult.getCatalog();
    final SyncMode srcSyncMode = SyncMode.FULL_REFRESH;
    final DestinationSyncMode dstSyncMode = DestinationSyncMode.OVERWRITE;
    catalog.getStreams().forEach(s -> s.getConfig().syncMode(srcSyncMode).selected(true).destinationSyncMode(dstSyncMode));
    final var conn =
        testHarness.createConnection(new TestConnectionCreate.Builder(
            sourceId,
            destinationId,
            catalog,
            discoverResult.getCatalogId())
                .setNormalizationOperationId(normalizationOpId)
                .setAdditionalOperationIds(List.of(operationRead.getOperationId()))
                .build());
    final var connectionId = conn.getConnectionId();

    // run the sync
    final var jobRead = apiClient.getConnectionApi().syncConnection(new ConnectionIdRequestBody().connectionId(connectionId)).getJob();
    testResources.waitForSuccessfulJobWithRetries(jobRead);
    Asserts.assertSourceAndDestinationDbRawRecordsInSync(
        testHarness.getSourceDatabase(), testHarness.getDestinationDatabase(), PUBLIC_SCHEMA_NAME,
        conn.getNamespaceFormat(), true,
        WITHOUT_SCD_TABLE);
    apiClient.getConnectionApi().deleteConnection(new ConnectionIdRequestBody().connectionId(connectionId));
    // remove connection to avoid exception during tear down
    testHarness.removeConnection(connectionId);
    // TODO(mfsiega-airbyte): add webhook info to the jobs api to verify the webhook execution status.
  }

}
