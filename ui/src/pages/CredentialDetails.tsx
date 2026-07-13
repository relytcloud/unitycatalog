import { Alert, Descriptions, Flex, Table, Tag, Typography } from 'antd';
import { SafetyOutlined } from '@ant-design/icons';
import { Link, useNavigate, useParams } from 'react-router-dom';
import DetailsLayout from '../components/layouts/DetailsLayout';
import MetadataList, { MetadataListType } from '../components/MetadataList';
import { formatTimestamp } from '../utils/formatTimestamp';
import { CredentialInterface, useGetCredential } from '../hooks/credentials';
import { useListExternalLocations } from '../hooks/externalLocations';
import { credentialTypeOf, maskAccessKeyId } from '../utils/credential';
import ValidateCredentialButton from '../components/credentials/ValidateCredentialButton';
import PermissionsPanel from '../components/permissions/PermissionsPanel';
import { SecurableType } from '../types/api/catalog.gen';

const CREDENTIAL_METADATA: MetadataListType<CredentialInterface> = [
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
    label: 'Credential ID',
    dataIndex: 'id',
    render: (value) => <Typography.Text code>{value}</Typography.Text>,
  },
];

export default function CredentialDetails() {
  const { name } = useParams();
  const navigate = useNavigate();
  if (!name) throw new Error('Credential name is required');

  const { data, isError, error } = useGetCredential({ name });
  const { data: locationsData } = useListExternalLocations();

  if (isError) {
    return (
      <Alert
        type="error"
        showIcon
        message={`Failed to load credential "${name}"`}
        description={error?.message}
      />
    );
  }
  if (!data) return null;

  const type = credentialTypeOf(data);
  const usedBy = (locationsData?.external_locations ?? []).filter(
    (location) => location.credential_name === data.name,
  );

  return (
    <DetailsLayout
      title={
        <Flex justify="space-between" align="flex-start" gap="middle">
          <Typography.Title level={3}>
            <SafetyOutlined /> {data.name}
          </Typography.Title>
          <ValidateCredentialButton
            credential={data}
            externalLocations={locationsData?.external_locations ?? []}
            size="middle"
          />
        </Flex>
      }
      breadcrumbs={[
        {
          title: (
            <Link to="/external-data/credentials">
              External Data / Credentials
            </Link>
          ),
          key: '_credentials',
        },
        { title: data.name, key: '_credential' },
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
            title="Credential"
            bordered
            column={1}
            size="small"
            items={[
              {
                key: 'type',
                label: 'Type',
                children: <Tag>{type}</Tag>,
              },
              ...(data.aws_iam_role?.role_arn
                ? [
                    {
                      key: 'aws_role_arn',
                      label: 'Role ARN',
                      children: (
                        <Typography.Text code copyable>
                          {data.aws_iam_role.role_arn}
                        </Typography.Text>
                      ),
                    },
                  ]
                : []),
              ...(data.aliyun_ram_role?.role_arn
                ? [
                    {
                      key: 'aliyun_role_arn',
                      label: 'Role ARN',
                      children: (
                        <Typography.Text code copyable>
                          {data.aliyun_ram_role.role_arn}
                        </Typography.Text>
                      ),
                    },
                  ]
                : []),
              ...(data.aliyun_ram_role?.access_key_id
                ? [
                    {
                      key: 'access_key_id',
                      label: 'Access key ID',
                      children: (
                        <Typography.Text code>
                          {maskAccessKeyId(data.aliyun_ram_role.access_key_id)}
                        </Typography.Text>
                      ),
                    },
                  ]
                : []),
              ...(data.aliyun_ram_role?.unity_catalog_ram_arn
                ? [
                    {
                      key: 'uc_ram_arn',
                      label: 'Unity Catalog RAM ARN',
                      children: (
                        <Typography.Text code copyable>
                          {data.aliyun_ram_role.unity_catalog_ram_arn}
                        </Typography.Text>
                      ),
                    },
                  ]
                : []),
              ...(data.purpose
                ? [
                    {
                      key: 'purpose',
                      label: 'Purpose',
                      children: <Tag>{data.purpose}</Tag>,
                    },
                  ]
                : []),
            ]}
          />
          {type === 'Aliyun RAM (STS)' &&
            data.aliyun_ram_role?.unity_catalog_ram_arn && (
              <Alert
                type="info"
                showIcon
                message="Trust policy requirement"
                description={
                  <>
                    The Unity Catalog server assumes this RAM role as{' '}
                    <Typography.Text code>
                      {data.aliyun_ram_role.unity_catalog_ram_arn}
                    </Typography.Text>
                    . Add it as a trusted principal in the RAM role's trust
                    policy, or credential vending will fail with AccessDenied.
                  </>
                }
              />
            )}
          <div>
            <Typography.Title level={5}>
              Used by external locations
            </Typography.Title>
            <Table
              size="small"
              rowKey={(record) => `used-by-${record.id}`}
              dataSource={usedBy}
              pagination={{ hideOnSinglePage: true, pageSize: 10 }}
              locale={{
                emptyText: 'No external location uses this credential',
              }}
              onRow={(record) => ({
                onClick: () =>
                  navigate(`/external-data/external-locations/${record.name}`),
                style: { cursor: 'pointer' },
              })}
              columns={[
                { title: 'Name', dataIndex: 'name', key: 'name' },
                {
                  title: 'URL',
                  dataIndex: 'url',
                  key: 'url',
                  render: (value) => (
                    <Typography.Text code>{value}</Typography.Text>
                  ),
                },
              ]}
            />
          </div>
          <PermissionsPanel
            securableType={SecurableType.credential}
            fullName={data.name ?? name}
          />
        </Flex>
      </DetailsLayout.Content>
      <DetailsLayout.Aside>
        <MetadataList
          data={data}
          metadata={CREDENTIAL_METADATA}
          title="Credential details"
        />
      </DetailsLayout.Aside>
    </DetailsLayout>
  );
}
