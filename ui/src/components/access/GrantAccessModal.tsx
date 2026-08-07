import { Button, Form, Modal, Radio, Select, Typography } from 'antd';
import { useCallback, useEffect, useRef } from 'react';
import { useNotification } from '../../utils/NotificationContext';
import {
  AccessLevel,
  AccessTarget,
  accessLevelsFor,
  grantsFor,
  useUpdateAccess,
} from '../../hooks/access';
import { useScimUserOptions } from '../../hooks/users';

interface GrantAccessFormValues {
  principal: string;
  level: AccessLevel;
}

interface GrantAccessModalProps {
  open: boolean;
  closeModal: () => void;
  /** The securable being granted on (catalog / schema / table page). */
  target: AccessTarget;
}

/**
 * Grants a simplified access level (read/create) on a catalog, schema or
 * table. The modal spells out the exact underlying privileges that will be
 * written — including the USE_* completions on ancestors the server needs
 * because privileges do not inherit — so the "one click" is transparent.
 * The server authorizes each PATCH (securable owner / metastore admin).
 */
export function GrantAccessModal({
  open,
  closeModal,
  target,
}: GrantAccessModalProps) {
  const mutation = useUpdateAccess();
  const { setNotification } = useNotification();
  const submitRef = useRef<HTMLButtonElement>(null);
  const [form] = Form.useForm<GrantAccessFormValues>();
  const userOptions = useScimUserOptions();

  const levels = accessLevelsFor(target.securableType);
  const watchedLevel = Form.useWatch('level', form) ?? levels[0];

  // Reset the form each time the modal opens so a prior grant's values don't
  // linger (the useForm store outlives destroyOnClose, which only unmounts
  // the fields).
  useEffect(() => {
    if (open) form.resetFields();
  }, [open, form]);

  const handleSubmit = useCallback(() => {
    submitRef.current?.click();
  }, []);

  const plannedGrants = watchedLevel ? grantsFor(target, watchedLevel) : [];

  return (
    <Modal
      title={<Typography.Title level={4}>Grant access</Typography.Title>}
      okText="Grant"
      cancelText="Cancel"
      open={open}
      destroyOnClose
      onCancel={closeModal}
      onOk={handleSubmit}
      okButtonProps={{ loading: mutation.isPending }}
    >
      <Typography.Paragraph type="secondary">
        Grant access on {target.securableType}:{' '}
        <Typography.Text code>{target.fullName}</Typography.Text>. The server
        authorizes every change (securable owner / metastore admin).
      </Typography.Paragraph>
      <Form<GrantAccessFormValues>
        form={form}
        layout="vertical"
        initialValues={{ level: levels[0] }}
        onFinish={(values) => {
          mutation.mutate(
            {
              principal: values.principal,
              target,
              level: values.level,
              mode: 'grant',
            },
            {
              onError: (error: Error) => {
                setNotification(error.message, 'error');
              },
              onSuccess: () => {
                setNotification(
                  `Granted ${values.level} on ${target.fullName} to ${values.principal}`,
                  'success',
                );
                closeModal();
              },
            },
          );
        }}
        name="Grant access form"
      >
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
              (option?.label ?? '').toLowerCase().includes(input.toLowerCase())
            }
          />
        </Form.Item>
        <Form.Item
          required
          label={<Typography.Text strong>Access level</Typography.Text>}
          name="level"
          rules={[{ required: true, message: 'Access level is required' }]}
        >
          <Radio.Group
            options={levels.map((level) => ({ value: level, label: level }))}
          />
        </Form.Item>
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          Privileges do not inherit in Unity Catalog, so this grant writes:
        </Typography.Paragraph>
        <ul style={{ marginTop: 0, paddingLeft: 20 }}>
          {plannedGrants.map((grant) => (
            <li
              key={`${grant.securable_type}:${grant.full_name}:${grant.privilege}`}
            >
              <Typography.Text code>{grant.privilege}</Typography.Text>{' '}
              <Typography.Text type="secondary">
                on {grant.securable_type} {grant.full_name}
              </Typography.Text>
            </li>
          ))}
        </ul>
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          Revoking later removes only the last item; the USE grants stay because
          sibling objects in the same catalog/schema may rely on them.
        </Typography.Paragraph>
        <Form.Item hidden>
          <Button type="primary" htmlType="submit" ref={submitRef}>
            Grant
          </Button>
        </Form.Item>
      </Form>
    </Modal>
  );
}
