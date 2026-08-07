import { useState } from 'react';
import { Button, Tooltip } from 'antd';
import { DeleteOutlined } from '@ant-design/icons';
import { DeleteTableModal } from '../modals/DeleteTableModal';
import { useAuthorized } from '../../hooks/authz';
import { SecurableType } from '../../types/api/catalog.gen';

interface DropTableButtonProps {
  catalog: string;
  schema: string;
  table: string;
  /** Owners along the chain (table, schema, catalog) for button gating. */
  owners?: (string | undefined)[];
}

/**
 * Explicit "Drop Table" action on the table page. Dropping requires ownership
 * along the chain (catalog / schema / table owner — see TableService); the
 * button is a positive-signal hint only and the server enforces the rule.
 */
export default function DropTableButton({
  catalog,
  schema,
  table,
  owners = [],
}: DropTableButtonProps) {
  const [open, setOpen] = useState(false);
  const fullName = [catalog, schema, table].join('.');
  const authz = useAuthorized([
    { securableType: SecurableType.table, fullName, ownerAnyOf: owners },
  ]);

  return (
    <>
      <Tooltip
        title={
          authz.ready && !authz.allowed
            ? 'Dropping requires table/schema/catalog ownership; the server enforces this.'
            : undefined
        }
      >
        <Button
          danger
          icon={<DeleteOutlined />}
          disabled={!authz.allowed}
          onClick={() => setOpen(true)}
        >
          Drop Table
        </Button>
      </Tooltip>
      <DeleteTableModal
        open={open}
        closeModal={() => setOpen(false)}
        catalog={catalog}
        schema={schema}
        table={table}
      />
    </>
  );
}
