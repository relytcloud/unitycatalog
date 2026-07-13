import { Button, Form, Input, Modal, Select, Typography } from 'antd';
import { useCallback, useRef } from 'react';
import TextArea from 'antd/es/input/TextArea';
import { useNavigate } from 'react-router-dom';
import { useNotification } from '../../utils/NotificationContext';
import {
  CreateExternalLocationMutationParams,
  useCreateExternalLocation,
} from '../../hooks/externalLocations';
import { useListCredentials } from '../../hooks/credentials';

interface CreateExternalLocationModalProps {
  open: boolean;
  closeModal: () => void;
}

export function CreateExternalLocationModal({
  open,
  closeModal,
}: CreateExternalLocationModalProps) {
  const navigate = useNavigate();
  const mutation = useCreateExternalLocation();
  const { data: credentialsData } = useListCredentials();
  const { setNotification } = useNotification();
  const submitRef = useRef<HTMLButtonElement>(null);

  const handleSubmit = useCallback(() => {
    submitRef.current?.click();
  }, []);

  return (
    <Modal
      title={
        <Typography.Title level={4}>Create external location</Typography.Title>
      }
      okText="Create"
      cancelText="Cancel"
      open={open}
      destroyOnClose
      onCancel={closeModal}
      onOk={handleSubmit}
      okButtonProps={{ loading: mutation.isPending }}
    >
      <Typography.Paragraph type="secondary">
        Register a storage path and bind it to a credential. Creation requires
        metastore OWNER or the CREATE_EXTERNAL_LOCATION privilege; the server
        enforces this on submit.
      </Typography.Paragraph>
      <Form<CreateExternalLocationMutationParams>
        layout="vertical"
        onFinish={(values) => {
          mutation.mutate(values, {
            onError: (error: Error) => {
              setNotification(error.message, 'error');
            },
            onSuccess: (externalLocation) => {
              setNotification(
                `External location ${externalLocation.name} created`,
                'success',
              );
              closeModal();
              navigate(
                `/external-data/external-locations/${externalLocation.name}`,
              );
            },
          });
        }}
        name="Create external location form"
      >
        <Form.Item
          required
          label={<Typography.Text strong>Name</Typography.Text>}
          name="name"
          rules={[{ required: true, message: 'Name is required' }]}
        >
          <Input />
        </Form.Item>
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
            Create
          </Button>
        </Form.Item>
      </Form>
    </Modal>
  );
}
