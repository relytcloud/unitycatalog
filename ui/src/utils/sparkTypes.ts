import { ColumnTypeName } from '../types/api/catalog.gen';

/**
 * The primitive column types offered by the Create Table form, with their
 * Spark SQL text and Spark JSON type names (what UC stores in
 * ColumnInfo.type_text / type_json).
 */
const SPARK_TYPE_MAP: Partial<
  Record<ColumnTypeName, { text: string; json: string }>
> = {
  [ColumnTypeName.STRING]: { text: 'string', json: 'string' },
  [ColumnTypeName.INT]: { text: 'int', json: 'integer' },
  [ColumnTypeName.LONG]: { text: 'bigint', json: 'long' },
  [ColumnTypeName.DOUBLE]: { text: 'double', json: 'double' },
  [ColumnTypeName.FLOAT]: { text: 'float', json: 'float' },
  [ColumnTypeName.BOOLEAN]: { text: 'boolean', json: 'boolean' },
  [ColumnTypeName.DATE]: { text: 'date', json: 'date' },
  [ColumnTypeName.TIMESTAMP]: { text: 'timestamp', json: 'timestamp' },
  [ColumnTypeName.SHORT]: { text: 'smallint', json: 'short' },
  [ColumnTypeName.BYTE]: { text: 'tinyint', json: 'byte' },
  [ColumnTypeName.BINARY]: { text: 'binary', json: 'binary' },
  [ColumnTypeName.DECIMAL]: { text: 'decimal(10,0)', json: 'decimal(10,0)' },
};

export const CREATE_TABLE_COLUMN_TYPES = Object.keys(
  SPARK_TYPE_MAP,
) as ColumnTypeName[];

/**
 * Builds a full ColumnInfo for a primitive column the way the UC CLI does:
 * type_text is the Spark SQL name and type_json is the serialized
 * StructField.
 */
// DECIMAL is offered with a fixed precision/scale; a consumer that rebuilds the
// schema from type_name + type_precision/type_scale needs these populated (else
// it derives an invalid decimal(0,0)).
const DECIMAL_PRECISION = 10;
const DECIMAL_SCALE = 0;

export function buildColumnInfo(
  name: string,
  typeName: ColumnTypeName,
  position: number,
) {
  const sparkType = SPARK_TYPE_MAP[typeName] ?? {
    text: typeName.toLowerCase(),
    json: typeName.toLowerCase(),
  };
  const isDecimal = typeName === ColumnTypeName.DECIMAL;
  return {
    name,
    type_name: typeName,
    type_text: sparkType.text,
    type_json: JSON.stringify({
      name,
      type: sparkType.json,
      nullable: true,
      metadata: {},
    }),
    position,
    nullable: true,
    ...(isDecimal
      ? { type_precision: DECIMAL_PRECISION, type_scale: DECIMAL_SCALE }
      : {}),
  };
}
