import { Checkbox, Modal, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { useNotification } from '../../utils/NotificationContext';
import {
  CredentialInterface,
  useDeleteCredential,
} from '../../hooks/credentials';
import { ExternalLocationInterface } from '../../hooks/externalLocations';

interface DeleteCredentialModalProps {
  open: boolean;
  closeModal: () => void;
  credential: CredentialInterface;
  /** For the dependency warning: locations still bound to this credential. */
  externalLocations: ExternalLocationInterface[];
}

/**
 * Deletes a credential. Locations still bound to it are listed as a warning;
 * `force` maps to the API's force flag. The server authorizes the call
 * (metastore admin or credential owner).
 */
export function DeleteCredentialModal({
  open,
  closeModal,
  credential,
  externalLocations,
}: DeleteCredentialModalProps) {
  const mutation = useDeleteCredential();
  const { setNotification } = useNotification();
  const [force, setForce] = useState(false);

  // destroyOnClose does not reset local state; never carry a checked force
  // flag over to the next deletion.
  useEffect(() => {
    if (open) setForce(false);
  }, [open]);

  const usedBy = externalLocations.filter(
    (location) => location.credential_name === credential.name,
  );

  return (
    <Modal
      title={
        <Typography.Title type={'danger'} level={4}>
          Delete credential
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
          { name: credential.name ?? '', force },
          {
            onError: (error: Error) => {
              setNotification(error.message, 'error');
            },
            onSuccess: () => {
              setNotification(
                `Credential ${credential.name} deleted`,
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
        Are you sure you want to delete the credential
        <Typography.Text strong>{` ${credential.name}`}</Typography.Text>? This
        operation cannot be undone.
      </Typography.Paragraph>
      {usedBy.length > 0 && (
        <Typography.Paragraph type="warning">
          Still used by {usedBy.length} external location(s):{' '}
          {usedBy.map((location) => location.name).join(', ')} — credential
          vending for them will break.
        </Typography.Paragraph>
      )}
      <Checkbox checked={force} onChange={(e) => setForce(e.target.checked)}>
        Force delete (even if referenced by external locations)
      </Checkbox>
    </Modal>
  );
}
