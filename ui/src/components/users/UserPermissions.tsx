import { useState } from 'react';
import {
  Alert,
  Button,
  Flex,
  Modal,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import {
  PrivilegeType,
  SecurableRef,
  useUpdatePermissions,
  useUserPermissions,
} from '../../hooks/permissions';
import { useAllSecurableRefs } from '../../hooks/userAccess';
import { useAuthorized } from '../../hooks/authz';
import { useNotification } from '../../utils/NotificationContext';
import { GrantPermissionModal } from '../permissions/GrantPermissionModal';
import { SecurableType } from '../../types/api/catalog.gen';

interface UserPermissionsProps {
  principal: string;
}

/**
 * Summary of one user's privileges across catalogs and schemas (one filtered
 * permissions query per securable — schemas are enumerated per catalog, so
 * the fan-out is bounded but real). Tables are NOT queried here: the full
 * catalog→schema→table scan lives behind the explicit "Details" button on
 * the Users list (see UserAccessDetails and its server-load warning).
 * External locations / credentials are no longer granted through the UI.
 */
export default function UserPermissions({ principal }: UserPermissionsProps) {
  const {
    data: securables,
    isLoading: securablesLoading,
    isError: securablesError,
    error: securablesErrorObj,
  } = useAllSecurableRefs();
  const mutation = useUpdatePermissions();
  const { setNotification } = useNotification();
  const [grantOpen, setGrantOpen] = useState(false);
  // Managing another user's grants is an owner/metastore-admin operation;
  // gate on the metastore owner-side signal (fail-open when identity is
  // unknown). The server re-authorizes every change regardless.
  const manage = useAuthorized([
    { securableType: SecurableType.metastore, fullName: 'metastore' },
  ]);

  const {
    data: userPrivileges,
    isLoading,
    isError,
    error,
  } = useUserPermissions(principal, securables ?? []);

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
        <Tooltip
          title={
            manage.ready && !manage.allowed
              ? 'Granting from the Users page requires metastore admin. To grant access on a specific catalog, schema or table, use that object’s own Access panel. The server enforces this.'
              : undefined
          }
        >
          {/* Wrap in a span: a disabled antd button has pointer-events:none and
              never fires the hover that the Tooltip needs, so the explanation
              would otherwise be invisible in exactly the disabled case. */}
          <span style={{ display: 'inline-block' }}>
            <Button
              size="small"
              type="primary"
              icon={<PlusOutlined />}
              disabled={!manage.allowed}
              onClick={() => setGrantOpen(true)}
            >
              Grant
            </Button>
          </span>
        </Tooltip>
      </Flex>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        Metastore, catalog and schema privileges. Table access is granted from
        each table's page; use "Details" on the Users list for the full per-user
        view including tables.
      </Typography.Text>
      {(isError || securablesError) && (
        <Alert
          type="error"
          showIcon
          message="Failed to load permissions"
          // Surface an enumeration failure too: without this, a failed
          // /catalogs call leaves `securables` undefined, the permissions query
          // stays disabled, and the empty table would misleadingly read as "no
          // privileges" instead of an error.
          description={(error ?? securablesErrorObj)?.message}
        />
      )}
      <Table
        size="small"
        loading={isLoading || securablesLoading}
        rowKey={(record) =>
          `user-perm-${record.securable_type}-${record.full_name}`
        }
        dataSource={isError || securablesError ? [] : (userPrivileges ?? [])}
        pagination={{ hideOnSinglePage: true, pageSize: 10 }}
        locale={{
          emptyText:
            'No metastore/catalog/schema privileges granted to this user',
        }}
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
                    closable={manage.allowed}
                    onClose={(e) => {
                      e.preventDefault();
                      confirmRevoke(record, privilege);
                    }}
                    style={{ cursor: manage.allowed ? 'pointer' : 'default' }}
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
