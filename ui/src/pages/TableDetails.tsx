import React from 'react';
import { Link, useParams } from 'react-router-dom';
import { useGetTable } from '../hooks/tables';
import { useGetSchema } from '../hooks/schemas';
import { useGetCatalog } from '../hooks/catalog';
import DetailsLayout from '../components/layouts/DetailsLayout';
import { Flex, Typography } from 'antd';
import ColumnsList from '../components/tables/ColumnsList';
import TableSidebar from '../components/tables/TablesSidebar';
import { TableOutlined } from '@ant-design/icons';
import DropTableButton from '../components/tables/DropTableButton';
import AccessPanel from '../components/access/AccessPanel';
import { SecurableType } from '../types/api/catalog.gen';

export default function TableDetails() {
  const { catalog, schema, table } = useParams();
  if (!catalog) throw new Error('Catalog name is required');
  if (!schema) throw new Error('Schema name is required');
  if (!table) throw new Error('Table name is required');

  const { data } = useGetTable({
    full_name: [catalog, schema, table].join('.'),
  });
  // Ancestor owners participate in button gating (a catalog/schema owner may
  // drop the table or manage its grants); the server re-authorizes anyway.
  const { data: schemaData } = useGetSchema({
    full_name: [catalog, schema].join('.'),
  });
  const { data: catalogData } = useGetCatalog({ name: catalog });

  if (!data) return null;

  const tableFullName = [catalog, schema, table].join('.');
  const owners = [data.owner, schemaData?.owner, catalogData?.owner];
  return (
    <DetailsLayout
      title={
        <Flex justify="space-between" align="flex-start" gap="middle">
          <Typography.Title level={3}>
            <TableOutlined /> {tableFullName}
          </Typography.Title>
          <DropTableButton
            catalog={catalog}
            schema={schema}
            table={table}
            owners={owners}
          />
        </Flex>
      }
      breadcrumbs={[
        { title: <Link to="/">Catalogs</Link>, key: '_home' },
        {
          title: <Link to={`/data/${catalog}`}>{catalog}</Link>,
          key: '_catalog',
        },
        {
          title: <Link to={`/data/${catalog}/${schema}`}>{schema}</Link>,
          key: '_schema',
        },
        { title: table, key: '_table' },
      ]}
    >
      <DetailsLayout.Content>
        <Flex vertical gap="middle">
          <div>
            <Typography.Title level={5}>Description</Typography.Title>
            <Typography.Text type="secondary">
              {data.comment ?? ''}
            </Typography.Text>
          </div>
          <ColumnsList catalog={catalog} schema={schema} table={table} />
          <AccessPanel
            securableType={SecurableType.table}
            fullName={tableFullName}
            owners={owners}
          />
        </Flex>
      </DetailsLayout.Content>
      <DetailsLayout.Aside>
        <TableSidebar catalog={catalog} schema={schema} table={table} />
      </DetailsLayout.Aside>
    </DetailsLayout>
  );
}
