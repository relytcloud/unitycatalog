import { useState } from 'react';
import { Button, Tooltip } from 'antd';
import { CreateTableModal } from '../modals/CreateTableModal';
import { useAuthorized } from '../../hooks/authz';
import { Privilege, SecurableType } from '../../types/api/catalog.gen';

interface CreateTableActionProps {
  catalog: string;
  schema: string;
  /** Owners along the chain, if known — positive gating signals only. */
  catalogOwner?: string;
  schemaOwner?: string;
}

export default function CreateTableAction({
  catalog,
  schema,
  catalogOwner,
  schemaOwner,
}: CreateTableActionProps) {
  const [open, setOpen] = useState(false);
  // Server rule: (catalog OWNER or USE_CATALOG) AND (schema OWNER or
  // USE_SCHEMA + CREATE_TABLE); external tables additionally need rights on
  // the external location, which only the server can decide.
  const authz = useAuthorized([
    {
      securableType: SecurableType.catalog,
      fullName: catalog,
      anyOf: [Privilege.USE_CATALOG],
      ownerAnyOf: [catalogOwner],
    },
    {
      securableType: SecurableType.schema,
      fullName: `${catalog}.${schema}`,
      allOf: [Privilege.USE_SCHEMA, Privilege.CREATE_TABLE],
      ownerAnyOf: [schemaOwner, catalogOwner],
    },
  ]);

  return (
    <>
      <Tooltip
        title={
          authz.ready && !authz.allowed
            ? 'Requires schema ownership or USE + CREATE TABLE privileges (server-enforced).'
            : undefined
        }
      >
        <Button
          type="primary"
          disabled={!authz.allowed}
          onClick={() => setOpen(true)}
        >
          Create Table
        </Button>
      </Tooltip>
      <CreateTableModal
        open={open}
        closeModal={() => setOpen(false)}
        catalog={catalog}
        schema={schema}
      />
    </>
  );
}
