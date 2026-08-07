import { useState } from 'react';
import { Button, Flex, Tag, Tooltip, Typography } from 'antd';
import { DeleteOutlined, EditOutlined } from '@ant-design/icons';
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
import { EditCredentialModal } from '../modals/EditCredentialModal';
import { DeleteCredentialModal } from '../modals/DeleteCredentialModal';
import ValidateCredentialButton from './ValidateCredentialButton';
import { useAuthorized, useCurrentPrincipal } from '../../hooks/authz';
import { Privilege, SecurableType } from '../../types/api/catalog.gen';

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
  const [editTarget, setEditTarget] = useState<CredentialInterface | null>(
    null,
  );
  const [deleteTarget, setDeleteTarget] = useState<CredentialInterface | null>(
    null,
  );
  const externalLocations = locationsData?.external_locations ?? [];

  // Row-level gating uses only list-level signals (owner field + metastore
  // probe) — no per-row permission queries. The server re-authorizes anyway.
  const { data: principal } = useCurrentPrincipal();
  const adminOrOpen = useAuthorized([
    { securableType: SecurableType.metastore, fullName: 'metastore' },
  ]);
  const canCreate = useAuthorized([
    {
      securableType: SecurableType.metastore,
      fullName: 'metastore',
      anyOf: [Privilege.CREATE_STORAGE_CREDENTIAL],
    },
  ]);
  const canTouch = (owner?: string) =>
    adminOrOpen.allowed || (!!principal && owner?.toLowerCase() === principal);

  return (
    <>
      <ListLayout<CredentialInterface>
        loading={isLoading}
        error={error}
        title={
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <Tooltip
              title={
                canCreate.ready && !canCreate.allowed
                  ? 'Requires CREATE STORAGE CREDENTIAL on the metastore (server-enforced).'
                  : undefined
              }
            >
              <Button
                type="primary"
                disabled={!canCreate.allowed}
                onClick={() => setOpen(true)}
              >
                Create Credential
              </Button>
            </Tooltip>
          </div>
        }
        data={data?.credentials}
        onRowClick={(record) =>
          navigate(`/external-data/credentials/${record.name}`)
        }
        rowKey={(record) => `credential-${record.id}`}
        columns={[
          { title: 'Name', dataIndex: 'name', key: 'name', width: '20%' },
          {
            title: 'Type',
            key: 'type',
            width: '14%',
            render: (_, record) => {
              const type = credentialTypeOf(record);
              return <Tag color={TYPE_COLORS[type]}>{type}</Tag>;
            },
          },
          {
            title: 'Role ARN / Access key',
            key: 'identity',
            width: '26%',
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
            width: '18%',
            render: (_, record) => {
              const allowed = canTouch(record.owner);
              return (
                <Flex
                  gap="small"
                  align="center"
                  // Keep clicks on the action buttons from also triggering
                  // the row's navigation.
                  onClick={(e) => e.stopPropagation()}
                >
                  <ValidateCredentialButton
                    credential={record}
                    externalLocations={externalLocations}
                  />
                  <Tooltip
                    title={
                      !allowed
                        ? 'Requires credential ownership or metastore admin (server-enforced).'
                        : 'Edit'
                    }
                  >
                    <Button
                      size="small"
                      icon={<EditOutlined />}
                      disabled={!allowed}
                      onClick={() => setEditTarget(record)}
                    />
                  </Tooltip>
                  <Tooltip
                    title={
                      !allowed
                        ? 'Requires credential ownership or metastore admin (server-enforced).'
                        : 'Delete'
                    }
                  >
                    <Button
                      size="small"
                      danger
                      icon={<DeleteOutlined />}
                      disabled={!allowed}
                      onClick={() => setDeleteTarget(record)}
                    />
                  </Tooltip>
                </Flex>
              );
            },
          },
        ]}
      />
      <CreateCredentialModal open={open} closeModal={() => setOpen(false)} />
      {editTarget && (
        <EditCredentialModal
          open={!!editTarget}
          closeModal={() => setEditTarget(null)}
          credential={editTarget}
        />
      )}
      {deleteTarget && (
        <DeleteCredentialModal
          open={!!deleteTarget}
          closeModal={() => setDeleteTarget(null)}
          credential={deleteTarget}
          externalLocations={externalLocations}
        />
      )}
    </>
  );
}
