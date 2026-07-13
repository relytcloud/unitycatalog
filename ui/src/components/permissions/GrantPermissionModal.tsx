import { Button, Form, Modal, Select, Typography } from 'antd';
import { useCallback, useEffect, useMemo, useRef } from 'react';
import { useNotification } from '../../utils/NotificationContext';
import { PrivilegeType, useUpdatePermissions } from '../../hooks/permissions';
import { useListScimUsers } from '../../hooks/users';
import { useListCatalogs } from '../../hooks/catalog';
import { useListCredentials } from '../../hooks/credentials';
import { useListExternalLocations } from '../../hooks/externalLocations';
import { Privilege, SecurableType } from '../../types/api/catalog.gen';

// The privileges that make sense per securable type (grantable subset).
const PRIVILEGES_BY_SECURABLE: Record<string, PrivilegeType[]> = {
  [SecurableType.metastore]: [
    Privilege.CREATE_CATALOG,
    Privilege.CREATE_EXTERNAL_LOCATION,
    Privilege.CREATE_STORAGE_CREDENTIAL,
  ],
  [SecurableType.catalog]: [Privilege.USE_CATALOG, Privilege.CREATE_SCHEMA],
  [SecurableType.schema]: [
    Privilege.USE_SCHEMA,
    Privilege.CREATE_TABLE,
    Privilege.CREATE_FUNCTION,
    Privilege.CREATE_VOLUME,
    Privilege.CREATE_MODEL,
  ],
  [SecurableType.table]: [Privilege.SELECT, Privilege.MODIFY],
  [SecurableType.volume]: [Privilege.READ_VOLUME],
  [SecurableType.function]: [Privilege.EXECUTE],
  [SecurableType.external_location]: [
    Privilege.CREATE_EXTERNAL_TABLE,
    Privilege.READ_FILES,
    Privilege.WRITE_FILES,
    Privilege.CREATE_EXTERNAL_VOLUME,
    Privilege.CREATE_MANAGED_STORAGE,
  ],
  [SecurableType.credential]: [Privilege.CREATE_EXTERNAL_LOCATION],
};

interface GrantPermissionFormValues {
  principal: string;
  securable?: string; // encoded as `${type}:${fullName}` when selectable
  privileges: PrivilegeType[];
}

interface GrantPermissionModalProps {
  open: boolean;
  closeModal: () => void;
  // When provided, the securable is fixed (grant FROM a securable page).
  securableType?: SecurableType;
  fullName?: string;
  // When provided, the principal is fixed (grant FROM a user page).
  principal?: string;
}

export function GrantPermissionModal({
  open,
  closeModal,
  securableType,
  fullName,
  principal,
}: GrantPermissionModalProps) {
  const mutation = useUpdatePermissions();
  const { setNotification } = useNotification();
  const submitRef = useRef<HTMLButtonElement>(null);
  const [form] = Form.useForm<GrantPermissionFormValues>();
  const securableLocked = !!securableType && !!fullName;
  const watchedSecurable = Form.useWatch('securable', form);

  const { data: usersData } = useListScimUsers();
  const { data: catalogsData } = useListCatalogs();
  const { data: credentialsData } = useListCredentials();
  const { data: locationsData } = useListExternalLocations();

  // Reset the form each time the modal opens so a prior grant's values don't
  // linger (the useForm store outlives destroyOnClose, which only unmounts
  // the fields).
  useEffect(() => {
    if (open) form.resetFields();
  }, [open, form]);

  // Users are searched with contains (%xx%) semantics on name AND email.
  // The Select value is the email (the grant principal), so dedupe by email
  // to avoid duplicate option values when two accounts share an address.
  const userOptions = useMemo(() => {
    const seen = new Set<string>();
    return (usersData?.Resources ?? []).flatMap((user) => {
      const email = (
        user.emails?.find((candidate) => candidate.primary) ?? user.emails?.[0]
      )?.value;
      if (!email || seen.has(email)) return [];
      seen.add(email);
      return [
        {
          value: email,
          label: `${user.displayName ?? email} (${email})`,
        },
      ];
    });
  }, [usersData]);

  const securableOptions = useMemo(() => {
    const options = [
      {
        value: `${SecurableType.metastore}:metastore`,
        label: 'metastore',
      },
      ...(catalogsData?.catalogs ?? []).map((catalog) => ({
        value: `${SecurableType.catalog}:${catalog.name}`,
        label: `catalog: ${catalog.name}`,
      })),
      ...(credentialsData?.credentials ?? []).map((credential) => ({
        value: `${SecurableType.credential}:${credential.name}`,
        label: `credential: ${credential.name}`,
      })),
      ...(locationsData?.external_locations ?? []).map((location) => ({
        value: `${SecurableType.external_location}:${location.name}`,
        label: `external location: ${location.name}`,
      })),
    ];
    return options;
  }, [catalogsData, credentialsData, locationsData]);

  const effectiveSecurableType = securableLocked
    ? securableType
    : ((watchedSecurable?.split(':')[0] as SecurableType | undefined) ??
      undefined);
  const privilegeOptions = effectiveSecurableType
    ? (PRIVILEGES_BY_SECURABLE[effectiveSecurableType] ?? [])
    : [];

  const handleSubmit = useCallback(() => {
    submitRef.current?.click();
  }, []);

  return (
    <Modal
      title={<Typography.Title level={4}>Grant privileges</Typography.Title>}
      okText="Grant"
      cancelText="Cancel"
      open={open}
      destroyOnClose
      onCancel={closeModal}
      onOk={handleSubmit}
      okButtonProps={{ loading: mutation.isPending }}
    >
      <Typography.Paragraph type="secondary">
        {securableLocked
          ? `Grant privileges on ${securableType}: ${fullName}.`
          : 'Pick a securable and the privileges to grant.'}{' '}
        The server authorizes the change (securable owner).
      </Typography.Paragraph>
      <Form<GrantPermissionFormValues>
        form={form}
        layout="vertical"
        onFinish={(values) => {
          const target = securableLocked
            ? { securable_type: securableType!, full_name: fullName! }
            : (() => {
                const [type, ...rest] = (values.securable ?? '').split(':');
                return {
                  securable_type: type as SecurableType,
                  full_name: rest.join(':'),
                };
              })();
          if (!target.securable_type || !target.full_name) {
            setNotification('Please select a securable', 'error');
            return;
          }
          mutation.mutate(
            {
              ...target,
              changes: [
                {
                  principal: principal ?? values.principal,
                  add: values.privileges,
                  remove: [],
                },
              ],
            },
            {
              onError: (error: Error) => {
                setNotification(error.message, 'error');
              },
              onSuccess: () => {
                setNotification('Privileges granted', 'success');
                closeModal();
              },
            },
          );
        }}
        name="Grant permission form"
      >
        {!principal && (
          <Form.Item
            required
            label={<Typography.Text strong>User</Typography.Text>}
            name="principal"
            rules={[{ required: true, message: 'User is required' }]}
          >
            <Select
              showSearch
              placeholder="Search user by name or email"
              options={userOptions}
              filterOption={(input, option) =>
                (option?.label ?? '')
                  .toLowerCase()
                  .includes(input.toLowerCase())
              }
            />
          </Form.Item>
        )}
        {!securableLocked && (
          <Form.Item
            required
            label={<Typography.Text strong>Securable</Typography.Text>}
            name="securable"
            rules={[{ required: true, message: 'Securable is required' }]}
          >
            <Select
              showSearch
              placeholder="Select a securable"
              options={securableOptions}
              // Clear already-picked privileges when the securable type
              // changes, so privileges valid only for the previous type can't
              // be submitted against the new one.
              onChange={() => form.setFieldValue('privileges', [])}
              filterOption={(input, option) =>
                (option?.label ?? '')
                  .toLowerCase()
                  .includes(input.toLowerCase())
              }
            />
          </Form.Item>
        )}
        <Form.Item
          required
          label={<Typography.Text strong>Privileges</Typography.Text>}
          name="privileges"
          rules={[
            { required: true, message: 'At least one privilege is required' },
          ]}
        >
          <Select
            mode="multiple"
            placeholder="Select privileges"
            options={privilegeOptions.map((privilege) => ({
              value: privilege,
              label: privilege,
            }))}
          />
        </Form.Item>
        <Form.Item hidden>
          <Button type="primary" htmlType="submit" ref={submitRef}>
            Grant
          </Button>
        </Form.Item>
      </Form>
    </Modal>
  );
}
