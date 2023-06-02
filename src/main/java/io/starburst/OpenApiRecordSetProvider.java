/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.starburst;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableList;
import com.google.common.io.CharStreams;
import io.airlift.http.client.HttpClient;
import io.airlift.http.client.Request;
import io.airlift.http.client.Response;
import io.airlift.http.client.ResponseHandler;
import io.airlift.log.Logger;
import io.airlift.slice.Slice;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorRecordSetProvider;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorSplit;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.ConnectorTransactionHandle;
import io.trino.spi.connector.InMemoryRecordSet;
import io.trino.spi.connector.RecordSet;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.type.StandardTypes;
import io.trino.spi.type.Type;

import javax.inject.Inject;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static com.google.common.net.MediaType.JSON_UTF_8;
import static io.airlift.http.client.Request.Builder.prepareGet;
import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.trino.spi.StandardErrorCode.INVALID_ROW_FILTER;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

public class OpenApiRecordSetProvider
        implements ConnectorRecordSetProvider
{
    private static final Logger log = Logger.get(OpenApiRecordSetProvider.class);
    private final URI baseUri;

    private final OpenApiMetadata metadata;
    private final HttpClient httpClient;
    private final OpenApiSpec openApiSpec;

    @Inject
    public OpenApiRecordSetProvider(OpenApiConfig config, OpenApiMetadata metadata, @OpenApiClient HttpClient httpClient, OpenApiSpec openApiSpec)
    {
        this.baseUri = config.getBaseUri();
        this.metadata = metadata;
        this.httpClient = httpClient;
        this.openApiSpec = openApiSpec;
    }

    @Override
    public RecordSet getRecordSet(
            ConnectorTransactionHandle connectorTransactionHandle,
            ConnectorSession connectorSession,
            ConnectorSplit connectorSplit,
            ConnectorTableHandle table,
            List<? extends ColumnHandle> list)
    {
        List<OpenApiColumnHandle> columnHandles = list.stream()
                .map(c -> (OpenApiColumnHandle) c)
                .collect(toList());
        ConnectorTableMetadata tableMetadata = metadata.getTableMetadata(connectorSession, table);

        List<Integer> columnIndexes = columnHandles.stream()
                .map(column -> {
                    int index = 0;
                    for (ColumnMetadata columnMetadata : tableMetadata.getColumns()) {
                        if (columnMetadata.getName().equalsIgnoreCase(column.getName())) {
                            return index;
                        }
                        index++;
                    }
                    throw new IllegalStateException("Unknown column: " + column.getName());
                })
                .collect(toList());

        Iterable<List<?>> rows = getRows(connectorSession, (OpenApiTableHandle) table);
        Iterable<List<?>> mappedRows = StreamSupport.stream(rows.spliterator(), false)
                .map(row -> columnIndexes.stream()
                        .map(row::get)
                        .collect(toList())).collect(toList());

        List<Type> mappedTypes = columnHandles.stream()
                .map(OpenApiColumnHandle::getType)
                .collect(toList());
        return new InMemoryRecordSet(mappedTypes, mappedRows);
    }

    private Iterable<List<?>> getRows(ConnectorSession connectorSession, OpenApiTableHandle table)
    {
        ConnectorTableMetadata tableMetadata = metadata.getTableMetadata(connectorSession, table);
        String path = table.getPath();
        Map<String, OpenApiColumnHandle> columns = tableMetadata.getColumns().stream().collect(toMap(ColumnMetadata::getName, column -> new OpenApiColumnHandle(column.getName(), column.getType())));
        String tableName = table.getSchemaTableName().getTableName();
        Map<String, Parameter> requiredParams = openApiSpec.getRequiredParameters().get(tableName);
        // TODO we ignore query and header params
        Map<String, String> pathParams = requiredParams.entrySet().stream()
                .filter(entry -> entry.getValue().getIn().equals("path"))
                .collect(toMap(Map.Entry::getKey, entry -> {
                    // TODO this will fail for predicates on numeric columns
                    String value = (String) getFilter(columns.get(entry.getKey()), table.getConstraint(), null);
                    if (value == null) {
                        throw new TrinoException(INVALID_ROW_FILTER, "Missing required constraint for " + entry.getKey());
                    }
                    return value;
                }));
        for (Map.Entry<String, String> entry : pathParams.entrySet()) {
            // TODO we shouldn't have to do a reverse name mapping, we should iterate over tuples of spec properties and trino types
            String parameterName = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, entry.getKey());
            path = path.replace(format("{%s}", parameterName), entry.getValue());
        }

        ObjectMapper objectMapper = new ObjectMapper();

        Request request = prepareGet()
                .setUri(baseUri.resolve(path))
                .addHeader(CONTENT_TYPE, JSON_UTF_8.toString())
                .build();

        return httpClient.execute(request, new ResponseHandler<>()
        {
            @Override
            public Iterable<List<?>> handleException(Request request, Exception exception)
            {
                throw new RuntimeException(exception);
            }

            @Override
            public Iterable<List<?>> handle(Request request, Response response)
            {
                if (response.getStatusCode() != 200) {
                    throw new TrinoException(GENERIC_INTERNAL_ERROR, format("Response code for getRows request was not 200: %s", response.getStatusCode()));
                }
                String result = "";
                try {
                    result = CharStreams.toString(new InputStreamReader(response.getInputStream(), UTF_8));
                }
                catch (IOException e) {
                    throw new RuntimeException(e);
                }
                log.debug("Received response code " + response.getStatusCode() + ": " + result);

                try {
                    JsonNode jsonNode = objectMapper.readTree(result);

                    log.debug("Marshalled response to json %s", jsonNode);

                    JsonNode jsonNodeToUse = openApiSpec.getAdapter().map(adapter -> adapter.runAdapter(tableMetadata, jsonNode)).orElse(jsonNode);

                    return convertJson(jsonNodeToUse, tableMetadata);
                }
                catch (JsonProcessingException ex) {
                    throw new TrinoException(GENERIC_INTERNAL_ERROR, format("Could not marshal JSON from API response: %s", result), ex);
                }
            }
        });
    }

    private static Object getFilter(OpenApiColumnHandle column, TupleDomain<ColumnHandle> constraint, Object defaultValue)
    {
        requireNonNull(column, "column is null");
        Domain domain = null;
        if (constraint.getDomains().isPresent()) {
            domain = constraint.getDomains().get().get(column);
        }
        switch (column.getType().getBaseName()) {
            case StandardTypes.VARCHAR:
                if (domain == null) {
                    return defaultValue;
                }
                return ((Slice) domain.getSingleValue()).toStringUtf8();
            case StandardTypes.BIGINT:
            case StandardTypes.INTEGER:
                if (domain == null) {
                    return defaultValue;
                }
                return domain.getSingleValue();
            default:
                throw new TrinoException(INVALID_ROW_FILTER, "Unexpected constraint for " + column.getName() + "(" + column.getType().getBaseName() + ")");
        }
    }

    private Iterable<List<?>> convertJson(JsonNode jsonNode, ConnectorTableMetadata tableMetadata)
    {
        ImmutableList.Builder<List<?>> resultRecordsBuilder = ImmutableList.builder();

        if (jsonNode instanceof ArrayNode) {
            ArrayNode jsonArray = (ArrayNode) jsonNode;
            for (JsonNode jsonRecord : jsonArray) {
                resultRecordsBuilder.add(convertJsonToRecord(jsonRecord, tableMetadata));
            }
        }
        else {
            resultRecordsBuilder.add(convertJsonToRecord(jsonNode, tableMetadata));
        }

        return resultRecordsBuilder.build();
    }

    private List<?> convertJsonToRecord(JsonNode jsonNode, ConnectorTableMetadata tableMetadata)
    {
        if (!jsonNode.isObject()) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, format("JsonNode is not an object: %s", jsonNode));
        }

        List<Object> recordBuilder = new ArrayList<>();
        for (ColumnMetadata columnMetadata : tableMetadata.getColumns()) {
            // TODO we shouldn't have to do a reverse name mapping, we should iterate over tuples of spec properties and trino types
            String parameterName = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, columnMetadata.getName());
            if (!jsonNode.has(parameterName)) {
                recordBuilder.add(null);
                continue;
            }
            recordBuilder.add(
                    JsonTrinoConverter.convert(
                            jsonNode.get(parameterName),
                            columnMetadata.getType(),
                            openApiSpec.getOriginalColumnTypes(tableMetadata.getTable().getTableName()).get(columnMetadata.getName())));
        }

        return recordBuilder;
    }
}
