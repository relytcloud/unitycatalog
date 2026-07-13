import { useState } from 'react';
import { Button, Typography } from 'antd';
import { Link, useNavigate } from 'react-router-dom';
import ListLayout from '../layouts/ListLayout';
import { formatTimestamp } from '../../utils/formatTimestamp';
import {
  ExternalLocationInterface,
  useListExternalLocations,
} from '../../hooks/externalLocations';
import { CreateExternalLocationModal } from '../modals/CreateExternalLocationModal';

export default function ExternalLocationsList() {
  const { data, isLoading, error } = useListExternalLocations();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);

  return (
    <>
      <ListLayout<ExternalLocationInterface>
        loading={isLoading}
        error={error}
        title={
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <Button type="primary" onClick={() => setOpen(true)}>
              Create External Location
            </Button>
          </div>
        }
        data={data?.external_locations}
        onRowClick={(record) =>
          navigate(`/external-data/external-locations/${record.name}`)
        }
        rowKey={(record) => `external-location-${record.id}`}
        columns={[
          { title: 'Name', dataIndex: 'name', key: 'name', width: '20%' },
          {
            title: 'URL',
            dataIndex: 'url',
            key: 'url',
            width: '35%',
            render: (value) => <Typography.Text code>{value}</Typography.Text>,
          },
          {
            title: 'Credential',
            dataIndex: 'credential_name',
            key: 'credential_name',
            width: '15%',
            render: (value) =>
              value ? (
                <Link
                  to={`/external-data/credentials/${value}`}
                  onClick={(e) => e.stopPropagation()}
                >
                  {value}
                </Link>
              ) : (
                ''
              ),
          },
          { title: 'Owner', dataIndex: 'owner', key: 'owner', width: '15%' },
          {
            title: 'Created At',
            dataIndex: 'created_at',
            key: 'created_at',
            width: '15%',
            render: (value) => (value ? formatTimestamp(value) : ''),
          },
        ]}
      />
      <CreateExternalLocationModal
        open={open}
        closeModal={() => setOpen(false)}
      />
    </>
  );
}
