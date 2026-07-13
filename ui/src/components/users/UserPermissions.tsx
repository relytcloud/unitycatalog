import { useMemo, useState } from 'react';
import { Alert, Button, Flex, Modal, Table, Tag, Typography } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import {
  PrivilegeType,
  SecurableRef,
  useUpdatePermissions,
  useUserPermissions,
} from '../../hooks/permissions';
import { useListCatalogs } from '../../hooks/catalog';
import { useListCredentials } from '../../hooks/credentials';
import { useListExternalLocations } from '../../hooks/externalLocations';
import { useNotification } from '../../utils/NotificationContext';
import { GrantPermissionModal } from '../permissions/GrantPermissionModal';
import { SecurableType } from '../../types/api/catalog.gen';

interface UserPermissionsProps {
  principal: string;
}

/**
 * Aggregated view of one user's privileges across the metastore, catalogs,
 * credentials and external locations (one filtered permissions query per
 * securable; schemas/tables are managed from their own detail pages).
 */
export default function UserPermissions({ principal }: UserPermissionsProps) {
  const { data: catalogsData } = useListCatalogs();
  const { data: credentialsData } = useListCredentials();
  const { data: locationsData } = useListExternalLocations();
  const mutation = useUpdatePermissions();
  const { setNotification } = useNotification();
  const [grantOpen, setGrantOpen] = useState(false);

  const securables = useMemo((): SecurableRef[] => {
    return [
      { securable_type: SecurableType.metastore, full_name: 'metastore' },
      ...(catalogsData?.catalogs ?? []).map((catalog) => ({
        securable_type: SecurableType.catalog,
        full_name: catalog.name ?? '',
      })),
      ...(credentialsData?.credentials ?? []).map((credential) => ({
        securable_type: SecurableType.credential,
        full_name: credential.name ?? '',
      })),
      ...(locationsData?.external_locations ?? []).map((location) => ({
        securable_type: SecurableType.external_location,
        full_name: location.name ?? '',
      })),
    ].filter((securable) => securable.full_name);
  }, [catalogsData, credentialsData, locationsData]);

  const {
    data: userPrivileges,
    isLoading,
    isError,
    error,
  } = useUserPermissions(principal, securables);

  const revoke = (securable: SecurableRef, privilege: PrivilegeType) => {
    mutation.mutate(
      {
        securable_type: securable.securable_type,
        full_name: securable.full_name,
        changes: [{ principal, add: [], remove: [privilege] }],
      },
      {
        onError: (err: Error) => {
          setNotification(err.message, 'error');
        },
        onSuccess: () => {
          setNotification(
            `Revoked ${privilege} on ${securable.full_name}`,
            'success',
          );
        },
      },
    );
  };

  // The tag's close (X) handler stopsPropagation, so a wrapping Popconfirm
  // never sees the click; drive the confirm imperatively from onClose instead.
  const confirmRevoke = (securable: SecurableRef, privilege: PrivilegeType) => {
    Modal.confirm({
      title: `Revoke ${privilege} on ${securable.full_name}?`,
      okText: 'Revoke',
      okButtonProps: { danger: true },
      cancelText: 'Cancel',
      onOk: () => revoke(securable, privilege),
    });
  };

  return (
    <Flex vertical gap="small">
      <Flex justify="space-between" align="center">
        <Typography.Title level={5} style={{ margin: 0 }}>
          Permissions
        </Typography.Title>
        <Button
          size="small"
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => setGrantOpen(true)}
        >
          Grant
        </Button>
      </Flex>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        Privileges on the metastore, catalogs, credentials and external
        locations. Schema/table grants are managed from their own pages.
      </Typography.Text>
      {isError && (
        <Alert
          type="error"
          showIcon
          message="Failed to load permissions"
          description={error?.message}
        />
      )}
      <Table
        size="small"
        loading={isLoading}
        rowKey={(record) =>
          `user-perm-${record.securable_type}-${record.full_name}`
        }
        dataSource={isError ? [] : (userPrivileges ?? [])}
        pagination={{ hideOnSinglePage: true, pageSize: 10 }}
        locale={{ emptyText: 'No privileges granted to this user' }}
        columns={[
          {
            title: 'Securable',
            key: 'securable',
            width: '45%',
            render: (_, record) => (
              <>
                <Tag>{record.securable_type}</Tag>
                {record.full_name}
              </>
            ),
          },
          {
            title: 'Privileges',
            key: 'privileges',
            render: (_, record) => (
              <>
                {record.privileges.map((privilege) => (
                  <Tag
                    key={privilege}
                    closable
                    onClose={(e) => {
                      e.preventDefault();
                      confirmRevoke(record, privilege);
                    }}
                    style={{ cursor: 'pointer' }}
                  >
                    {privilege}
                  </Tag>
                ))}
              </>
            ),
          },
        ]}
      />
      <GrantPermissionModal
        open={grantOpen}
        closeModal={() => setGrantOpen(false)}
        principal={principal}
      />
    </Flex>
  );
}
