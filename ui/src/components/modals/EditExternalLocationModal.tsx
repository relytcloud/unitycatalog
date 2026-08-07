import { Button, Form, Input, Modal, Select, Typography } from 'antd';
import { useCallback, useEffect, useRef } from 'react';
import TextArea from 'antd/es/input/TextArea';
import { useNotification } from '../../utils/NotificationContext';
import {
  ExternalLocationInterface,
  useUpdateExternalLocation,
} from '../../hooks/externalLocations';
import { useListCredentials } from '../../hooks/credentials';

interface EditExternalLocationFormValues {
  url: string;
  credential_name: string;
  comment?: string;
}

interface EditExternalLocationModalProps {
  open: boolean;
  closeModal: () => void;
  externalLocation: ExternalLocationInterface;
}

/**
 * Edits an external location's URL, credential binding and comment (renaming
 * is intentionally not offered — other objects reference the location by
 * name). The server authorizes the update (metastore admin, or the location
 * owner who can also use the chosen credential).
 */
export function EditExternalLocationModal({
  open,
  closeModal,
  externalLocation,
}: EditExternalLocationModalProps) {
  const mutation = useUpdateExternalLocation();
  const { data: credentialsData } = useListCredentials();
  const { setNotification } = useNotification();
  const submitRef = useRef<HTMLButtonElement>(null);
  const [form] = Form.useForm<EditExternalLocationFormValues>();

  // Re-seed the fields from the current record each time the modal opens
  // (the useForm store outlives destroyOnClose).
  useEffect(() => {
    if (open) {
      form.setFieldsValue({
        url: externalLocation.url,
        credential_name: externalLocation.credential_name,
        comment: externalLocation.comment,
      });
    }
  }, [open, form, externalLocation]);

  const handleSubmit = useCallback(() => {
    submitRef.current?.click();
  }, []);

  return (
    <Modal
      title={
        <Typography.Title level={4}>Edit external location</Typography.Title>
      }
      okText="Save"
      cancelText="Cancel"
      open={open}
      destroyOnClose
      onCancel={closeModal}
      onOk={handleSubmit}
      okButtonProps={{ loading: mutation.isPending }}
    >
      <Typography.Paragraph type="secondary">
        Update <Typography.Text code>{externalLocation.name}</Typography.Text>.
        The server authorizes the change (metastore admin or location owner).
      </Typography.Paragraph>
      <Form<EditExternalLocationFormValues>
        form={form}
        layout="vertical"
        onFinish={(values) => {
          mutation.mutate(
            { name: externalLocation.name ?? '', ...values },
            {
              onError: (error: Error) => {
                setNotification(error.message, 'error');
              },
              onSuccess: (updated) => {
                setNotification(
                  `External location ${updated.name} updated`,
                  'success',
                );
                closeModal();
              },
            },
          );
        }}
        name="Edit external location form"
      >
        <Form.Item
          required
          label={<Typography.Text strong>URL</Typography.Text>}
          name="url"
          rules={[{ required: true, message: 'URL is required' }]}
        >
          <Input placeholder="oss://bucket/path or s3://bucket/path" />
        </Form.Item>
        <Form.Item
          required
          label={<Typography.Text strong>Credential</Typography.Text>}
          name="credential_name"
          rules={[{ required: true, message: 'Credential is required' }]}
        >
          <Select
            showSearch
            placeholder="Select a credential"
            options={(credentialsData?.credentials ?? []).map((credential) => ({
              value: credential.name,
              label: credential.name,
            }))}
          />
        </Form.Item>
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
