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

import io.swagger.v3.oas.models.media.Schema;
import io.trino.spi.type.BigintType;
import io.trino.spi.type.BooleanType;
import io.trino.spi.type.DateType;
import io.trino.spi.type.IntegerType;
import io.trino.spi.type.MapType;
import io.trino.spi.type.SqlDate;
import io.trino.spi.type.TimestampType;
import io.trino.spi.type.Type;
import io.trino.spi.type.VarcharType;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;

import static java.lang.String.format;

public class JsonTrinoConverter
{
    private JsonTrinoConverter()
    {
    }

    public static Object convert(JSONObject jsonObject, String columnName, Type type, Schema<?> schemaType)
    {
        if (type instanceof IntegerType) {
            return jsonObject.getInt(columnName);
        }
        else if (type instanceof BigintType) {
            return jsonObject.getBigInteger(columnName);
        }
        else if (type instanceof VarcharType) {
            return jsonObject.getString(columnName);
        }
        else if (type instanceof DateType) {
            DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern(schemaType.getFormat());
            TemporalAccessor temporalAccessor = dateFormatter.parse(jsonObject.getString(columnName));
            return getSqlDate(LocalDate.from(temporalAccessor));
        }
        else if (type instanceof TimestampType) {
            DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern(schemaType.getFormat());
            TemporalAccessor temporalAccessor = dateFormatter.parse(jsonObject.getString(columnName));
            if (temporalAccessor instanceof Instant) {
                return ((Instant) temporalAccessor).toEpochMilli();
            }
            else if (temporalAccessor instanceof OffsetDateTime) {
                return ((OffsetDateTime) temporalAccessor).toEpochSecond();
            }
            else if (temporalAccessor instanceof ZonedDateTime) {
                return ((ZonedDateTime) temporalAccessor).toEpochSecond();
            }
            else {
                throw new RuntimeException(format("Unsupported TemporalAccessor type %s", temporalAccessor.getClass().getCanonicalName()));
            }
        }
        else if (type instanceof BooleanType) {
            return jsonObject.getBoolean(columnName);
        }
        else if (type instanceof MapType) {
            throw new RuntimeException("MapType unsupported currently");
        }
        throw new RuntimeException(format("Type unsupported: %s", type));
    }

    public static SqlDate getSqlDate(LocalDate localDate)
    {
        return new SqlDate((int) localDate.toEpochDay());
    }
}
