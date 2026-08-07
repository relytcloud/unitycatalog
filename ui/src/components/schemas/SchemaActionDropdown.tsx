import {
  DeleteOutlined,
  DeploymentUnitOutlined,
  MoreOutlined,
} from '@ant-design/icons';
import { Button, Dropdown, MenuProps } from 'antd';
import React, { useMemo, useState } from 'react';
import { DeleteSchemaModal } from '../modals/DeleteSchemaModal';
import CreateModelModal from '../modals/CreateModelModal';
import { useAuthorized } from '../../hooks/authz';
import { Privilege, SecurableType } from '../../types/api/catalog.gen';

interface SchemaActionDropdownProps {
  catalog: string;
  schema: string;
  /** Owners along the chain, if known — positive gating signals only. */
  catalogOwner?: string;
  schemaOwner?: string;
}

enum SchemaActionsEnum {
  Delete,
  CreateModel,
}

export default function SchemaActionsDropdown({
  catalog,
  schema,
  catalogOwner,
  schemaOwner,
}: SchemaActionDropdownProps) {
  const [dropdownVisible, setDropdownVisible] = useState<boolean>(false);
  const [action, setAction] = useState<SchemaActionsEnum | null>(null);
  const schemaFullName = `${catalog}.${schema}`;
  // Server rule for DELETE /schemas: metastore OWNER, catalog OWNER, or
  // schema OWNER (with USE_CATALOG) — ownership only, plain privileges don't
  // qualify.
  const canDelete = useAuthorized([
    {
      securableType: SecurableType.schema,
      fullName: schemaFullName,
      ownerAnyOf: [schemaOwner, catalogOwner],
    },
  ]);
  // Server rule for creating a model mirrors table creation with
  // CREATE_MODEL instead of CREATE_TABLE.
  const canCreateModel = useAuthorized([
    {
      securableType: SecurableType.catalog,
      fullName: catalog,
      anyOf: [Privilege.USE_CATALOG],
      ownerAnyOf: [catalogOwner],
    },
    {
      securableType: SecurableType.schema,
      fullName: schemaFullName,
      allOf: [Privilege.USE_SCHEMA, Privilege.CREATE_MODEL],
      ownerAnyOf: [schemaOwner, catalogOwner],
    },
  ]);

  const menuItems = useMemo(
    (): MenuProps['items'] => [
      {
        key: 'createModel',
        label: 'Create Registered Model',
        onClick: () => setAction(SchemaActionsEnum.CreateModel),
        icon: <DeploymentUnitOutlined />,
        danger: false,
        disabled: !canCreateModel.allowed,
      },
      {
        key: 'deleteSchema',
        label: 'Delete Schema',
        onClick: () => setAction(SchemaActionsEnum.Delete),
        icon: <DeleteOutlined />,
        danger: true,
        disabled: !canDelete.allowed,
      },
    ],
    [canCreateModel.allowed, canDelete.allowed],
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
      <DeleteSchemaModal
        open={action === SchemaActionsEnum.Delete}
        closeModal={() => setAction(null)}
        catalog={catalog}
        schema={schema}
      />
      <CreateModelModal
        open={action === SchemaActionsEnum.CreateModel}
        closeModal={() => setAction(null)}
        catalog={catalog}
        schema={schema}
      />
    </>
  );
}
