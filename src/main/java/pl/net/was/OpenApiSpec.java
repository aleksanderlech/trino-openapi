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

package pl.net.was;

import com.fasterxml.jackson.core.JsonPointer;
import com.google.common.base.CaseFormat;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.google.inject.Inject;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.DateSchema;
import io.swagger.v3.oas.models.media.DateTimeSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MapSchema;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import io.trino.spi.connector.SchemaNotFoundException;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.TableNotFoundException;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.MapType;
import io.trino.spi.type.RowType;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeOperators;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.DecimalType.createDecimalType;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RealType.REAL;
import static io.trino.spi.type.TimestampType.TIMESTAMP_MILLIS;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;

public class OpenApiSpec
{
    public static final String SCHEMA_NAME = "default";
    public static final String ROW_ID = "__trino_row_id";
    public static final String HTTP_OK = "200";
    public static final String MIME_JSON = "application/json";

    private static final TypeTuple FALLBACK_TYPE = new TypeTuple(VARCHAR, new StringSchema());

    private static final String EXTENSION_PAGINATION = "x-pagination";
    private static final String PAGINATION_RESULTS_PATH = "resultsPath";
    private static final String PAGINATION_PAGE_PARAM = "pageParam";
    private static final Pattern RESULTS_PATH_PATTERN = Pattern.compile("\\$response\\.body#(/.*)");

    // should only be used to manually resolving references
    private final OpenAPI openApi;
    private final Map<String, List<OpenApiColumn>> tables;
    private final Map<String, Map<PathItem.HttpMethod, String>> paths;

    private final Map<String, Map<PathItem.HttpMethod, List<SecurityRequirement>>> pathSecurityRequirements;
    private final Map<String, SecurityScheme> securitySchemas;
    private final List<SecurityRequirement> securityRequirements;

    @Inject
    public OpenApiSpec(OpenApiConfig config)
    {
        this(parse(requireNonNull(config, "config is null").getSpecLocation()));
    }

    OpenApiSpec(OpenAPI openApi)
    {
        this.openApi = requireNonNull(openApi, "openApi is null");

        this.tables = openApi.getPaths().entrySet().stream()
                .filter(entry -> hasOpsWithJson(entry.getValue()))
                .filter(entry -> !getIdentifier(stripPathParams(entry.getKey())).isEmpty())
                .collect(toMap(
                        entry -> getIdentifier(stripPathParams(entry.getKey())),
                        entry -> getColumns(entry.getValue(), entry.getKey()),
                        (a, b) -> Stream.concat(a.stream(), b.stream()).distinct().toList()))
                .entrySet().stream()
                .collect(toMap(Map.Entry::getKey, entry -> mergeColumns(entry.getValue())));
        this.paths = openApi.getPaths().entrySet().stream()
                .map(pathEntry -> Map.entry(
                        getIdentifier(stripPathParams(pathEntry.getKey())),
                        pathEntry.getValue().readOperationsMap().keySet().stream()
                                .filter(method -> filterPath(pathEntry.getKey(), method))
                                .collect(toMap(identity(), method -> pathEntry.getKey()))))
                .collect(toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        // merge both maps
                        (a, b) -> Stream.concat(a.entrySet().stream(), b.entrySet().stream())
                                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (x, y) -> x.length() < y.length() ? x : y))));
        this.pathSecurityRequirements = openApi.getPaths().entrySet().stream()
                .map(pathEntry -> Map.entry(
                        pathEntry.getKey(),
                        pathEntry.getValue().readOperationsMap().entrySet().stream()
                                .filter(opEntry -> opEntry.getValue().getSecurity() != null)
                                .map(opEntry -> Map.entry(
                                        opEntry.getKey(),
                                        opEntry.getValue().getSecurity()))
                                .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue))))
                .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
        this.securitySchemas = openApi.getComponents().getSecuritySchemes();
        this.securityRequirements = openApi.getSecurity();
    }

    private String stripPathParams(String key)
    {
        return key.replaceAll("/\\{[^\\}]+\\}", "");
    }

    private static OpenAPI parse(String specLocation)
    {
        ParseOptions parseOptions = new ParseOptions();
        parseOptions.setResolveFully(true);
        SwaggerParseResult result = new OpenAPIV3Parser().readLocation(specLocation, null, parseOptions);
        OpenAPI openAPI = result.getOpenAPI();

        if (result.getMessages() != null && !result.getMessages().isEmpty()) {
            throw new IllegalArgumentException("Failed to parse the OpenAPI spec: " + String.join(", ", result.getMessages()));
        }

        return openAPI;
    }

    private static boolean hasOpsWithJson(PathItem pathItem)
    {
        return pathItem.readOperations().stream().anyMatch(OpenApiSpec::hasJsonResponse);
    }

    private static boolean hasJsonResponse(Operation op)
    {
        return op != null && (op.getDeprecated() == null || !op.getDeprecated()) &&
                op.getResponses().get(HTTP_OK) != null &&
                op.getResponses().get(HTTP_OK).getContent() != null &&
                op.getResponses().get(HTTP_OK).getContent().get(MIME_JSON) != null;
    }

    public Map<String, List<OpenApiColumn>> getTables()
    {
        return tables;
    }

    public OpenApiTableHandle getTableHandle(SchemaTableName name)
    {
        if (!name.getSchemaName().equals(SCHEMA_NAME)) {
            throw new SchemaNotFoundException(name.getSchemaName());
        }
        Map<PathItem.HttpMethod, String> paths = this.paths.get(name.getTableName());
        if (paths == null) {
            throw new TableNotFoundException(name);
        }
        return new OpenApiTableHandle(
                name,
                // some APIs use POST to query resources
                paths.containsKey(PathItem.HttpMethod.GET) ? paths.get(PathItem.HttpMethod.GET) : paths.get(PathItem.HttpMethod.POST),
                paths.containsKey(PathItem.HttpMethod.GET) ? PathItem.HttpMethod.GET : PathItem.HttpMethod.POST,
                paths.get(PathItem.HttpMethod.POST),
                PathItem.HttpMethod.POST,
                // some APIs use POST to update resources, or both PUT and POST, with an identifier as a required query parameter or in the body
                paths.containsKey(PathItem.HttpMethod.PUT) ? paths.get(PathItem.HttpMethod.PUT) : paths.get(PathItem.HttpMethod.POST),
                paths.containsKey(PathItem.HttpMethod.PUT) ? PathItem.HttpMethod.PUT : PathItem.HttpMethod.POST,
                paths.get(PathItem.HttpMethod.DELETE),
                PathItem.HttpMethod.DELETE,
                TupleDomain.none());
    }

    private List<OpenApiColumn> getColumns(PathItem pathItem, String path)
    {
        Stream<OpenApiColumn> columns = pathItem.readOperationsMap().entrySet().stream()
                .flatMap(entry -> getColumn(path, entry))
                .distinct();
        if (pathItem.getPost() != null || pathItem.getPut() != null || pathItem.getDelete() != null) {
            // the ROW_ID column is required for MERGE operation, including UPDATE and DELETE
            return Stream.concat(
                            Stream.of(OpenApiColumn.builder()
                                    .setName(ROW_ID)
                                    .setType(VARCHAR)
                                    .setSourceType(new StringSchema())
                                    .setIsHidden(true)
                                    .build()),
                            columns)
                    .toList();
        }
        return columns.toList();
    }

    private Stream<OpenApiColumn> getColumn(String path, Map.Entry<PathItem.HttpMethod, Operation> entry)
    {
        PathItem.HttpMethod method = entry.getKey();
        Operation op = entry.getValue();
        List<OpenApiColumn> result = new ArrayList<>();

        Map<String, String> pagination = op.getExtensions() == null ? ImmutableMap.of() : getMapOfStrings(op.getExtensions().get(EXTENSION_PAGINATION));
        JsonPointer resultsPointer = getResultsPath(pagination);

        Schema<?> schema = getResponseSchema(op);
        if (schema != null) {
            List<String> requiredProperties = schema.getRequired() != null ? schema.getRequired() : List.of();
            getSchemaProperties(schema)
                    .entrySet().stream()
                    .filter(propEntry -> !resultsPointer.matchesProperty(propEntry.getKey()))
                    .map(propEntry -> getResultColumn(
                            propEntry.getKey(),
                            propEntry.getValue(),
                            !requiredProperties.contains(propEntry.getKey()),
                            propEntry.getKey().equals(pagination.get(PAGINATION_PAGE_PARAM))))
                    .filter(Optional::isPresent)
                    .forEach(column -> result.add(column.get()));
            getResultsSchema(schema, resultsPointer)
                    .entrySet().stream()
                    .map(propEntry -> getResultColumn(
                            propEntry.getKey(),
                            resultsPointer,
                            propEntry.getValue(),
                            !requiredProperties.contains(propEntry.getKey()),
                            propEntry.getKey().equals(pagination.get(PAGINATION_PAGE_PARAM))))
                    .filter(Optional::isPresent)
                    .forEach(column -> result.add(column.get()));
        }
        schema = getRequestSchema(op);
        if (schema != null) {
            ListMultimap<String, OpenApiColumn.PrimaryKey> keys = result.stream()
                    .collect(ArrayListMultimap::create,
                            (map, element) -> map.put(element.getName(), element.getPrimaryKey()),
                            ArrayListMultimap::putAll);
            List<String> requiredProperties = schema.getRequired() != null ? schema.getRequired() : List.of();
            getSchemaProperties(schema)
                    .entrySet().stream()
                    .map(propEntry -> getPredicateColumn(
                            propEntry.getKey(),
                            propEntry.getValue(),
                            requiredProperties.contains(propEntry.getKey()) ? Map.of(method, "body") : Map.of(),
                            !requiredProperties.contains(propEntry.getKey()) ? Map.of(method, "body") : Map.of(),
                            !requiredProperties.contains(propEntry.getKey()),
                            false,
                            propEntry.getKey().equals(pagination.get(PAGINATION_PAGE_PARAM))))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .map(column -> {
                        while (hasAmbiguousName(column, keys)) {
                            // if the request param is also a response field,
                            // append `_req` to its name to disambiguate it,
                            // otherwise it'll get a number suffix, like `_2`
                            column = OpenApiColumn.builderFrom(column)
                                    .setName(column.getName() + "_req")
                                    .build();
                        }
                        return column;
                    })
                    .forEach(result::add);
        }
        if (op.getParameters() != null && filterPath(path, method)) {
            ListMultimap<String, OpenApiColumn.PrimaryKey> keys = result.stream()
                    .collect(ArrayListMultimap::create,
                            (map, element) -> map.put(element.getName(), element.getPrimaryKey()),
                            ArrayListMultimap::putAll);
            // add required parameters as columns, so they can be set as predicates;
            // predicate values will be saved in the table handle and copied to result rows
            op.getParameters().stream()
                    .map(parameter -> getPredicateColumn(
                            parameter.getName(),
                            parameter.getSchema(),
                            parameter.getRequired() ? Map.of(method, parameter.getIn()) : Map.of(),
                            !parameter.getRequired() ? Map.of(method, parameter.getIn()) : Map.of(),
                            // always nullable, because they're only required as predicates, not in INSERT statements
                            true,
                            // keep pagination parameters as hidden columns, so it's possible to
                            // see the page number (how many requests were made) and change the default per-page limit
                            pagination.containsValue(parameter.getName()),
                            parameter.getName().equals(pagination.get(PAGINATION_PAGE_PARAM))))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .map(column -> {
                        while (hasAmbiguousName(column, keys)) {
                            // if the request param is also a response field,
                            // append `_req` to its name to disambiguate it,
                            // otherwise it'll get a number suffix, like `_2`
                            column = OpenApiColumn.builderFrom(column)
                                    .setName(column.getName() + "_req")
                                    .build();
                        }
                        return column;
                    })
                    .forEach(result::add);
        }

        return result.stream();
    }

    private static JsonPointer getResultsPath(Map<String, String> pagination)
    {
        if (!pagination.containsKey(PAGINATION_RESULTS_PATH)) {
            return JsonPointer.empty();
        }
        String resultsPath = pagination.get(PAGINATION_RESULTS_PATH);
        Matcher matcher = RESULTS_PATH_PATTERN.matcher(resultsPath);
        if (matcher.matches()) {
            return JsonPointer.compile(matcher.group(1));
        }
        if (!resultsPath.contains("/") && (resultsPath.contains(".") || resultsPath.contains("["))) {
            // it might be a JSON path, which are not supported, so ignore them
            return JsonPointer.empty();
        }
        if (resultsPath.startsWith("$")) {
            throw new IllegalArgumentException("Invalid value of %s.%s: %s, complex JSON pointer or JSON path expressions are not supported".formatted(EXTENSION_PAGINATION, RESULTS_PATH_PATTERN, resultsPath));
        }
        if (!resultsPath.startsWith("/")) {
            resultsPath = "/" + resultsPath;
        }
        return JsonPointer.compile(resultsPath);
    }

    private static Schema<?> getResponseSchema(Operation op)
    {
        if (op.getResponses() == null
                || op.getResponses().get(HTTP_OK) == null
                || op.getResponses().get(HTTP_OK).getContent() == null
                || op.getResponses().get(HTTP_OK).getContent().get(MIME_JSON) == null) {
            return null;
        }
        return op.getResponses()
                .get(HTTP_OK)
                .getContent()
                .get(MIME_JSON)
                .getSchema();
    }

    private static Schema<?> getRequestSchema(Operation op)
    {
        if (op.getRequestBody() == null
                || op.getRequestBody().getContent() == null
                || op.getRequestBody().getContent().get(MIME_JSON) == null
                || op.getRequestBody().getContent().get(MIME_JSON).getSchema() == null) {
            return null;
        }
        return op.getRequestBody()
                .getContent()
                .get(MIME_JSON)
                .getSchema();
    }

    private static Map<String, String> getMapOfStrings(Object object)
    {
        if (!(object instanceof Map<?, ?>)) {
            return ImmutableMap.of();
        }

        return ((Map<?, ?>) object).entrySet().stream()
                .filter(entry -> entry.getKey() instanceof String && entry.getValue() instanceof String)
                .collect(toImmutableMap(entry -> (String) entry.getKey(), entry -> (String) entry.getValue()));
    }

    private static Map<String, Schema> getSchemaProperties(Schema<?> schema)
    {
        Map<String, Schema> properties;
        if (schema instanceof ArraySchema || schema.getItems() != null) {
            properties = schema.getItems().getProperties();
        }
        else {
            properties = schema.getProperties();
        }
        if (properties == null) {
            return Map.of();
        }
        return properties;
    }

    private static Map<String, Schema> getResultsSchema(Schema<?> schema, JsonPointer resultsPointer)
    {
        while (resultsPointer != JsonPointer.empty()) {
            String name = resultsPointer.getMatchingProperty();
            schema = getSchemaProperties(schema).get(name);
            if (schema == null) {
                throw new IllegalArgumentException("Invalid value of %s.%s: unknown field %s".formatted(EXTENSION_PAGINATION, RESULTS_PATH_PATTERN, resultsPointer));
            }
            // TODO validate that the schema is an array?
            resultsPointer = resultsPointer.tail();
        }
        return getSchemaProperties(schema);
    }

    private static boolean hasAmbiguousName(OpenApiColumn column, ListMultimap<String, OpenApiColumn.PrimaryKey> keys)
    {
        return keys.get(column.getName()).stream().anyMatch(existingKey -> !existingKey.equals(column.getPrimaryKey()));
    }

    private Optional<OpenApiColumn> getResultColumn(
            String sourceName,
            Schema<?> schema,
            boolean isNullable,
            boolean isPageNumber)
    {
        String name = getIdentifier(sourceName);
        return convertType(schema).map(type -> OpenApiColumn.builder()
                .setName(name)
                .setSourceName(sourceName)
                .setType(type.type())
                .setSourceType(type.schema())
                .setIsNullable(Optional.ofNullable(schema.getNullable()).orElse(isNullable))
                .setIsHidden(false)
                .setIsPageNumber(isPageNumber)
                .setComment(schema.getDescription())
                .build());
    }

    private Optional<OpenApiColumn> getResultColumn(
            String sourceName,
            JsonPointer resultsPointer,
            Schema<?> schema,
            boolean isNullable,
            boolean isPageNumber)
    {
        String name = getIdentifier(sourceName);
        return convertType(schema).map(type -> OpenApiColumn.builder()
                .setName(name)
                .setSourceName(sourceName)
                .setResultsPointer(resultsPointer)
                .setType(type.type())
                .setSourceType(type.schema())
                .setIsNullable(Optional.ofNullable(schema.getNullable()).orElse(isNullable))
                .setIsHidden(false)
                .setIsPageNumber(isPageNumber)
                .setComment(schema.getDescription())
                .build());
    }

    private Optional<OpenApiColumn> getPredicateColumn(
            String sourceName,
            Schema<?> schema,
            Map<PathItem.HttpMethod, String> requiredPredicate,
            Map<PathItem.HttpMethod, String> optionalPredicate,
            boolean isNullable,
            boolean isHidden,
            boolean isPageNumber)
    {
        String name = getIdentifier(sourceName);
        return convertType(schema).map(type -> OpenApiColumn.builder()
                .setName(name)
                .setSourceName(sourceName)
                .setType(type.type())
                .setSourceType(type.schema())
                .setRequiresPredicate(requiredPredicate)
                .setOptionalPredicate(optionalPredicate)
                .setIsNullable(Optional.ofNullable(schema.getNullable()).orElse(isNullable))
                .setIsHidden(isHidden)
                .setIsPageNumber(isPageNumber)
                .setComment(schema.getDescription())
                .build());
    }

    private boolean filterPath(String path, PathItem.HttpMethod method)
    {
        // ignore PUT operations on paths without parameters, because UPDATE always require a predicate and the required parameter will be the primary key
        // TODO what if there's no PUT, only POST, on a parametrized endpoint?
        return !method.equals(PathItem.HttpMethod.PUT) || path.contains("{");
    }

    public static String getIdentifier(String string)
    {
        return CaseFormat.LOWER_CAMEL.to(
                CaseFormat.LOWER_UNDERSCORE,
                string
                        .replaceAll("^/", "")
                        .replace('/', '_')
                        .replace('-', '_'));
    }

    private Optional<TypeTuple> convertType(Schema<?> property)
    {
        if (property.getOneOf() != null
                || property.getAnyOf() != null
                || property.getAllOf() != null) {
            // TODO oneOf types can be incompatible (object and an array), so it would require generating separate fields for every type
            // TODO allOf and anyOf types could be merged into a single type
            return Optional.of(new TypeTuple(VARCHAR, property));
        }
        if (property instanceof ArraySchema array) {
            return convertType(array.getItems()).map(elementType -> new TypeTuple(
                    new ArrayType(elementType.type()),
                    array.items(elementType.schema())));
        }
        if (property instanceof MapSchema map && map.getAdditionalProperties() instanceof Schema<?> valueSchema) {
            Optional<TypeTuple> mapType = convertType(valueSchema);
            if (mapType.isEmpty()) {
                // fallback for invalid types - the value will be serialized json,
                // which can be later processed using SQL json functions
                return Optional.of(FALLBACK_TYPE);
            }
            return mapType.map(type -> new TypeTuple(
                    new MapType(VARCHAR, type.type(), new TypeOperators()),
                    map.additionalProperties(type.schema())));
        }
        Optional<String> format = Optional.ofNullable(property.getFormat());
        if (property instanceof IntegerSchema) {
            if (format.filter("int64"::equals).isPresent()) {
                return Optional.of(new TypeTuple(BIGINT, property));
            }
            return Optional.of(new TypeTuple(INTEGER, property));
        }
        if (property instanceof NumberSchema) {
            if (format.filter("float"::equals).isPresent()) {
                return Optional.of(new TypeTuple(REAL, property));
            }
            if (format.filter("double"::equals).isPresent()) {
                return Optional.of(new TypeTuple(DOUBLE, property));
            }
            // arbitrary scale and precision but should fit most numbers
            return Optional.of(new TypeTuple(createDecimalType(18, 8), property));
        }
        if (property instanceof StringSchema) {
            return Optional.of(new TypeTuple(VARCHAR, property));
        }
        if (property instanceof DateSchema) {
            return Optional.of(new TypeTuple(DATE, property));
        }
        if (property instanceof DateTimeSchema) {
            // according to ISO-8601 can be any precision actually so might not fit
            return Optional.of(new TypeTuple(TIMESTAMP_MILLIS, property));
        }
        if (property instanceof BooleanSchema) {
            return Optional.of(new TypeTuple(BOOLEAN, property));
        }
        if (property instanceof ObjectSchema object) {
            // composite type
            Map<String, Schema> properties = object.getProperties();
            if (properties == null) {
                return Optional.of(FALLBACK_TYPE);
            }
            Map<String, TypeTuple> fieldTypes = properties.entrySet().stream()
                    .map(prop -> Map.entry(prop.getKey(), convertType(prop.getValue())))
                    .filter(entry -> entry.getValue().isPresent())
                    .collect(toMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue().get(),
                            (a, b) -> a,
                            LinkedHashMap::new));
            List<RowType.Field> fields = fieldTypes.entrySet().stream()
                    .map(prop -> RowType.field(prop.getKey(), prop.getValue().type()))
                    .toList();
            if (fields.isEmpty()) {
                return Optional.of(FALLBACK_TYPE);
            }
            Map<String, Schema> newProperties = fieldTypes.entrySet().stream()
                    .collect(toMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue().schema(),
                            (a, b) -> a,
                            LinkedHashMap::new));
            return Optional.of(new TypeTuple(RowType.from(fields), object.properties(newProperties)));
        }
        String type = property.getType();
        if (type == null && property.getTypes() != null && property.getTypes().size() == 1) {
            type = property.getTypes().iterator().next();
        }
        if (type == null) {
            return Optional.of(FALLBACK_TYPE);
        }
        if (type.equals("string")) {
            if (format.filter("date"::equals).isPresent()) {
                return Optional.of(new TypeTuple(DATE, property));
            }
            if (format.filter("date-time"::equals).isPresent()) {
                return Optional.of(new TypeTuple(TIMESTAMP_MILLIS, property));
            }
            return Optional.of(new TypeTuple(VARCHAR, property));
        }
        if (type.equals("object") && property.getAdditionalProperties() instanceof Schema<?> valueSchema) {
            Optional<TypeTuple> mapType = convertType(valueSchema);
            if (mapType.isEmpty()) {
                // fallback for invalid types - the value will be serialized json,
                // which can be later processed using SQL json functions
                return Optional.of(FALLBACK_TYPE);
            }
            return mapType.map(convertedType -> new TypeTuple(
                    new MapType(VARCHAR, convertedType.type(), new TypeOperators()),
                    new MapSchema().type("string").additionalProperties(convertedType.schema())));
        }
        if (type.equals("array")) {
            return convertType(property.getItems()).map(elementType -> new TypeTuple(
                    new ArrayType(elementType.type()),
                    new ArraySchema().items(elementType.schema())));
        }
        if (type.equals("number")) {
            // arbitrary scale and precision but should fit most numbers
            return Optional.of(new TypeTuple(createDecimalType(18, 8), property));
        }
        if (type.equals("float")) {
            return Optional.of(new TypeTuple(REAL, property));
        }
        if (type.equals("int") || type.equals("integer")) {
            return Optional.of(new TypeTuple(INTEGER, property));
        }
        Schema<?> referenced = openApi.getComponents().getSchemas().get(type);
        if (referenced != null) {
            return convertType(referenced).map(convertedType -> new TypeTuple(convertedType.type(), referenced));
        }
        // unknown and unsupported types will be returned as strings, which at least can be parsed with json functions
        return Optional.of(FALLBACK_TYPE);
    }

    private record TypeTuple(Type type, Schema<?> schema) {}

    private List<OpenApiColumn> mergeColumns(List<OpenApiColumn> columns)
    {
        return columns.stream()
                // merge all columns with same name and data type
                .collect(groupingBy(OpenApiColumn::getPrimaryKey))
                .values().stream()
                .map(sameColumns -> OpenApiColumn.builderFrom(sameColumns.get(0))
                        .setIsNullable(sameColumns.stream().anyMatch(column -> column.getMetadata().isNullable()))
                        .setRequiresPredicate(sameColumns.stream()
                                .map(OpenApiColumn::getRequiresPredicate)
                                .flatMap(map -> map.entrySet().stream())
                                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new)))
                        .setOptionalPredicate(sameColumns.stream()
                                .map(OpenApiColumn::getOptionalPredicate)
                                .flatMap(map -> map.entrySet().stream())
                                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new)))
                        .build())
                .collect(groupingBy(OpenApiColumn::getName))
                .values().stream()
                // make sure column names are also unique, append incrementing suffixes for columns of different types
                .flatMap(sameColumns -> IntStream
                        .range(0, sameColumns.size())
                        .mapToObj(i -> OpenApiColumn.builderFrom(sameColumns.get(i))
                                .setName(sameColumns.get(i).getName() + (i > 0 ? "_" + (i + 1) : ""))
                                .build()))
                .toList();
    }

    public Map<String, Map<PathItem.HttpMethod, List<SecurityRequirement>>> getPathSecurityRequirements()
    {
        return pathSecurityRequirements;
    }

    public Map<String, SecurityScheme> getSecuritySchemas()
    {
        return securitySchemas;
    }

    public List<SecurityRequirement> getSecurityRequirements()
    {
        return securityRequirements;
    }
}
