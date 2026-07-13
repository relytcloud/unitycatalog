import { Button, Form, Input, Modal, Typography } from 'antd';
import { useCallback, useRef } from 'react';
import { useNotification } from '../../utils/NotificationContext';
import { useCreateScimUser } from '../../hooks/users';

interface CreateUserFormValues {
  displayName: string;
  email: string;
}

interface CreateUserModalProps {
  open: boolean;
  closeModal: () => void;
}

export function CreateUserModal({ open, closeModal }: CreateUserModalProps) {
  const mutation = useCreateScimUser();
  const { setNotification } = useNotification();
  const submitRef = useRef<HTMLButtonElement>(null);

  const handleSubmit = useCallback(() => {
    submitRef.current?.click();
  }, []);

  return (
    <Modal
      title={<Typography.Title level={4}>Create user</Typography.Title>}
      okText="Create"
      cancelText="Cancel"
      open={open}
      destroyOnClose
      onCancel={closeModal}
      onOk={handleSubmit}
      okButtonProps={{ loading: mutation.isPending }}
    >
      <Typography.Paragraph type="secondary">
        Register a user in Unity Catalog. The email is the principal used for
        grants and token exchange. Creation requires metastore OWNER; the server
        enforces this on submit.
      </Typography.Paragraph>
      <Form<CreateUserFormValues>
        layout="vertical"
        onFinish={(values) => {
          mutation.mutate(
            {
              displayName: values.displayName,
              emails: [{ primary: true, value: values.email }],
            },
            {
              onError: (error: Error) => {
                setNotification(error.message, 'error');
              },
              onSuccess: (user) => {
                setNotification(`User ${user.displayName} created`, 'success');
                closeModal();
              },
            },
          );
        }}
        name="Create user form"
      >
        <Form.Item
          required
          label={<Typography.Text strong>Display name</Typography.Text>}
          name="displayName"
          rules={[{ required: true, message: 'Display name is required' }]}
        >
          <Input />
        </Form.Item>
        <Form.Item
          required
          label={<Typography.Text strong>Email</Typography.Text>}
          name="email"
          rules={[
            { required: true, message: 'Email is required' },
            { type: 'email', message: 'Must be a valid email' },
          ]}
        >
          <Input placeholder="user@example.com" />
        </Form.Item>
        <Form.Item hidden>
          <Button type="primary" htmlType="submit" ref={submitRef}>
            Create
          </Button>
        </Form.Item>
      </Form>
    </Modal>
  );
}
