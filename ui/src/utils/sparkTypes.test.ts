import { ColumnTypeName } from '../types/api/catalog.gen';
import { buildColumnInfo, CREATE_TABLE_COLUMN_TYPES } from './sparkTypes';

describe('buildColumnInfo', () => {
  it('maps INT to spark int/integer', () => {
    const column = buildColumnInfo('id', ColumnTypeName.INT, 0);
    expect(column).toMatchObject({
      name: 'id',
      type_name: ColumnTypeName.INT,
      type_text: 'int',
      position: 0,
      nullable: true,
    });
    expect(JSON.parse(column.type_json)).toEqual({
      name: 'id',
      type: 'integer',
      nullable: true,
      metadata: {},
    });
  });

  it('maps LONG to spark bigint/long', () => {
    const column = buildColumnInfo('count', ColumnTypeName.LONG, 2);
    expect(column.type_text).toBe('bigint');
    expect(JSON.parse(column.type_json).type).toBe('long');
    expect(column.position).toBe(2);
  });

  it('maps STRING to spark string/string', () => {
    const column = buildColumnInfo('name', ColumnTypeName.STRING, 1);
    expect(column.type_text).toBe('string');
    expect(JSON.parse(column.type_json).type).toBe('string');
  });

  it('every offered column type has a mapping', () => {
    for (const type of CREATE_TABLE_COLUMN_TYPES) {
      const column = buildColumnInfo('c', type, 0);
      expect(column.type_text).toBeTruthy();
      expect(() => JSON.parse(column.type_json)).not.toThrow();
    }
  });
});
