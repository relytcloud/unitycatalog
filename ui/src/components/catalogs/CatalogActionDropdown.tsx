import { DeleteOutlined, MoreOutlined } from '@ant-design/icons';
import { Button, Dropdown, MenuProps } from 'antd';
import { useMemo, useState } from 'react';
import { DeleteCatalogModal } from '../modals/DeleteCatalogModal';
import { useAuthorized } from '../../hooks/authz';
import { SecurableType } from '../../types/api/catalog.gen';

interface CatalogActionDropdownProps {
  catalog: string;
  /** The catalog's owner, if known — a positive gating signal only. */
  catalogOwner?: string;
}

enum CatalogActionsEnum {
  Delete,
}

export default function CatalogActionsDropdown({
  catalog,
  catalogOwner,
}: CatalogActionDropdownProps) {
  const [dropdownVisible, setDropdownVisible] = useState<boolean>(false);
  const [action, setAction] = useState<CatalogActionsEnum | null>(null);
  // Deleting a catalog is destructive and should require OWNERSHIP (catalog
  // owner or metastore admin) — never a plain reader, so gate on ownership
  // only, mirroring SchemaActionDropdown.
  //
  // NOTE: the server's deleteCatalog rule currently ALSO accepts USE_CATALOG
  // (a read privilege), so a reader can still delete via API/CLI regardless of
  // this button. That over-permission is a server bug tracked in
  // zbyte/unitycatalog#10 and fixed server-side separately; the UI must not
  // advertise it by enabling the button for readers.
  const canDelete = useAuthorized([
    {
      securableType: SecurableType.catalog,
      fullName: catalog,
      ownerAnyOf: [catalogOwner],
    },
  ]);

  const menuItems = useMemo(
    (): MenuProps['items'] => [
      {
        key: 'deleteCatalog',
        label: 'Delete Catalog',
        onClick: () => setAction(CatalogActionsEnum.Delete),
        icon: <DeleteOutlined />,
        danger: true,
        disabled: !canDelete.allowed,
      },
    ],
    [canDelete.allowed],
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
          icon={
            <MoreOutlined
              rotate={dropdownVisible ? 90 : 0}
              style={{ transition: 'transform 0.5s' }}
            />
          }
        />
      </Dropdown>
      <DeleteCatalogModal
        open={action === CatalogActionsEnum.Delete}
        closeModal={() => setAction(null)}
        catalog={catalog}
      />
    </>
  );
}
