import { Checkbox, Modal, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { useNotification } from '../../utils/NotificationContext';
import {
  ExternalLocationInterface,
  useDeleteExternalLocation,
} from '../../hooks/externalLocations';

interface DeleteExternalLocationModalProps {
  open: boolean;
  closeModal: () => void;
  externalLocation: ExternalLocationInterface;
}

/**
 * Deletes an external location. `force` maps to the API's force flag, which
 * deletes even when dependent objects reference the location. The server
 * authorizes the call (metastore admin or location owner).
 */
export function DeleteExternalLocationModal({
  open,
  closeModal,
  externalLocation,
}: DeleteExternalLocationModalProps) {
  const mutation = useDeleteExternalLocation();
  const { setNotification } = useNotification();
  const [force, setForce] = useState(false);

  // destroyOnClose does not reset local state; never carry a checked force
  // flag over to the next deletion.
  useEffect(() => {
    if (open) setForce(false);
  }, [open]);

  return (
    <Modal
      title={
        <Typography.Title type={'danger'} level={4}>
          Delete external location
        </Typography.Title>
      }
      okText="Delete"
      okType="danger"
      cancelText="Cancel"
      open={open}
      destroyOnClose
      onCancel={closeModal}
      onOk={() =>
        mutation.mutate(
          { name: externalLocation.name ?? '', force },
          {
            onError: (error: Error) => {
              setNotification(error.message, 'error');
            },
            onSuccess: () => {
              setNotification(
                `External location ${externalLocation.name} deleted`,
                'success',
              );
              closeModal();
            },
          },
        )
      }
      okButtonProps={{ loading: mutation.isPending }}
    >
      <Typography.Paragraph>
        Are you sure you want to delete the external location
        <Typography.Text strong>{` ${externalLocation.name}`}</Typography.Text>?
        This operation cannot be undone.
      </Typography.Paragraph>
      <Checkbox checked={force} onChange={(e) => setForce(e.target.checked)}>
        Force delete (even if referenced by other objects)
      </Checkbox>
    </Modal>
  );
}
