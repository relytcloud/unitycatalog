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
import { useGetPermissions } from '../../hooks/permissions';
import {
  AccessLevel,
  accessLevelsFor,
  leafPrivilegeFor,
  useUpdateAccess,
} from '../../hooks/access';
import { useCanManageGrants } from '../../hooks/authz';
import { useNotification } from '../../utils/NotificationContext';
import { GrantAccessModal } from './GrantAccessModal';
import { SecurableType } from '../../types/api/catalog.gen';

interface AccessPanelProps {
  securableType: SecurableType;
  fullName: string;
  /** Owners along the resource chain (resource, schema, catalog) — used only
   * to decide whether the grant/revoke buttons render enabled; the server
   * re-authorizes every change. */
  owners?: (string | undefined)[];
}

/**
 * Simplified access list for one catalog / schema / table. A principal is
 * shown here when they hold the securable's defining leaf privilege
 * (table:SELECT → read, schema:CREATE_TABLE → create, catalog:CREATE_SCHEMA →
 * create); the USE_* plumbing grants are intentionally hidden — the per-user
 * detail view on the Users page lists every raw privilege. Revoking removes
 * only the leaf privilege (see hooks/access.ts).
 */
export default function AccessPanel({
  securableType,
  fullName,
  owners = [],
}: AccessPanelProps) {
  const { data, isLoading, isError, error } = useGetPermissions({
    securable_type: securableType,
    full_name: fullName,
  });
  const mutation = useUpdateAccess();
  const { setNotification } = useNotification();
  const [grantOpen, setGrantOpen] = useState(false);
  const manage = useCanManageGrants(securableType, fullName, owners);

  const level: AccessLevel | undefined = accessLevelsFor(securableType)[0];
  const leafPrivilege = leafPrivilegeFor(securableType);

  const rows = (data?.privilege_assignments ?? []).filter(
    (assignment) =>
      leafPrivilege && (assignment.privileges ?? []).includes(leafPrivilege),
  );

  const revoke = (principal: string) => {
    if (!level) return;
    mutation.mutate(
      {
        principal,
        target: { securableType, fullName },
        level,
        mode: 'revoke',
      },
      {
        onError: (err: Error) => {
          setNotification(err.message, 'error');
        },
        onSuccess: () => {
          setNotification(
            `Revoked ${level} on ${fullName} from ${principal}`,
            'success',
          );
        },
      },
    );
  };

  // The tag's close (X) handler stopsPropagation, so a wrapping Popconfirm
  // never sees the click; drive the confirm imperatively from onClose instead.
  const confirmRevoke = (principal: string) => {
    Modal.confirm({
      title: `Revoke ${level} on ${fullName} from ${principal}?`,
      content: `Only ${leafPrivilege} is removed; the USE grants on the catalog/schema stay because other objects may rely on them.`,
      okText: 'Revoke',
      okButtonProps: { danger: true },
      cancelText: 'Cancel',
      onOk: () => revoke(principal),
    });
  };

  if (!level || !leafPrivilege) return null;

  return (
    <Flex vertical gap="small">
      <Flex justify="space-between" align="center">
        <Typography.Title level={5} style={{ margin: 0 }}>
          Access
        </Typography.Title>
        <Tooltip
          title={
            manage.ready && !manage.allowed
              ? 'Granting requires ownership of this object (or metastore admin); the server enforces this.'
              : undefined
          }
        >
          <Button
            size="small"
            type="primary"
            icon={<PlusOutlined />}
            disabled={!manage.allowed}
            onClick={() => setGrantOpen(true)}
          >
            Grant
          </Button>
        </Tooltip>
      </Flex>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        Users with {level} access on this {securableType}. Only grants you are
        allowed to see are listed; a non-owner sees just their own. Raw
        privileges are listed in the per-user view on the Users page.
      </Typography.Text>
      {isError && (
        <Alert
          type="error"
          showIcon
          message="Failed to load access"
          description={error?.message}
        />
      )}
      <Table
        size="small"
        loading={isLoading}
        rowKey={(record) => `access-${record.principal}`}
        dataSource={isError ? [] : rows}
        pagination={{ hideOnSinglePage: true, pageSize: 10 }}
        locale={{
          emptyText: `No ${level} access granted on this ${securableType}`,
        }}
        columns={[
          {
            title: 'User',
            dataIndex: 'principal',
            key: 'principal',
            width: '60%',
          },
          {
            title: 'Access',
            key: 'access',
            render: (_, record) => (
              <Tag
                color="blue"
                closable={manage.allowed}
                onClose={(e) => {
                  e.preventDefault();
                  confirmRevoke(record.principal ?? '');
                }}
                style={{ cursor: manage.allowed ? 'pointer' : 'default' }}
              >
                {level}
              </Tag>
            ),
          },
        ]}
      />
      <GrantAccessModal
        open={grantOpen}
        closeModal={() => setGrantOpen(false)}
        target={{ securableType, fullName }}
      />
    </Flex>
  );
}
