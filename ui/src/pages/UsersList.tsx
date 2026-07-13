import { useMemo, useState } from 'react';
import {
  Avatar,
  Button,
  Descriptions,
  Divider,
  Drawer,
  Flex,
  Tag,
  Typography,
} from 'antd';
import { TeamOutlined, UserOutlined } from '@ant-design/icons';
import ListLayout from '../components/layouts/ListLayout';
import { ScimUserInterface, useListScimUsers } from '../hooks/users';
import { CreateUserModal } from '../components/modals/CreateUserModal';
import UserPermissions from '../components/users/UserPermissions';

// ListLayout's built-in search filters on `name`, so expose displayName there.
interface UserRow extends ScimUserInterface {
  name: string;
}

function primaryEmailOf(user: ScimUserInterface): string {
  const emails = user.emails ?? [];
  return (emails.find((email) => email.primary) ?? emails[0])?.value ?? '';
}

export default function UsersList() {
  const { data, isLoading, error } = useListScimUsers();
  const [selectedUser, setSelectedUser] = useState<UserRow | null>(null);
  const [createOpen, setCreateOpen] = useState(false);

  const users = useMemo(
    (): UserRow[] =>
      (data?.Resources ?? []).map((user) => ({
        ...user,
        name: user.displayName ?? '',
      })),
    [data],
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
            <Button type="primary" onClick={() => setCreateOpen(true)}>
              Create User
            </Button>
          </Flex>
        }
        data={users}
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
            width: '10%',
            render: (value) =>
              value ? (
                <Tag color="green">Active</Tag>
              ) : (
                <Tag color="default">Inactive</Tag>
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
      <CreateUserModal
        open={createOpen}
        closeModal={() => setCreateOpen(false)}
      />
    </>
  );
}
