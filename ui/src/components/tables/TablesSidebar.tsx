import { useMemo } from 'react';
import { Flex, Typography } from 'antd';
import { Link } from 'react-router-dom';
import { formatTimestamp } from '../../utils/formatTimestamp';
import MetadataList, { MetadataListType } from '../MetadataList';
import { TableInterface, useGetTable } from '../../hooks/tables';
import { useListExternalLocations } from '../../hooks/externalLocations';
import { matchExternalLocation } from '../../utils/externalLocation';

interface TableSidebarProps {
  catalog: string;
  schema: string;
  table: string;
}

const TABLE_METADATA: MetadataListType<Omit<TableInterface, 'columns'>> = [
  {
    key: 'created_at',
    label: 'Created at',
    dataIndex: 'created_at',
    render: (value) => (
      <Typography.Text>{formatTimestamp(value)}</Typography.Text>
    ),
  },
  {
    key: 'updated_at',
    label: 'Updated at',
    dataIndex: 'updated_at',
    render: (value) => (
      <Typography.Text>{formatTimestamp(value)}</Typography.Text>
    ),
  },
  {
    key: 'data_source_format',
    label: 'Data source format',
    dataIndex: 'data_source_format',
    render: (value) => <Typography.Text code>{value}</Typography.Text>,
  },
  {
    key: 'table_type',
    label: 'Table type',
    dataIndex: 'table_type',
    render: (value) => <Typography.Text code>{value}</Typography.Text>,
  },
  {
    key: 'owner',
    label: 'Owner',
    dataIndex: 'owner',
  },
  {
    key: 'table_id',
    label: 'Table ID',
    dataIndex: 'table_id',
    render: (value) => (
      <Typography.Text code copyable>
        {value}
      </Typography.Text>
    ),
  },
];

export default function TableSidebar({
  catalog,
  schema,
  table,
}: TableSidebarProps) {
  const { data } = useGetTable({
    full_name: [catalog, schema, table].join('.'),
  });
  const {
    data: locationsData,
    isLoading: locationsLoading,
    isError: locationsError,
  } = useListExternalLocations();

  const matchedLocation = useMemo(
    () =>
      matchExternalLocation(
        data?.storage_location,
        locationsData?.external_locations,
      ),
    [data?.storage_location, locationsData?.external_locations],
  );

  if (!data) return null;

  const { columns, ...metadata } = data;

  return (
    <Flex vertical gap="large">
      <MetadataList
        data={metadata}
        metadata={TABLE_METADATA}
        title="Table details"
      />
      {data.storage_location && (
        <Flex vertical gap="middle">
          <Typography.Title level={5}>Storage</Typography.Title>
          <div>
            <Typography.Text strong>Location: </Typography.Text>
            <Typography.Text
              code
              copyable={{ text: data.storage_location }}
              style={{ wordBreak: 'break-all' }}
            >
              {data.storage_location}
            </Typography.Text>
          </div>
          <div>
            <Typography.Text strong>External location: </Typography.Text>
            {matchedLocation ? (
              <Link
                to={`/external-data/external-locations/${matchedLocation.name}`}
              >
                {matchedLocation.name}
              </Link>
            ) : locationsLoading ? (
              <Typography.Text type="secondary">Resolving…</Typography.Text>
            ) : locationsError ? (
              <Typography.Text type="secondary">
                — could not load external locations
              </Typography.Text>
            ) : (
              <Typography.Text type="secondary">
                — not under any external location you can view
              </Typography.Text>
            )}
          </div>
          {matchedLocation?.credential_name && (
            <div>
              <Typography.Text strong>Credential: </Typography.Text>
              <Link
                to={`/external-data/credentials/${matchedLocation.credential_name}`}
              >
                {matchedLocation.credential_name}
              </Link>
            </div>
          )}
        </Flex>
      )}
    </Flex>
  );
}
