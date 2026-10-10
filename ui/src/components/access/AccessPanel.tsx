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
  ancestors,
  isAdminOnly,
  leafPrivilegeFor,
  useUpdateAccess,
} from '../../hooks/access';
import { useCanManageGrants, useIsMetastoreAdmin } from '../../hooks/authz';
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

interface AccessRow {
  principal: string;
  level: AccessLevel;
}

/**
 * Simplified access list for one catalog / schema / table: one row per
 * (principal, level), where a principal holds a level when they hold its leaf
 * privilege on this securable (read → SELECT, create → CREATE_TABLE /
 * CREATE_SCHEMA). The USE_* plumbing grants are intentionally hidden — the
 * per-user detail view on the Users page lists every raw privilege. Revoking
 * removes only the leaf privilege (see hooks/access.ts).
 *
 * A read row on a schema or catalog means every table beneath it, tables
 * created later included; the server lets only a metastore owner grant or
 * revoke those, so their controls follow that signal rather than ownership.
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
  // Granting any simplified level writes USE_CATALOG on the owning catalog,
  // so the grantor must be able to manage the CATALOG — its owner or a
  // metastore admin. A schema/table owner who is NOT the catalog owner cannot
  // complete the grant (the USE_CATALOG PATCH 403s), so gate the Grant button
  // on catalog authority. The catalog owner is always the last entry of the
  // resource→…→catalog `owners` chain.
  const canGrant = useCanManageGrants(
    SecurableType.catalog,
    ancestors(fullName).catalog,
    [owners[owners.length - 1]],
  );
  // Revoking removes only the leaf privilege on THIS securable, which its own
  // owner (or an ancestor / metastore admin) can do — except read on a schema
  // or catalog, which only a metastore admin may touch.
  const canRevoke = useCanManageGrants(securableType, fullName, owners);
  const { data: isAdmin = false } = useIsMetastoreAdmin();

  const levels = accessLevelsFor(securableType);

  const rows: AccessRow[] = (data?.privilege_assignments ?? []).flatMap(
    (assignment) =>
      levels
        .filter((level) => {
          const leaf = leafPrivilegeFor(securableType, level);
          return leaf && (assignment.privileges ?? []).includes(leaf);
        })
        .map((level) => ({ principal: assignment.principal ?? '', level })),
  );

  const mayRevoke = (level: AccessLevel) =>
    isAdminOnly(securableType, level) ? isAdmin : canRevoke.allowed;

  const revoke = (principal: string, level: AccessLevel) => {
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
  const confirmRevoke = (principal: string, level: AccessLevel) => {
    const leaf = leafPrivilegeFor(securableType, level);
    Modal.confirm({
      title: `Revoke ${level} on ${fullName} from ${principal}?`,
      content: `Only ${leaf} is removed; the USE grants on the catalog/schema stay because other objects may rely on them.`,
      okText: 'Revoke',
      okButtonProps: { danger: true },
      cancelText: 'Cancel',
      onOk: () => revoke(principal, level),
    });
  };

  if (levels.length === 0) return null;

  const levelList = levels.join(' / ');
  const readReachesDown =
    securableType === SecurableType.schema ||
    securableType === SecurableType.catalog;

  return (
    <Flex vertical gap="small">
      <Flex justify="space-between" align="center">
        <Typography.Title level={5} style={{ margin: 0 }}>
          Access
        </Typography.Title>
        <Tooltip
          title={
            canGrant.ready && !canGrant.allowed
              ? 'Granting requires ownership of the catalog (or metastore admin), because it also writes USE_CATALOG; the server enforces this.'
              : undefined
          }
        >
          {/* Wrap in a span: a disabled antd button has pointer-events:none and
              never fires the hover the Tooltip needs, so the explanation would
              otherwise be invisible in exactly the disabled case. */}
          <span style={{ display: 'inline-block' }}>
            <Button
              size="small"
              type="primary"
              icon={<PlusOutlined />}
              disabled={!canGrant.allowed}
              onClick={() => setGrantOpen(true)}
            >
              Grant
            </Button>
          </span>
        </Tooltip>
      </Flex>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        Users with {levelList} access on this {securableType}.
        {readReachesDown &&
          ` Read here covers every table beneath this ${securableType}, including tables created later; only a metastore admin can grant or revoke it.`}{' '}
        Only grants you are allowed to see are listed; a non-owner sees just
        their own. Raw privileges are listed in the per-user view on the Users
        page.
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
        rowKey={(record) => `access-${record.principal}-${record.level}`}
        dataSource={isError ? [] : rows}
        pagination={{ hideOnSinglePage: true, pageSize: 10 }}
        locale={{
          emptyText: `No ${levelList} access granted on this ${securableType}`,
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
            render: (_, record: AccessRow) => (
              <Tag
                color={record.level === 'read' ? 'blue' : 'green'}
                closable={mayRevoke(record.level)}
                onClose={(e) => {
                  e.preventDefault();
                  confirmRevoke(record.principal, record.level);
                }}
                style={{
                  cursor: mayRevoke(record.level) ? 'pointer' : 'default',
                }}
              >
                {record.level}
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
