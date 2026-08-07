import { useState } from 'react';
import { CreateCatalogModal } from '../modals/CreateCatalogModal';
import { Button, Tooltip } from 'antd';
import { useAuthorized } from '../../hooks/authz';
import { Privilege, SecurableType } from '../../types/api/catalog.gen';

export default function CreateCatalogAction() {
  const [open, setOpen] = useState(false);
  // Server rule: metastore OWNER or CREATE_CATALOG on the metastore.
  const authz = useAuthorized([
    {
      securableType: SecurableType.metastore,
      fullName: 'metastore',
      anyOf: [Privilege.CREATE_CATALOG],
    },
  ]);

  return (
    <>
      <Tooltip
        title={
          authz.ready && !authz.allowed
            ? 'Requires CREATE CATALOG on the metastore (server-enforced).'
            : undefined
        }
      >
        <Button
          type="primary"
          disabled={!authz.allowed}
          onClick={() => setOpen(true)}
        >
          Create Catalog
        </Button>
      </Tooltip>
      <CreateCatalogModal open={open} closeModal={() => setOpen(false)} />
    </>
  );
}
