import { DeleteOutlined, MoreOutlined } from '@ant-design/icons';
import { Button, Dropdown, MenuProps } from 'antd';
import { useMemo, useState } from 'react';
import { DeleteCatalogModal } from '../modals/DeleteCatalogModal';
import { useAuthorized } from '../../hooks/authz';
import { Privilege, SecurableType } from '../../types/api/catalog.gen';

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
  // Server rule for DELETE /catalogs: metastore OWNER, or catalog
  // OWNER/USE_CATALOG.
  const canDelete = useAuthorized([
    {
      securableType: SecurableType.catalog,
      fullName: catalog,
      anyOf: [Privilege.USE_CATALOG],
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
