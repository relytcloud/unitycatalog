import { useState } from 'react';
import { Button, Tooltip } from 'antd';
import CreateSchemaModal from '../modals/CreateSchemaModal';
import { useAuthorized } from '../../hooks/authz';
import { Privilege, SecurableType } from '../../types/api/catalog.gen';

interface CreateSchemaActionProps {
  catalog: string;
  /** The catalog's owner, if known — a positive gating signal only. */
  catalogOwner?: string;
}

export default function CreateSchemaAction({
  catalog,
  catalogOwner,
}: CreateSchemaActionProps) {
  const [open, setOpen] = useState(false);
  // Server rule: catalog OWNER, or USE_CATALOG + CREATE_SCHEMA on the catalog.
  const authz = useAuthorized([
    {
      securableType: SecurableType.catalog,
      fullName: catalog,
      allOf: [Privilege.USE_CATALOG, Privilege.CREATE_SCHEMA],
      ownerAnyOf: [catalogOwner],
    },
  ]);

  return (
    <>
      <Tooltip
        title={
          authz.ready && !authz.allowed
            ? 'Requires catalog ownership or USE CATALOG + CREATE SCHEMA (server-enforced).'
            : undefined
        }
      >
        <Button
          type="primary"
          disabled={!authz.allowed}
          onClick={() => setOpen(true)}
        >
          Create Schema
        </Button>
      </Tooltip>
      <CreateSchemaModal
        open={open}
        closeModal={() => setOpen(false)}
        catalog={catalog}
      />
    </>
  );
}
