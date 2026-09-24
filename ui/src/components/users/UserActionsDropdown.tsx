import {
  CrownOutlined,
  DeleteOutlined,
  MoreOutlined,
  SafetyOutlined,
  StopOutlined,
  TableOutlined,
  UndoOutlined,
} from '@ant-design/icons';
import { Button, Dropdown, MenuProps, Tooltip } from 'antd';
import { useMemo, useState } from 'react';
import GrantToUserModal from './GrantToUserModal';
import DeleteUserModal from './DeleteUserModal';
import { useSetMetastoreAdmin, useSetScimUserActive } from '../../hooks/users';
import { useCurrentPrincipal, useIsMetastoreAdmin } from '../../hooks/authz';
import { useNotification } from '../../utils/NotificationContext';

/** The account behind the password sign-in; see deploy/README "Administrator sign-in". */
const BOOTSTRAP_ADMIN = 'admin';

interface UserActionsDropdownProps {
  /** The user's primary email; Unity Catalog principals are emails. */
  principal: string;
  /** SCIM id, needed by everything that modifies the user. */
  userId?: string;
  /** Whether the user is currently active. */
  active?: boolean;
  /** Whether the user holds OWNER on the metastore. */
  isAdmin?: boolean;
  displayName?: string;
  /** Opens the (expensive) full permission scan for this user. */
  onShowAccessDetails: () => void;
}

/**
 * Row actions for a user.
 *
 * Granting is deliberately separate from "Access details": that view has to
 * traverse every catalog, schema and table because Unity Catalog has no
 * per-user permissions lookup, and it only starts on an explicit click. Putting
 * the grant entry here means an admin can hand out access without ever paying
 * for that scan.
 *
 * Deactivation and deletion are two different things and are presented as such.
 * Deactivating blocks the user immediately and completely — the server checks
 * their state on every request — and is undone with one click. Deleting only
 * frees the principal for reuse, and cannot be undone; it is offered solely for
 * an already-deactivated user, so the irreversible step sits behind a state the
 * administrator has seen.
 *
 * The gates here are guidance, not enforcement: the server re-checks all of
 * them and a 403 remains possible (see hooks/authz.ts).
 */
export default function UserActionsDropdown({
  principal,
  userId,
  active,
  isAdmin,
  displayName,
  onShowAccessDetails,
}: UserActionsDropdownProps) {
  const [dropdownVisible, setDropdownVisible] = useState<boolean>(false);
  const [grantOpen, setGrantOpen] = useState<boolean>(false);
  const [deleteOpen, setDeleteOpen] = useState<boolean>(false);
  const { data: isMetastoreAdmin } = useIsMetastoreAdmin();
  const { data: currentPrincipal } = useCurrentPrincipal();
  const setActive = useSetScimUserActive();
  const setAdmin = useSetMetastoreAdmin();
  const { setNotification } = useNotification();

  const isSelf = !!currentPrincipal && currentPrincipal === principal;
  const isBootstrapAdmin = principal === BOOTSTRAP_ADMIN;

  const menuItems = useMemo((): MenuProps['items'] => {
    const items: MenuProps['items'] = [
      {
        key: 'grantAccess',
        label: 'Grant access',
        onClick: () => setGrantOpen(true),
        icon: <SafetyOutlined />,
      },
      {
        key: 'accessDetails',
        label: 'Access details',
        onClick: onShowAccessDetails,
        icon: <TableOutlined />,
      },
    ];

    // Only a metastore admin may change another account's state, and without a
    // SCIM id there is nothing to address.
    if (!isMetastoreAdmin || !userId) {
      return items;
    }

    items.push({ type: 'divider' });

    if (active) {
      // Administrator status is only offered for an active user: the server
      // will not grant it to a deactivated account, and withdrawing it from one
      // is a distraction from reactivating or deleting them.
      items.push({
        key: 'admin',
        icon: <CrownOutlined />,
        // The bootstrap administrator keeps the privilege: the password sign-in
        // would still work without it but administer nothing, and no restart
        // puts it back.
        disabled: isAdmin && isBootstrapAdmin,
        label: (
          <Tooltip
            title={
              isAdmin && isBootstrapAdmin
                ? 'The bootstrap administrator cannot lose administrator status; no restart restores it'
                : undefined
            }
          >
            {isAdmin ? 'Withdraw administrator' : 'Make administrator'}
          </Tooltip>
        ),
        onClick: () =>
          setAdmin.mutate(
            { email: principal, admin: !isAdmin },
            {
              onError: (error: Error) =>
                setNotification(error.message, 'error'),
              onSuccess: () =>
                setNotification(
                  isAdmin
                    ? `${principal} is no longer an administrator`
                    : `${principal} is now an administrator`,
                  'success',
                ),
            },
          ),
      });
      items.push({
        key: 'deactivate',
        icon: <StopOutlined />,
        // Deactivating yourself locks you out of the UI, and only another
        // administrator (or the password sign-in) can undo it. Deactivating the
        // bootstrap administrator closes the password sign-in itself, which is
        // the way back in when the identity provider is unavailable.
        disabled: isSelf || isBootstrapAdmin,
        label: (
          <Tooltip
            title={
              isSelf
                ? 'You cannot deactivate your own account'
                : isBootstrapAdmin
                  ? 'The bootstrap administrator cannot be deactivated; it is the way back in when the identity provider is unavailable'
                  : undefined
            }
          >
            Deactivate
          </Tooltip>
        ),
        onClick: () =>
          setActive.mutate(
            { id: userId, active: false },
            {
              onError: (error: Error) =>
                setNotification(error.message, 'error'),
              onSuccess: () =>
                setNotification(`User ${principal} deactivated`, 'success'),
            },
          ),
      });
    } else {
      items.push({
        key: 'reactivate',
        icon: <UndoOutlined />,
        label: 'Reactivate',
        onClick: () =>
          setActive.mutate(
            { id: userId, active: true },
            {
              onError: (error: Error) =>
                setNotification(error.message, 'error'),
              onSuccess: () =>
                setNotification(`User ${principal} reactivated`, 'success'),
            },
          ),
      });
      items.push({
        key: 'delete',
        icon: <DeleteOutlined />,
        danger: true,
        disabled: isSelf || isBootstrapAdmin,
        label: (
          <Tooltip
            title={
              isSelf
                ? 'You cannot delete your own account'
                : isBootstrapAdmin
                  ? 'The bootstrap administrator cannot be deleted; it is the way back in when the identity provider is unavailable'
                  : undefined
            }
          >
            Delete permanently
          </Tooltip>
        ),
        onClick: () => setDeleteOpen(true),
      });
    }

    return items;
  }, [
    onShowAccessDetails,
    isMetastoreAdmin,
    userId,
    active,
    isAdmin,
    isSelf,
    isBootstrapAdmin,
    principal,
    setActive,
    setAdmin,
    setNotification,
  ]);

  return (
    // The row opens the user drawer when clicked, and everything below is rendered from inside
    // that row's cell. Ant Design puts menus and modals in portals, so they look detached from
    // it, but React events travel the React tree rather than the DOM — on that tree all of this
    // is still the cell's child. One stop here covers the lot: opening the menu, choosing an
    // action, and clicking or typing inside a dialog the action opened. Without it the drawer
    // kept appearing behind whatever had just been opened.
    // eslint-disable-next-line jsx-a11y/no-static-element-interactions, jsx-a11y/click-events-have-key-events -- not a control: it only keeps clicks from escaping to the row
    <span onClick={(e) => e.stopPropagation()}>
      <Dropdown
        menu={{ items: menuItems }}
        trigger={['click']}
        onOpenChange={() => setDropdownVisible(!dropdownVisible)}
      >
        <Button
          type="text"
          aria-label={`Actions for ${principal}`}
          icon={
            <MoreOutlined
              rotate={dropdownVisible ? 90 : 0}
              style={{ transition: 'transform 0.5s' }}
            />
          }
        />
      </Dropdown>
      <GrantToUserModal
        open={grantOpen}
        closeModal={() => setGrantOpen(false)}
        principal={principal}
      />
      {userId && (
        <DeleteUserModal
          open={deleteOpen}
          closeModal={() => setDeleteOpen(false)}
          userId={userId}
          principal={principal}
          displayName={displayName}
        />
      )}
    </span>
  );
}
