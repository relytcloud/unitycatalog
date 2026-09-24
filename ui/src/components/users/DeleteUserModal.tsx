import { useMemo, useState } from 'react';
import {
  Alert,
  Modal,
  Select,
  Spin,
  Table,
  Typography,
  Input,
  Form,
} from 'antd';
import { useNotification } from '../../utils/NotificationContext';
import {
  OwnedObjectInterface,
  usePurgeScimUser,
  useScimUserOptions,
  useUserOwnedObjects,
} from '../../hooks/users';
import { useCurrentPrincipal } from '../../hooks/authz';

interface DeleteUserModalProps {
  open: boolean;
  closeModal: () => void;
  /** SCIM id of the user being deleted. */
  userId: string;
  /** Their principal (primary email) — what the caller has to type back. */
  principal: string;
  displayName?: string;
}

/**
 * Permanent deletion of a user, with its consequences shown first.
 *
 * Two inputs that are easy to confuse and must not be: the **reassignment
 * target** decides who inherits the user's objects, while the **typed
 * principal** only confirms which user is being deleted. They can never be the
 * same value — the server rejects handing ownership to the account being
 * removed.
 *
 * Ownership is transferred rather than offering to delete the objects: a
 * cascade behind a confirmation dialog is how an entire catalog disappears by
 * accident, and the objects can still be deleted individually afterwards.
 */
export default function DeleteUserModal({
  open,
  closeModal,
  userId,
  principal,
  displayName,
}: DeleteUserModalProps) {
  const { data, isPending, error } = useUserOwnedObjects(userId, open);
  const { data: currentPrincipal } = useCurrentPrincipal();
  const userOptions = useScimUserOptions();
  const mutation = usePurgeScimUser();
  const { setNotification } = useNotification();

  const [reassignTo, setReassignTo] = useState<string | undefined>(undefined);
  const [typedPrincipal, setTypedPrincipal] = useState('');

  const owned: OwnedObjectInterface[] = useMemo(
    () => data?.owned ?? [],
    [data],
  );

  // Anyone but the user being deleted. The current administrator is offered
  // first: they are demonstrably able to act on what they inherit, whereas the
  // built-in `admin` account may itself be deactivated.
  const reassignOptions = useMemo(
    () => userOptions.filter((option) => option.value !== principal),
    [userOptions, principal],
  );

  // Only meaningful when there is something to hand over: sending a
  // reassignment target for a user who owns nothing would claim a transfer
  // that never happens.
  const effectiveReassignTo =
    owned.length === 0
      ? undefined
      : (reassignTo ??
        (currentPrincipal && currentPrincipal !== principal
          ? currentPrincipal
          : undefined));

  const confirmed = typedPrincipal === principal;
  const reassignmentSatisfied = owned.length === 0 || !!effectiveReassignTo;

  const close = () => {
    setReassignTo(undefined);
    setTypedPrincipal('');
    closeModal();
  };

  return (
    <Modal
      title={
        <Typography.Title level={4}>
          Delete {displayName ?? principal} permanently
        </Typography.Title>
      }
      okText="Delete permanently"
      cancelText="Cancel"
      open={open}
      destroyOnClose
      onCancel={close}
      okButtonProps={{
        danger: true,
        loading: mutation.isPending,
        disabled: !confirmed || !reassignmentSatisfied || isPending,
      }}
      onOk={() =>
        mutation.mutate(
          { id: userId, principal, reassignTo: effectiveReassignTo },
          {
            onError: (err: Error) => setNotification(err.message, 'error'),
            onSuccess: () => {
              setNotification(`User ${principal} deleted`, 'success');
              close();
            },
          },
        )
      }
    >
      <Typography.Paragraph type="secondary">
        This removes the user record and frees <code>{principal}</code> to be
        registered again. It cannot be undone. Access is already blocked by the
        deactivation — delete only to clean up the directory.
      </Typography.Paragraph>

      {error && (
        <Alert
          type="error"
          showIcon
          message="Could not determine what this user owns"
          description={error.message}
          style={{ marginBottom: 16 }}
        />
      )}

      {isPending && !error && <Spin />}

      {!isPending && !error && owned.length === 0 && (
        <Alert
          type="info"
          showIcon
          message="This user owns nothing; no ownership has to change hands."
          style={{ marginBottom: 16 }}
        />
      )}

      {owned.length > 0 && (
        <>
          <Alert
            type="warning"
            showIcon
            message={`This user owns ${owned.length} object(s). Ownership transfers to the principal you choose.`}
            style={{ marginBottom: 16 }}
          />
          <Table<OwnedObjectInterface>
            size="small"
            pagination={owned.length > 10 ? { pageSize: 10 } : false}
            dataSource={owned}
            rowKey={(record) =>
              `${record.securable_type}-${record.securable_id}`
            }
            style={{ marginBottom: 16 }}
            columns={[
              {
                title: 'Type',
                dataIndex: 'securable_type',
                key: 'securable_type',
                width: '30%',
              },
              { title: 'Name', dataIndex: 'full_name', key: 'full_name' },
            ]}
          />
          <Form layout="vertical">
            <Form.Item
              label={
                <Typography.Text strong>Reassign ownership to</Typography.Text>
              }
              // Credentials and external locations carry access to cloud
              // storage; whoever inherits them inherits that responsibility.
              extra={
                owned.some((object) =>
                  ['credential', 'external_location'].includes(
                    object.securable_type ?? '',
                  ),
                )
                  ? 'Includes credentials or external locations — the new owner becomes responsible for the cloud access they grant.'
                  : undefined
              }
            >
              <Select
                showSearch
                optionFilterProp="label"
                placeholder="Select a user"
                value={effectiveReassignTo}
                onChange={setReassignTo}
                options={reassignOptions}
              />
            </Form.Item>
          </Form>
        </>
      )}

      <Form layout="vertical">
        <Form.Item
          label={
            <Typography.Text strong>
              Type <code>{principal}</code> to confirm
            </Typography.Text>
          }
        >
          <Input
            value={typedPrincipal}
            onChange={(event) => setTypedPrincipal(event.target.value)}
            placeholder={principal}
            aria-label="Confirm principal"
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}
