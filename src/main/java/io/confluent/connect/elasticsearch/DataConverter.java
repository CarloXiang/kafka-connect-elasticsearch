/**
 * Copyright 2016 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 **/

package io.confluent.connect.elasticsearch;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.connect.data.ConnectSchema;
import org.apache.kafka.connect.data.Date;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.data.Time;
import org.apache.kafka.connect.data.Timestamp;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.storage.Converter;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.confluent.connect.elasticsearch.ElasticsearchSinkConnectorConstants.MAP_KEY;
import static io.confluent.connect.elasticsearch.ElasticsearchSinkConnectorConstants.MAP_VALUE;

public class DataConverter {

  private static final Converter JSON_CONVERTER;
  private static final Gson GSON = new Gson();

  static {
    JSON_CONVERTER = new JsonConverter();
    JSON_CONVERTER.configure(Collections.singletonMap("schemas.enable", "false"), false);
  }

  private static String convertKey(Schema keySchema, Object key) {
    if (key == null) {
      throw new ConnectException("Key is used as document id and can not be null.");
    }

    final Schema.Type schemaType;
    if (keySchema == null) {
      schemaType = ConnectSchema.schemaType(key.getClass());
      if (schemaType == null) {
        throw new DataException(
            "Java class "
            + key.getClass()
            + " does not have corresponding schema type."
        );
      }
    } else {
      schemaType = keySchema.type();
    }

    switch (schemaType) {
      case INT8:
      case INT16:
      case INT32:
      case INT64:
      case STRING:
        return String.valueOf(key);
      default:
        throw new DataException(schemaType.name() + " is not supported as the document id.");
    }
  }

  public static IndexableRecord convertRecord(SinkRecord record, String index, String type, boolean ignoreKey, boolean ignoreSchema, String versionField, String versionType) {
    final String id;
    if (ignoreKey) {
      id = record.topic() + "+" + String.valueOf((int) record.kafkaPartition()) + "+" + String.valueOf(record.kafkaOffset());
    } else {
      id = DataConverter.convertKey(record.keySchema(), record.key());
    }

    final Schema schema;
    final Object value;
    if (!ignoreSchema) {
      schema = preProcessSchema(record.valueSchema());
      value = preProcessValue(record.value(), record.valueSchema(), schema);
    } else {
      schema = record.valueSchema();
      value = record.value();
    }

    final String payload = new String(JSON_CONVERTER.fromConnectData(record.topic(), schema, value), StandardCharsets.UTF_8);

    final Long version = getVersion(payload, versionField, ignoreKey, record.kafkaOffset());

    return new IndexableRecord(new Key(index, type, id), payload, version, versionType);
  }

  static Long getVersion(String payload, String versionField, boolean ignoreKey, long offset) {
    if (ignoreKey) return null;
    if (StringUtils.isBlank(versionField)) return offset;
    if (StringUtils.isBlank(payload)) return null;

    JsonObject jsonObject;
    try {
      jsonObject = GSON.fromJson(payload, JsonObject.class);
    } catch (JsonSyntaxException e) {
      throw new ConnectException("The document content is not a valid json, document:" + payload, e);
    }

    String[] paths = versionField.split("\\.");
    JsonElement jsonElement = null;
    for (int i = 0, pathsLength = paths.length; i < pathsLength; i++) {
      String path = paths[i];
      jsonElement = jsonObject.get(path);

      if (jsonElement == null) // payload doesn't contain version field
        return null;

      if (i != pathsLength - 1) {
        if (!jsonElement.isJsonObject())
          return null;
        jsonObject = (JsonObject) jsonElement;
      }

    }

    if (jsonElement == null) { // payload doesn't contain version
      return null;
    } else {
      if (!jsonElement.isJsonPrimitive()) {
        return null; // specified field is not a eligible version field
      }
      try {
        return jsonElement.getAsLong();
      } catch (NumberFormatException e) {
        return null; // can't parse string to long, fallback to not use version control
      }
    }
  }

  // We need to pre process the Kafka Connect schema before converting to JSON as Elasticsearch
  // expects a different JSON format from the current JSON converter provides. Rather than
  // completely rewrite a converter for Elasticsearch, we will refactor the JSON converter to
  // support customized translation. The pre process is no longer needed once we have the JSON
  // converter refactored.
  static Schema preProcessSchema(Schema schema) {
    if (schema == null) {
      return null;
    }
    // Handle logical types
    String schemaName = schema.name();
    if (schemaName != null) {
      switch (schemaName) {
        case Decimal.LOGICAL_NAME:
          return copySchemaBasics(schema, SchemaBuilder.float64()).build();
        case Date.LOGICAL_NAME:
        case Time.LOGICAL_NAME:
        case Timestamp.LOGICAL_NAME:
          return schema;
        default:
          // User type or unknown logical type
          break;
      }
    }

    Schema.Type schemaType = schema.type();
    switch (schemaType) {
      case ARRAY:
        return preProcessArraySchema(schema);
      case MAP:
        return preProcessMapSchema(schema);
      case STRUCT:
        return preProcessStructSchema(schema);
      default:
        return schema;
    }
  }

  private static Schema preProcessArraySchema(Schema schema) {
    Schema valSchema = preProcessSchema(schema.valueSchema());
    return copySchemaBasics(schema, SchemaBuilder.array(valSchema)).build();
  }

  private static Schema preProcessMapSchema(Schema schema) {
    Schema keySchema = schema.keySchema();
    Schema valueSchema = schema.valueSchema();
    String keyName = keySchema.name() == null ? keySchema.type().name() : keySchema.name();
    String valueName = valueSchema.name() == null ? valueSchema.type().name() : valueSchema.name();
    Schema preprocessedKeySchema = preProcessSchema(keySchema);
    Schema preprocessedValueSchema = preProcessSchema(valueSchema);
    if (keySchema.type() == Schema.Type.STRING) {
      return SchemaBuilder.map(preprocessedKeySchema, preprocessedValueSchema).build();
    }
    Schema elementSchema = SchemaBuilder.struct().name(keyName + "-" + valueName)
        .field(MAP_KEY, preprocessedKeySchema)
        .field(MAP_VALUE, preprocessedValueSchema)
        .build();
    return copySchemaBasics(schema, SchemaBuilder.array(elementSchema)).build();
  }

  private static Schema preProcessStructSchema(Schema schema) {
    SchemaBuilder builder = copySchemaBasics(schema, SchemaBuilder.struct().name(schema.name()));
    for (Field field : schema.fields()) {
      builder.field(field.name(), preProcessSchema(field.schema()));
    }
    return builder.build();
  }

  private static SchemaBuilder copySchemaBasics(Schema source, SchemaBuilder target) {
    if (source.isOptional()) {
      target.optional();
    }
    if (source.defaultValue() != null && source.type() != Schema.Type.STRUCT) {
      final Object defaultVal = preProcessValue(source.defaultValue(), source, target);
      target.defaultValue(defaultVal);
    }
    return target;
  }

  // visible for testing
  static Object preProcessValue(Object value, Schema schema, Schema newSchema) {
    if (schema == null) {
      return value;
    }
    if (value == null) {
      Object result = preProcessNullValue(schema);
      if (result != null) {
        return result;
      }
    }

    // Handle logical types
    String schemaName = schema.name();
    if (schemaName != null) {
      Object result = preProcessLogicalValue(schemaName, value);
      if (result != null) {
        return result;
      }
    }

    Schema.Type schemaType = schema.type();
    switch (schemaType) {
      case ARRAY:
        return preProcessArrayValue(value, schema, newSchema);
      case MAP:
        return preProcessMapValue(value, schema, newSchema);
      case STRUCT:
        return preProcessStructValue(value, schema, newSchema);
      default:
        return value;
    }
  }

  private static Object preProcessNullValue(Schema schema) {
    if (schema.defaultValue() != null) {
      return schema.defaultValue();
    }
    if (schema.isOptional()) {
      return null;
    }
    throw new DataException("null value for field that is required and has no default value");
  }

  // @returns the decoded logical value or null if this isn't a known logical type
  private static Object preProcessLogicalValue(String schemaName, Object value) {
    switch (schemaName) {
      case Decimal.LOGICAL_NAME:
        return ((BigDecimal) value).doubleValue();
      case Date.LOGICAL_NAME:
      case Time.LOGICAL_NAME:
      case Timestamp.LOGICAL_NAME:
        return value;
      default:
        // User-defined type or unknown built-in
        return null;
    }
  }

  private static Object preProcessArrayValue(Object value, Schema schema, Schema newSchema) {
    Collection collection = (Collection) value;
    List<Object> result = new ArrayList<>();
    for (Object element: collection) {
      result.add(preProcessValue(element, schema.valueSchema(), newSchema.valueSchema()));
    }
    return result;
  }

  private static Object preProcessMapValue(Object value, Schema schema, Schema newSchema) {
    Schema keySchema = schema.keySchema();
    Schema valueSchema = schema.valueSchema();
    Schema newValueSchema = newSchema.valueSchema();
    Map<?, ?> map = (Map<?, ?>) value;
    if (keySchema.type() == Schema.Type.STRING) {
      Map<Object, Object> processedMap = new HashMap<>();
      for (Map.Entry<?, ?> entry: map.entrySet()) {
        processedMap.put(
            preProcessValue(entry.getKey(), keySchema, newSchema.keySchema()),
            preProcessValue(entry.getValue(), valueSchema, newValueSchema)
        );
      }
      return processedMap;
    }
    List<Struct> mapStructs = new ArrayList<>();
    for (Map.Entry<?, ?> entry: map.entrySet()) {
      Struct mapStruct = new Struct(newValueSchema);
      Schema mapKeySchema = newValueSchema.field(MAP_KEY).schema();
      Schema mapValueSchema = newValueSchema.field(MAP_VALUE).schema();
      mapStruct.put(MAP_KEY, preProcessValue(entry.getKey(), keySchema, mapKeySchema));
      mapStruct.put(MAP_VALUE, preProcessValue(entry.getValue(), valueSchema, mapValueSchema));
      mapStructs.add(mapStruct);
    }
    return mapStructs;
  }

  private static Object preProcessStructValue(Object value, Schema schema, Schema newSchema) {
    Struct struct = (Struct) value;
    Struct newStruct = new Struct(newSchema);
    for (Field field : schema.fields()) {
      Schema newFieldSchema = newSchema.field(field.name()).schema();
      Object converted = preProcessValue(struct.get(field), field.schema(), newFieldSchema);
      newStruct.put(field.name(), converted);
    }
    return newStruct;
  }
}
