import { useState } from 'react';
import { Button } from 'antd';
import { CreateTableModal } from '../modals/CreateTableModal';

interface CreateTableActionProps {
  catalog: string;
  schema: string;
}

export default function CreateTableAction({
  catalog,
  schema,
}: CreateTableActionProps) {
  const [open, setOpen] = useState(false);

  return (
    <>
      <Button type="primary" onClick={() => setOpen(true)}>
        Create Table
      </Button>
      <CreateTableModal
        open={open}
        closeModal={() => setOpen(false)}
        catalog={catalog}
        schema={schema}
      />
    </>
  );
}
