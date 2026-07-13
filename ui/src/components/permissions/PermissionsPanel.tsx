import { useState } from 'react';
import { Alert, Button, Flex, Modal, Table, Tag, Typography } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import {
  PrivilegeType,
  useGetPermissions,
  useUpdatePermissions,
} from '../../hooks/permissions';
import { useNotification } from '../../utils/NotificationContext';
import { GrantPermissionModal } from './GrantPermissionModal';
import { SecurableType } from '../../types/api/catalog.gen';

interface PermissionsPanelProps {
  securableType: SecurableType;
  fullName: string;
}

/**
 * Grants viewer/editor for one securable: lists (principal, privileges),
 * revokes a single privilege via the tag close button, grants via the modal
 * (with %xx% user search). All changes are authorized server-side.
 */
export default function PermissionsPanel({
  securableType,
  fullName,
}: PermissionsPanelProps) {
  const { data, isLoading, isError, error } = useGetPermissions({
    securable_type: securableType,
    full_name: fullName,
  });
  const mutation = useUpdatePermissions();
  const { setNotification } = useNotification();
  const [grantOpen, setGrantOpen] = useState(false);

  const revoke = (principal: string, privilege: PrivilegeType) => {
    mutation.mutate(
      {
        securable_type: securableType,
        full_name: fullName,
        changes: [{ principal, add: [], remove: [privilege] }],
      },
      {
        onError: (err: Error) => {
          setNotification(err.message, 'error');
        },
        onSuccess: () => {
          setNotification(`Revoked ${privilege} from ${principal}`, 'success');
        },
      },
    );
  };

  // The tag's close (X) handler stopsPropagation, so a wrapping Popconfirm
  // never sees the click; drive the confirm imperatively from onClose instead.
  const confirmRevoke = (principal: string, privilege: PrivilegeType) => {
    Modal.confirm({
      title: `Revoke ${privilege} from ${principal}?`,
      okText: 'Revoke',
      okButtonProps: { danger: true },
      cancelText: 'Cancel',
      onOk: () => revoke(principal, privilege),
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
        Only grants you are allowed to see are listed; a non-owner sees just
        their own.
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
        rowKey={(record) => `perm-${record.principal}`}
        dataSource={isError ? [] : (data?.privilege_assignments ?? [])}
        pagination={{ hideOnSinglePage: true, pageSize: 10 }}
        locale={{ emptyText: 'No grants on this securable' }}
        columns={[
          {
            title: 'Principal',
            dataIndex: 'principal',
            key: 'principal',
            width: '40%',
          },
          {
            title: 'Privileges',
            key: 'privileges',
            render: (_, record) => (
              <>
                {(record.privileges ?? []).map((privilege) => (
                  <Tag
                    key={privilege}
                    closable
                    onClose={(e) => {
                      e.preventDefault();
                      confirmRevoke(record.principal ?? '', privilege);
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
        securableType={securableType}
        fullName={fullName}
      />
    </Flex>
  );
}
