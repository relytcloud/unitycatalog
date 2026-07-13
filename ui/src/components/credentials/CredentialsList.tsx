import { useState } from 'react';
import { Button, Tag, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';
import ListLayout from '../layouts/ListLayout';
import { formatTimestamp } from '../../utils/formatTimestamp';
import {
  CredentialInterface,
  useListCredentials,
} from '../../hooks/credentials';
import { useListExternalLocations } from '../../hooks/externalLocations';
import { credentialIdentityOf, credentialTypeOf } from '../../utils/credential';
import { CreateCredentialModal } from '../modals/CreateCredentialModal';
import ValidateCredentialButton from './ValidateCredentialButton';

const TYPE_COLORS: Record<string, string> = {
  'Aliyun RAM (STS)': 'orange',
  'Aliyun AK/SK (static)': 'gold',
  'AWS IAM role': 'geekblue',
};

export default function CredentialsList() {
  const { data, isLoading, error } = useListCredentials();
  const { data: locationsData } = useListExternalLocations();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const externalLocations = locationsData?.external_locations ?? [];

  return (
    <>
      <ListLayout<CredentialInterface>
        loading={isLoading}
        error={error}
        title={
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <Button type="primary" onClick={() => setOpen(true)}>
              Create Credential
            </Button>
          </div>
        }
        data={data?.credentials}
        onRowClick={(record) =>
          navigate(`/external-data/credentials/${record.name}`)
        }
        rowKey={(record) => `credential-${record.id}`}
        columns={[
          { title: 'Name', dataIndex: 'name', key: 'name', width: '25%' },
          {
            title: 'Type',
            key: 'type',
            width: '15%',
            render: (_, record) => {
              const type = credentialTypeOf(record);
              return <Tag color={TYPE_COLORS[type]}>{type}</Tag>;
            },
          },
          {
            title: 'Role ARN / Access key',
            key: 'identity',
            width: '30%',
            render: (_, record) => (
              <Typography.Text code>
                {credentialIdentityOf(record)}
              </Typography.Text>
            ),
          },
          { title: 'Owner', dataIndex: 'owner', key: 'owner', width: '10%' },
          {
            title: 'Created At',
            dataIndex: 'created_at',
            key: 'created_at',
            width: '12%',
            render: (value) => (value ? formatTimestamp(value) : ''),
          },
          {
            title: 'Actions',
            key: 'actions',
            width: '8%',
            render: (_, record) => (
              <ValidateCredentialButton
                credential={record}
                externalLocations={externalLocations}
              />
            ),
          },
        ]}
      />
      <CreateCredentialModal open={open} closeModal={() => setOpen(false)} />
    </>
  );
}
