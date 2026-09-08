import { MoreOutlined, SafetyOutlined } from '@ant-design/icons';
import { Button, Dropdown, MenuProps } from 'antd';
import { useMemo, useState } from 'react';
import { GrantAccessModal } from '../access/GrantAccessModal';
import { SecurableType } from '../../types/api/catalog.gen';

interface TableAccessDropdownProps {
  // The generated API types mark these optional; a row missing any of them has
  // no addressable full name, so the entry is simply not offered.
  catalog?: string;
  schema?: string;
  table?: string;
}

/**
 * Row-level access entry for the table list. The table details page already
 * carries a full AccessPanel; this only shortcuts the common "grant someone
 * read on this table" so it does not require opening each table first.
 *
 * The Grant button inside the modal is not gated here: granting is authorized
 * by the server per PATCH, and the list has no owner information to gate on
 * without an extra request per row.
 */
export default function TableAccessDropdown({
  catalog,
  schema,
  table,
}: TableAccessDropdownProps) {
  const [dropdownVisible, setDropdownVisible] = useState<boolean>(false);
  const [grantOpen, setGrantOpen] = useState<boolean>(false);
  const addressable = !!catalog && !!schema && !!table;

  const menuItems = useMemo(
    (): MenuProps['items'] => [
      {
        key: 'grantAccess',
        label: 'Grant access',
        onClick: () => setGrantOpen(true),
        icon: <SafetyOutlined />,
      },
    ],
    [],
  );

  if (!addressable) return null;

  return (
    <>
      <Dropdown
        menu={{ items: menuItems }}
        trigger={['click']}
        onOpenChange={() => setDropdownVisible(!dropdownVisible)}
      >
        <Button
          type="text"
          aria-label={`Access actions for ${table}`}
          // The row itself navigates to the table page; keep that from firing.
          onClick={(e) => e.stopPropagation()}
          icon={
            <MoreOutlined
              rotate={dropdownVisible ? 90 : 0}
              style={{ transition: 'transform 0.5s' }}
            />
          }
        />
      </Dropdown>
      <GrantAccessModal
        open={grantOpen}
        closeModal={() => setGrantOpen(false)}
        target={{
          securableType: SecurableType.table,
          fullName: `${catalog}.${schema}.${table}`,
        }}
      />
    </>
  );
}
