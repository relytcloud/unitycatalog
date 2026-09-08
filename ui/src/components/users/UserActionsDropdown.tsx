import { MoreOutlined, SafetyOutlined, TableOutlined } from '@ant-design/icons';
import { Button, Dropdown, MenuProps } from 'antd';
import { useMemo, useState } from 'react';
import GrantToUserModal from './GrantToUserModal';

interface UserActionsDropdownProps {
  /** The user's primary email; Unity Catalog principals are emails. */
  principal: string;
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
 */
export default function UserActionsDropdown({
  principal,
  onShowAccessDetails,
}: UserActionsDropdownProps) {
  const [dropdownVisible, setDropdownVisible] = useState<boolean>(false);
  const [grantOpen, setGrantOpen] = useState<boolean>(false);

  const menuItems = useMemo(
    (): MenuProps['items'] => [
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
    ],
    [onShowAccessDetails],
  );

  return (
    <>
      <Dropdown
        menu={{ items: menuItems }}
        trigger={['click']}
        onOpenChange={() => setDropdownVisible(!dropdownVisible)}
      >
        <Button
          type="text"
          aria-label={`Actions for ${principal}`}
          // The row itself opens the user drawer; keep that from firing.
          onClick={(e) => e.stopPropagation()}
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
    </>
  );
}
