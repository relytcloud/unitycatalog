import { Button, Form, Input, Modal, Radio, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import TextArea from 'antd/es/input/TextArea';
import { useNotification } from '../../utils/NotificationContext';
import {
  CredentialInterface,
  useUpdateCredential,
} from '../../hooks/credentials';
import { credentialTypeOf } from '../../utils/credential';

type CredentialMode = 'keep' | 'aliyun_sts' | 'aliyun_static' | 'aws_iam';

interface EditCredentialFormValues {
  comment?: string;
  role_arn?: string;
  access_key_id?: string;
  access_key_secret?: string;
}

interface EditCredentialModalProps {
  open: boolean;
  closeModal: () => void;
  credential: CredentialInterface;
}

/**
 * Edits a credential's comment and, optionally, replaces its identity (role
 * ARN or static AK/SK — the two Aliyun modes can be switched freely, matching
 * the API contract). "Keep current identity" sends a comment-only PATCH.
 * Renaming is intentionally not offered — external locations reference the
 * credential by name. The server authorizes the update (metastore admin or
 * credential owner).
 */
export function EditCredentialModal({
  open,
  closeModal,
  credential,
}: EditCredentialModalProps) {
  const mutation = useUpdateCredential();
  const { setNotification } = useNotification();
  const submitRef = useRef<HTMLButtonElement>(null);
  const [form] = Form.useForm<EditCredentialFormValues>();
  const [mode, setMode] = useState<CredentialMode>('keep');

  // Re-seed on every open: comment from the record, identity mode back to
  // "keep" (destroyOnClose resets fields but not this local state).
  useEffect(() => {
    if (open) {
      setMode('keep');
      form.setFieldsValue({
        comment: credential.comment,
        role_arn: undefined,
        access_key_id: undefined,
        access_key_secret: undefined,
      });
    }
  }, [open, form, credential]);

  const handleSubmit = useCallback(() => {
    submitRef.current?.click();
  }, []);

  return (
    <Modal
      title={<Typography.Title level={4}>Edit credential</Typography.Title>}
      okText="Save"
      cancelText="Cancel"
      open={open}
      destroyOnClose
      onCancel={closeModal}
      onOk={handleSubmit}
      okButtonProps={{ loading: mutation.isPending }}
    >
      <Typography.Paragraph type="secondary">
        Update <Typography.Text code>{credential.name}</Typography.Text>{' '}
        (currently {credentialTypeOf(credential)}). The server authorizes the
        change (metastore admin or credential owner).
      </Typography.Paragraph>
      <Form<EditCredentialFormValues>
        form={form}
        layout="vertical"
        onFinish={(values) => {
          mutation.mutate(
            {
              name: credential.name ?? '',
              comment: values.comment,
              ...(mode === 'aws_iam'
                ? { aws_iam_role: { role_arn: values.role_arn ?? '' } }
                : mode === 'aliyun_sts'
                  ? { aliyun_ram_role: { role_arn: values.role_arn } }
                  : mode === 'aliyun_static'
                    ? {
                        aliyun_ram_role: {
                          access_key_id: values.access_key_id,
                          access_key_secret: values.access_key_secret,
                        },
                      }
                    : {}),
            },
            {
              onError: (error: Error) => {
                setNotification(error.message, 'error');
              },
              onSuccess: (updated) => {
                setNotification(
                  `Credential ${updated.name} updated`,
                  'success',
                );
                closeModal();
              },
            },
          );
        }}
        name="Edit credential form"
      >
        <Form.Item label={<Typography.Text strong>Identity</Typography.Text>}>
          <Radio.Group
            value={mode}
            onChange={(e) => setMode(e.target.value)}
            options={[
              { value: 'keep', label: 'Keep current identity' },
              { value: 'aliyun_sts', label: 'Aliyun RAM role (STS)' },
              { value: 'aliyun_static', label: 'Aliyun static AK/SK' },
              { value: 'aws_iam', label: 'AWS IAM role' },
            ]}
          />
        </Form.Item>
        {(mode === 'aliyun_sts' || mode === 'aws_iam') && (
          <Form.Item
            required
            label={<Typography.Text strong>Role ARN</Typography.Text>}
            name="role_arn"
            rules={[{ required: true, message: 'Role ARN is required' }]}
          >
            <Input
              placeholder={
                mode === 'aliyun_sts'
                  ? 'acs:ram::<account-id>:role/<role-name>'
                  : 'arn:aws:iam::<account-id>:role/<role-name>'
              }
            />
          </Form.Item>
        )}
        {mode === 'aliyun_static' && (
          <>
            <Form.Item
              required
              label={<Typography.Text strong>Access key ID</Typography.Text>}
              name="access_key_id"
              rules={[{ required: true, message: 'Access key ID is required' }]}
            >
              <Input />
            </Form.Item>
            <Form.Item
              required
              label={
                <Typography.Text strong>Access key secret</Typography.Text>
              }
              name="access_key_secret"
              rules={[
                { required: true, message: 'Access key secret is required' },
              ]}
              extra="Stored server-side and never returned by the API; responses always redact it."
            >
              <Input.Password autoComplete="new-password" />
            </Form.Item>
          </>
        )}
        <Form.Item
          label={<Typography.Text strong>Comment</Typography.Text>}
          name="comment"
        >
          <TextArea />
        </Form.Item>
        <Form.Item hidden>
          <Button type="primary" htmlType="submit" ref={submitRef}>
            Save
          </Button>
        </Form.Item>
      </Form>
    </Modal>
  );
}
