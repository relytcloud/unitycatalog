import { Alert, Descriptions, Flex, Typography } from 'antd';
import { FolderOpenOutlined } from '@ant-design/icons';
import { Link, useParams } from 'react-router-dom';
import DetailsLayout from '../components/layouts/DetailsLayout';
import MetadataList, { MetadataListType } from '../components/MetadataList';
import { formatTimestamp } from '../utils/formatTimestamp';
import {
  ExternalLocationInterface,
  useGetExternalLocation,
} from '../hooks/externalLocations';
import PermissionsPanel from '../components/permissions/PermissionsPanel';
import { SecurableType } from '../types/api/catalog.gen';

const EXTERNAL_LOCATION_METADATA: MetadataListType<ExternalLocationInterface> =
  [
    {
      key: 'created_at',
      label: 'Created at',
      dataIndex: 'created_at',
      render: (value) => (
        <Typography.Text>{formatTimestamp(value)}</Typography.Text>
      ),
    },
    {
      key: 'created_by',
      label: 'Created by',
      dataIndex: 'created_by',
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
      key: 'updated_by',
      label: 'Updated by',
      dataIndex: 'updated_by',
    },
    {
      key: 'owner',
      label: 'Owner',
      dataIndex: 'owner',
    },
    {
      key: 'id',
      label: 'External location ID',
      dataIndex: 'id',
      render: (value) => <Typography.Text code>{value}</Typography.Text>,
    },
  ];

export default function ExternalLocationDetails() {
  const { name } = useParams();
  if (!name) throw new Error('External location name is required');

  const { data, isError, error } = useGetExternalLocation({ name });

  if (isError) {
    return (
      <Alert
        type="error"
        showIcon
        message={`Failed to load external location "${name}"`}
        description={error?.message}
      />
    );
  }
  if (!data) return null;

  return (
    <DetailsLayout
      title={
        <Typography.Title level={3}>
          <FolderOpenOutlined /> {data.name}
        </Typography.Title>
      }
      breadcrumbs={[
        {
          title: (
            <Link to="/external-data/external-locations">
              External Data / External Locations
            </Link>
          ),
          key: '_external_locations',
        },
        { title: data.name, key: '_external_location' },
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
          <Descriptions
            title="External location"
            bordered
            column={1}
            size="small"
            items={[
              {
                key: 'url',
                label: 'URL',
                children: (
                  <Typography.Text code copyable>
                    {data.url}
                  </Typography.Text>
                ),
              },
              {
                key: 'credential',
                label: 'Credential',
                children: data.credential_name ? (
                  <Link
                    to={`/external-data/credentials/${data.credential_name}`}
                  >
                    {data.credential_name}
                  </Link>
                ) : (
                  ''
                ),
              },
            ]}
          />
          <PermissionsPanel
            securableType={SecurableType.external_location}
            fullName={data.name ?? name}
          />
        </Flex>
      </DetailsLayout.Content>
      <DetailsLayout.Aside>
        <MetadataList
          data={data}
          metadata={EXTERNAL_LOCATION_METADATA}
          title="External location details"
        />
      </DetailsLayout.Aside>
    </DetailsLayout>
  );
}
