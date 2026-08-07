import { useEffect } from 'react';
import {
  Alert,
  Avatar,
  Button,
  Drawer,
  Flex,
  Modal,
  Spin,
  Table,
  Tag,
  Typography,
} from 'antd';
import { UserOutlined } from '@ant-design/icons';
import {
  PrivilegeType,
  SecurableRef,
  useUpdatePermissions,
} from '../../hooks/permissions';
import { useUserAccessScan } from '../../hooks/userAccess';
import { leafPrivilegeFor, accessLevelsFor } from '../../hooks/access';
import { useNotification } from '../../utils/NotificationContext';

interface UserAccessDetailsProps {
  open: boolean;
  onClose: () => void;
  principal: string;
  displayName?: string;
}

/**
 * Admin view of EVERYTHING one user can access: catalogs, schemas and tables,
 * with per-privilege revoke.
 *
 * *** Server-load warning (do not remove) ***
 * Unity Catalog cannot look privileges up by principal, so this view walks
 * catalog → schema → table and issues one permissions GET per object (plus
 * the LIST calls). On a metastore with many tables this is a lot of requests
 * against the UC server — the scan therefore never starts automatically and
 * the UI carries the same warning. See ui/README.md ("Per-user permission
 * views") and hooks/userAccess.ts.
 */
export default function UserAccessDetails({
  open,
  onClose,
  principal,
  displayName,
}: UserAccessDetailsProps) {
  const { status, progress, rows, error, start, reset, removePrivilege } =
    useUserAccessScan(principal);
  const mutation = useUpdatePermissions();
  const { setNotification } = useNotification();

  // A fresh principal (or reopen) starts from the explicit "scan" step again;
  // stale results from another user must never linger in the drawer.
  useEffect(() => {
    reset();
  }, [principal, open, reset]);

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
          removePrivilege(securable, privilege);
          setNotification(
            `Revoked ${privilege} on ${securable.full_name} from ${principal}`,
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
      title: `Revoke ${privilege} on ${securable.full_name} from ${principal}?`,
      okText: 'Revoke',
      okButtonProps: { danger: true },
      cancelText: 'Cancel',
      onOk: () => revoke(securable, privilege),
    });
  };

  return (
    <Drawer
      title={
        <Flex align="center" gap="small">
          <Avatar icon={<UserOutlined />} />
          {`All permissions — ${displayName || principal}`}
        </Flex>
      }
      open={open}
      onClose={onClose}
      width={720}
    >
      <Flex vertical gap="middle">
        <Alert
          type="warning"
          showIcon
          message="Full metastore scan"
          description="Unity Catalog has no per-user permissions lookup, so this view traverses every catalog, schema and table and queries each one's permissions. On a metastore with many tables this creates significant load on the UC server — not recommended there. Non-admins only see their own grants, so this view is meaningful for admins/owners."
        />
        {status === 'idle' && (
          <Button type="primary" onClick={() => start()}>
            Scan permissions
          </Button>
        )}
        {status === 'running' && (
          <Flex align="center" gap="small">
            <Spin size="small" />
            <Typography.Text type="secondary">
              Scanning… {progress.catalogs} catalogs, {progress.schemas}{' '}
              schemas, {progress.tables} tables checked
              {progress.failures > 0 ? `, ${progress.failures} failed` : ''}
            </Typography.Text>
          </Flex>
        )}
        {status === 'done' && (
          <Flex align="center" gap="small" justify="space-between">
            <Typography.Text type="secondary">
              Scanned {progress.catalogs} catalogs, {progress.schemas} schemas,{' '}
              {progress.tables} tables.
            </Typography.Text>
            <Button size="small" onClick={() => start()}>
              Rescan
            </Button>
          </Flex>
        )}
        {error && (
          <Alert
            type="error"
            showIcon
            message="Scan failed"
            description={error}
          />
        )}
        {status === 'done' && progress.failures > 0 && (
          <Alert
            type="warning"
            showIcon
            message={`${progress.failures} objects could not be checked — the results below may be incomplete.`}
          />
        )}
        {status !== 'idle' && (
          <Table
            size="small"
            rowKey={(record) =>
              `scan-${record.securable_type}-${record.full_name}`
            }
            dataSource={rows}
            pagination={{ hideOnSinglePage: true, pageSize: 15 }}
            locale={{
              emptyText:
                status === 'done'
                  ? 'No privileges found for this user'
                  : 'Nothing found yet…',
            }}
            columns={[
              {
                title: 'Securable',
                key: 'securable',
                width: '55%',
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
                    {record.privileges.map((privilege) => {
                      const isLeaf =
                        leafPrivilegeFor(record.securable_type) === privilege;
                      const level = accessLevelsFor(record.securable_type)[0];
                      return (
                        <Tag
                          key={privilege}
                          color={isLeaf ? 'blue' : undefined}
                          closable
                          onClose={(e) => {
                            e.preventDefault();
                            confirmRevoke(record, privilege);
                          }}
                          style={{ cursor: 'pointer' }}
                        >
                          {privilege}
                          {isLeaf && level ? ` (${level})` : ''}
                        </Tag>
                      );
                    })}
                  </>
                ),
              },
            ]}
          />
        )}
      </Flex>
    </Drawer>
  );
}
