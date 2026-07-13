import { Button, Form, Input, Modal, Radio, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import TextArea from 'antd/es/input/TextArea';
import { useNavigate } from 'react-router-dom';
import { useNotification } from '../../utils/NotificationContext';
import { useCreateCredential } from '../../hooks/credentials';
import { CredentialPurpose } from '../../types/api/catalog.gen';

type CredentialType = 'aliyun_sts' | 'aliyun_static' | 'aws_iam';

interface CreateCredentialFormValues {
  name: string;
  comment?: string;
  role_arn?: string;
  access_key_id?: string;
  access_key_secret?: string;
}

interface CreateCredentialModalProps {
  open: boolean;
  closeModal: () => void;
}

export function CreateCredentialModal({
  open,
  closeModal,
}: CreateCredentialModalProps) {
  const navigate = useNavigate();
  const mutation = useCreateCredential();
  const { setNotification } = useNotification();
  const submitRef = useRef<HTMLButtonElement>(null);
  const [credentialType, setCredentialType] =
    useState<CredentialType>('aliyun_sts');

  // destroyOnClose resets the form fields but not this local state; re-default
  // the type each time the modal opens so it doesn't reveal the prior choice.
  useEffect(() => {
    if (open) setCredentialType('aliyun_sts');
  }, [open]);

  const handleSubmit = useCallback(() => {
    submitRef.current?.click();
  }, []);

  return (
    <Modal
      title={<Typography.Title level={4}>Create credential</Typography.Title>}
      okText="Create"
      cancelText="Cancel"
      open={open}
      destroyOnClose
      onCancel={closeModal}
      onOk={handleSubmit}
      okButtonProps={{ loading: mutation.isPending }}
    >
      <Typography.Paragraph type="secondary">
        Register a storage credential used to vend temporary credentials for
        external locations. Creation requires metastore OWNER or the
        CREATE_STORAGE_CREDENTIAL privilege; the server enforces this on submit.
      </Typography.Paragraph>
      <Form<CreateCredentialFormValues>
        layout="vertical"
        onFinish={(values) => {
          mutation.mutate(
            {
              name: values.name,
              comment: values.comment,
              purpose: CredentialPurpose.STORAGE,
              ...(credentialType === 'aws_iam'
                ? { aws_iam_role: { role_arn: values.role_arn ?? '' } }
                : credentialType === 'aliyun_sts'
                  ? { aliyun_ram_role: { role_arn: values.role_arn } }
                  : {
                      aliyun_ram_role: {
                        access_key_id: values.access_key_id,
                        access_key_secret: values.access_key_secret,
                      },
                    }),
            },
            {
              onError: (error: Error) => {
                setNotification(error.message, 'error');
              },
              onSuccess: (credential) => {
                setNotification(
                  `Credential ${credential.name} created`,
                  'success',
                );
                closeModal();
                navigate(`/external-data/credentials/${credential.name}`);
              },
            },
          );
        }}
        name="Create credential form"
      >
        <Form.Item
          required
          label={<Typography.Text strong>Name</Typography.Text>}
          name="name"
          rules={[{ required: true, message: 'Name is required' }]}
        >
          <Input />
        </Form.Item>
        <Form.Item label={<Typography.Text strong>Type</Typography.Text>}>
          <Radio.Group
            value={credentialType}
            onChange={(e) => setCredentialType(e.target.value)}
            options={[
              { value: 'aliyun_sts', label: 'Aliyun RAM role (STS)' },
              { value: 'aliyun_static', label: 'Aliyun static AK/SK' },
              { value: 'aws_iam', label: 'AWS IAM role' },
            ]}
          />
        </Form.Item>
        {credentialType !== 'aliyun_static' && (
          <Form.Item
            required
            label={<Typography.Text strong>Role ARN</Typography.Text>}
            name="role_arn"
            rules={[{ required: true, message: 'Role ARN is required' }]}
          >
            <Input
              placeholder={
                credentialType === 'aliyun_sts'
                  ? 'acs:ram::<account-id>:role/<role-name>'
                  : 'arn:aws:iam::<account-id>:role/<role-name>'
              }
            />
          </Form.Item>
        )}
        {credentialType === 'aliyun_static' && (
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
            Create
          </Button>
        </Form.Item>
      </Form>
    </Modal>
  );
}
