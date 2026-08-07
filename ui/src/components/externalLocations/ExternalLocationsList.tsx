import { useState } from 'react';
import { Button, Flex, Tooltip, Typography } from 'antd';
import { DeleteOutlined, EditOutlined } from '@ant-design/icons';
import { Link, useNavigate } from 'react-router-dom';
import ListLayout from '../layouts/ListLayout';
import { formatTimestamp } from '../../utils/formatTimestamp';
import {
  ExternalLocationInterface,
  useListExternalLocations,
} from '../../hooks/externalLocations';
import { CreateExternalLocationModal } from '../modals/CreateExternalLocationModal';
import { EditExternalLocationModal } from '../modals/EditExternalLocationModal';
import { DeleteExternalLocationModal } from '../modals/DeleteExternalLocationModal';
import { useAuthorized, useCurrentPrincipal } from '../../hooks/authz';
import { Privilege, SecurableType } from '../../types/api/catalog.gen';

export default function ExternalLocationsList() {
  const { data, isLoading, error } = useListExternalLocations();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [editTarget, setEditTarget] =
    useState<ExternalLocationInterface | null>(null);
  const [deleteTarget, setDeleteTarget] =
    useState<ExternalLocationInterface | null>(null);

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
      anyOf: [Privilege.CREATE_EXTERNAL_LOCATION],
    },
  ]);
  const canTouch = (owner?: string) =>
    adminOrOpen.allowed || (!!principal && owner?.toLowerCase() === principal);

  return (
    <>
      <ListLayout<ExternalLocationInterface>
        loading={isLoading}
        error={error}
        title={
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <Tooltip
              title={
                canCreate.ready && !canCreate.allowed
                  ? 'Requires CREATE EXTERNAL LOCATION on the metastore (server-enforced).'
                  : undefined
              }
            >
              <Button
                type="primary"
                disabled={!canCreate.allowed}
                onClick={() => setOpen(true)}
              >
                Create External Location
              </Button>
            </Tooltip>
          </div>
        }
        data={data?.external_locations}
        onRowClick={(record) =>
          navigate(`/external-data/external-locations/${record.name}`)
        }
        rowKey={(record) => `external-location-${record.id}`}
        columns={[
          { title: 'Name', dataIndex: 'name', key: 'name', width: '18%' },
          {
            title: 'URL',
            dataIndex: 'url',
            key: 'url',
            width: '30%',
            render: (value) => <Typography.Text code>{value}</Typography.Text>,
          },
          {
            title: 'Credential',
            dataIndex: 'credential_name',
            key: 'credential_name',
            width: '14%',
            render: (value) =>
              value ? (
                <Link
                  to={`/external-data/credentials/${value}`}
                  onClick={(e) => e.stopPropagation()}
                >
                  {value}
                </Link>
              ) : (
                ''
              ),
          },
          { title: 'Owner', dataIndex: 'owner', key: 'owner', width: '13%' },
          {
            title: 'Created At',
            dataIndex: 'created_at',
            key: 'created_at',
            width: '13%',
            render: (value) => (value ? formatTimestamp(value) : ''),
          },
          {
            title: 'Actions',
            key: 'actions',
            width: '12%',
            render: (_, record) => {
              const allowed = canTouch(record.owner);
              return (
                <Flex
                  gap="small"
                  // Keep clicks on the action buttons from also triggering
                  // the row's navigation.
                  onClick={(e) => e.stopPropagation()}
                >
                  <Tooltip
                    title={
                      !allowed
                        ? 'Requires location ownership or metastore admin (server-enforced).'
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
                        ? 'Requires location ownership or metastore admin (server-enforced).'
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
      <CreateExternalLocationModal
        open={open}
        closeModal={() => setOpen(false)}
      />
      {editTarget && (
        <EditExternalLocationModal
          open={!!editTarget}
          closeModal={() => setEditTarget(null)}
          externalLocation={editTarget}
        />
      )}
      {deleteTarget && (
        <DeleteExternalLocationModal
          open={!!deleteTarget}
          closeModal={() => setDeleteTarget(null)}
          externalLocation={deleteTarget}
        />
      )}
    </>
  );
}
