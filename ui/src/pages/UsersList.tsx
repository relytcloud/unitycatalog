import { useMemo, useState } from 'react';
import {
  Avatar,
  Button,
  Descriptions,
  Divider,
  Drawer,
  Flex,
  Segmented,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import { TeamOutlined, UserOutlined } from '@ant-design/icons';
import ListLayout from '../components/layouts/ListLayout';
import {
  ScimUserInterface,
  useListScimUsers,
  useMetastoreAdmins,
} from '../hooks/users';
import { CreateUserModal } from '../components/modals/CreateUserModal';
import UserPermissions from '../components/users/UserPermissions';
import UserAccessDetails from '../components/users/UserAccessDetails';
import UserActionsDropdown from '../components/users/UserActionsDropdown';
import { useAuthorized } from '../hooks/authz';
import { SecurableType } from '../types/api/catalog.gen';

// ListLayout's built-in search filters on `name`, so expose displayName there.
interface UserRow extends ScimUserInterface {
  name: string;
}

type StatusFilter = 'active' | 'inactive';

function primaryEmailOf(user: ScimUserInterface): string {
  const emails = user.emails ?? [];
  return (emails.find((email) => email.primary) ?? emails[0])?.value ?? '';
}

// A user the server has not told us about is shown in the default view rather
// than in neither: the two tabs must always add up to the whole directory.
function isInactive(user: ScimUserInterface): boolean {
  return user.active === false;
}

export default function UsersList() {
  const { data, isLoading, error } = useListScimUsers();
  // Empty for a non-administrator, who is not allowed to read it — the column
  // then simply shows nothing rather than claiming there are no administrators.
  const { data: admins } = useMetastoreAdmins();
  const [selectedUser, setSelectedUser] = useState<UserRow | null>(null);
  const [accessDetailsUser, setAccessDetailsUser] = useState<UserRow | null>(
    null,
  );
  const [createOpen, setCreateOpen] = useState(false);
  const [status, setStatus] = useState<StatusFilter>('active');
  // Server rule: only the metastore OWNER may create users; gate on the
  // metastore owner-side signal (fail-open when identity is unknown).
  const canCreateUser = useAuthorized([
    { securableType: SecurableType.metastore, fullName: 'metastore' },
  ]);

  const users = useMemo(
    (): UserRow[] =>
      (data?.Resources ?? []).map((user) => ({
        ...user,
        name: user.displayName ?? '',
      })),
    [data],
  );

  const inactiveCount = useMemo(() => users.filter(isInactive).length, [users]);

  const visibleUsers = useMemo(
    () =>
      users.filter((user) =>
        status === 'inactive' ? isInactive(user) : !isInactive(user),
      ),
    [users, status],
  );

  return (
    <>
      <ListLayout<UserRow>
        loading={isLoading}
        error={error}
        searchText={(record) =>
          `${record.displayName ?? ''} ${primaryEmailOf(record)}`
        }
        title={
          <Flex justify="space-between" align="flex-start" gap="middle">
            <Typography.Title level={2}>
              <TeamOutlined /> Users
            </Typography.Title>
            {/* The inactive count is on the control on purpose: deactivated
                accounts are the queue that permanent deletion works through,
                and nobody switches to a tab they cannot tell is empty. */}
            <Segmented<StatusFilter>
              value={status}
              onChange={setStatus}
              options={[
                { label: 'Active', value: 'active' },
                {
                  label: inactiveCount
                    ? `Inactive (${inactiveCount})`
                    : 'Inactive',
                  value: 'inactive',
                },
              ]}
            />
            <Tooltip
              title={
                canCreateUser.ready && !canCreateUser.allowed
                  ? 'Only the metastore admin may create users (server-enforced).'
                  : undefined
              }
            >
              <Button
                type="primary"
                disabled={!canCreateUser.allowed}
                onClick={() => setCreateOpen(true)}
              >
                Create User
              </Button>
            </Tooltip>
          </Flex>
        }
        data={visibleUsers}
        onRowClick={(record) => setSelectedUser(record)}
        rowKey={(record) => `user-${record.id}`}
        columns={[
          {
            title: 'Name',
            dataIndex: 'name',
            key: 'name',
            width: '30%',
            render: (value, record) => (
              <Flex align="center" gap="small">
                <Avatar
                  size="small"
                  src={record.photos?.[0]?.value}
                  icon={<UserOutlined />}
                />
                {value}
              </Flex>
            ),
          },
          {
            title: 'Email',
            key: 'email',
            width: '30%',
            render: (_, record) => primaryEmailOf(record),
          },
          {
            title: 'Status',
            dataIndex: 'active',
            key: 'active',
            width: '16%',
            render: (value, record) => (
              <Flex align="center" gap="small" wrap="wrap">
                {value ? (
                  <Tag color="green">Active</Tag>
                ) : (
                  <Tag color="default">Inactive</Tag>
                )}
                {admins?.has(primaryEmailOf(record)) && (
                  <Tooltip title="Holds OWNER on the metastore: authorized for every securable.">
                    <Tag color="gold">Admin</Tag>
                  </Tooltip>
                )}
              </Flex>
            ),
          },
          {
            title: 'Created',
            key: 'created',
            width: '15%',
            render: (_, record) => record.meta?.created ?? '',
          },
          {
            title: 'Last Modified',
            key: 'lastModified',
            width: '15%',
            render: (_, record) => record.meta?.lastModified ?? '',
          },
          {
            title: '',
            key: 'actions',
            width: '10%',
            render: (_, record) => {
              const principal = primaryEmailOf(record);
              return principal ? (
                <UserActionsDropdown
                  principal={principal}
                  userId={record.id}
                  active={!isInactive(record)}
                  isAdmin={admins?.has(principal)}
                  displayName={record.displayName}
                  onShowAccessDetails={() => setAccessDetailsUser(record)}
                />
              ) : null;
            },
          },
        ]}
      />
      <Drawer
        title={
          <Flex align="center" gap="small">
            <Avatar
              src={selectedUser?.photos?.[0]?.value}
              icon={<UserOutlined />}
            />
            {selectedUser?.displayName}
          </Flex>
        }
        open={!!selectedUser}
        onClose={() => setSelectedUser(null)}
        width={640}
      >
        {selectedUser && (
          <Descriptions
            column={1}
            bordered
            size="small"
            items={[
              {
                key: 'id',
                label: 'User ID',
                children: (
                  <Typography.Text code>{selectedUser.id}</Typography.Text>
                ),
              },
              {
                key: 'displayName',
                label: 'Display name',
                children: selectedUser.displayName ?? '',
              },
              {
                key: 'emails',
                label: 'Emails',
                children: (selectedUser.emails ?? [])
                  .map((email) => email.value)
                  .join(', '),
              },
              ...(selectedUser.externalId
                ? [
                    {
                      key: 'externalId',
                      label: 'External ID',
                      children: selectedUser.externalId,
                    },
                  ]
                : []),
              {
                key: 'active',
                label: 'Status',
                children: selectedUser.active ? (
                  <Tag color="green">Active</Tag>
                ) : (
                  <Tag color="default">Inactive</Tag>
                ),
              },
              {
                key: 'created',
                label: 'Created',
                children: selectedUser.meta?.created ?? '',
              },
              {
                key: 'lastModified',
                label: 'Last modified',
                children: selectedUser.meta?.lastModified ?? '',
              },
            ]}
          />
        )}
        {selectedUser && primaryEmailOf(selectedUser) && (
          <>
            <Divider />
            <UserPermissions principal={primaryEmailOf(selectedUser)} />
          </>
        )}
      </Drawer>
      {accessDetailsUser && (
        <UserAccessDetails
          open={!!accessDetailsUser}
          onClose={() => setAccessDetailsUser(null)}
          principal={primaryEmailOf(accessDetailsUser)}
          displayName={accessDetailsUser.displayName}
        />
      )}
      <CreateUserModal
        open={createOpen}
        closeModal={() => setCreateOpen(false)}
      />
    </>
  );
}
