import { Button, Flex, Form, Input, Modal, Select, Typography } from 'antd';
import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { useCallback, useEffect, useMemo, useRef } from 'react';
import TextArea from 'antd/es/input/TextArea';
import { useNavigate } from 'react-router-dom';
import { useNotification } from '../../utils/NotificationContext';
import { useCreateTable } from '../../hooks/tables';
import { useListExternalLocations } from '../../hooks/externalLocations';
import {
  ColumnTypeName,
  DataSourceFormat,
  TableType,
} from '../../types/api/catalog.gen';
import {
  buildColumnInfo,
  CREATE_TABLE_COLUMN_TYPES,
} from '../../utils/sparkTypes';
import { joinStorageLocation } from '../../utils/externalLocation';

interface CreateTableFormValues {
  name: string;
  data_source_format: DataSourceFormat;
  external_location: string;
  subpath?: string;
  columns: { name: string; type_name: ColumnTypeName }[];
  comment?: string;
}

interface CreateTableModalProps {
  open: boolean;
  closeModal: () => void;
  catalog: string;
  schema: string;
}

export function CreateTableModal({
  open,
  closeModal,
  catalog,
  schema,
}: CreateTableModalProps) {
  const navigate = useNavigate();
  const mutation = useCreateTable();
  const { data: locationsData } = useListExternalLocations();
  const { setNotification } = useNotification();
  const submitRef = useRef<HTMLButtonElement>(null);
  const [form] = Form.useForm<CreateTableFormValues>();
  const selectedLocationName = Form.useWatch('external_location', form);
  const subpath = Form.useWatch('subpath', form);

  // Reset each time the modal opens: the useForm store outlives destroyOnClose,
  // so a cancelled draft (name/columns/subpath) would otherwise reappear.
  useEffect(() => {
    if (open) form.resetFields();
  }, [open, form]);

  const locations = useMemo(
    () => locationsData?.external_locations ?? [],
    [locationsData],
  );
  const selectedLocation = locations.find(
    (location) => location.name === selectedLocationName,
  );
  const storagePreview = selectedLocation?.url
    ? joinStorageLocation(selectedLocation.url, subpath)
    : '';

  const handleSubmit = useCallback(() => {
    submitRef.current?.click();
  }, []);

  return (
    <Modal
      title={<Typography.Title level={4}>Create table</Typography.Title>}
      okText="Create"
      cancelText="Cancel"
      open={open}
      destroyOnClose
      onCancel={closeModal}
      onOk={handleSubmit}
      okButtonProps={{ loading: mutation.isPending }}
      width={640}
    >
      <Typography.Paragraph type="secondary">
        Create an EXTERNAL table under {catalog}.{schema}. The storage path is
        chosen from a registered external location, so access is automatically
        bound to that location's credential. Register the external location
        first if it does not exist yet.
      </Typography.Paragraph>
      <Form<CreateTableFormValues>
        form={form}
        layout="vertical"
        initialValues={{
          data_source_format: DataSourceFormat.DELTA,
          columns: [{ name: '', type_name: ColumnTypeName.STRING }],
        }}
        onFinish={(values) => {
          const location = locations.find(
            (candidate) => candidate.name === values.external_location,
          );
          if (!location?.url) {
            setNotification('Please select an external location', 'error');
            return;
          }
          mutation.mutate(
            {
              name: values.name,
              catalog_name: catalog,
              schema_name: schema,
              table_type: TableType.EXTERNAL,
              data_source_format: values.data_source_format,
              storage_location: joinStorageLocation(
                location.url,
                values.subpath,
              ),
              columns: values.columns.map((column, index) =>
                buildColumnInfo(column.name, column.type_name, index),
              ),
              comment: values.comment,
            },
            {
              onError: (error: Error) => {
                setNotification(error.message, 'error');
              },
              onSuccess: (table) => {
                setNotification(`Table ${table.name} created`, 'success');
                closeModal();
                navigate(`/data/${catalog}/${schema}/${table.name}`);
              },
            },
          );
        }}
        name="Create table form"
      >
        <Form.Item
          required
          label={<Typography.Text strong>Name</Typography.Text>}
          name="name"
          rules={[{ required: true, message: 'Name is required' }]}
        >
          <Input />
        </Form.Item>
        <Form.Item
          required
          label={<Typography.Text strong>Data source format</Typography.Text>}
          name="data_source_format"
          rules={[{ required: true, message: 'Format is required' }]}
        >
          <Select
            options={[
              DataSourceFormat.DELTA,
              DataSourceFormat.PARQUET,
              DataSourceFormat.CSV,
              DataSourceFormat.JSON,
              DataSourceFormat.ORC,
              DataSourceFormat.AVRO,
              DataSourceFormat.TEXT,
            ].map((format) => ({ value: format, label: format }))}
          />
        </Form.Item>
        <Form.Item
          required
          label={<Typography.Text strong>External location</Typography.Text>}
          name="external_location"
          rules={[{ required: true, message: 'External location is required' }]}
          extra={
            selectedLocation
              ? `Credential: ${selectedLocation.credential_name ?? '-'}`
              : 'The table path must live under a registered external location'
          }
        >
          <Select
            showSearch
            placeholder="Select an external location"
            options={locations.map((location) => ({
              value: location.name,
              label: `${location.name}  (${location.url})`,
            }))}
          />
        </Form.Item>
        <Form.Item
          label={
            <Typography.Text strong>
              Subpath under the location (optional)
            </Typography.Text>
          }
          name="subpath"
          rules={[
            {
              // A '..' segment escapes the chosen external location; the
              // server authorizes by raw string prefix, so the vended
              // credentials (scoped to the location) would then AccessDenied.
              validator: async (_, value?: string) => {
                if (
                  value &&
                  value
                    .split('/')
                    .some((segment) => segment === '..' || segment === '.')
                ) {
                  return Promise.reject(
                    new Error("Subpath must not contain '.' or '..' segments"),
                  );
                }
              },
            },
          ]}
          extra={
            storagePreview ? (
              <>
                Storage location:{' '}
                <Typography.Text code>{storagePreview}</Typography.Text>
              </>
            ) : undefined
          }
        >
          <Input placeholder="tables/my_table" />
        </Form.Item>
        <Typography.Text strong>Columns</Typography.Text>
        <Form.List
          name="columns"
          rules={[
            {
              validator: async (
                _,
                columns: { name?: string }[] | undefined,
              ) => {
                if (!columns || columns.length < 1) {
                  return Promise.reject(
                    new Error('At least one column is required'),
                  );
                }
                const names = columns.map((column) =>
                  (column?.name ?? '').trim(),
                );
                if (names.some((name) => name === '')) {
                  return Promise.reject(
                    new Error('Column names must not be blank'),
                  );
                }
                if (new Set(names).size !== names.length) {
                  return Promise.reject(
                    new Error('Column names must be unique'),
                  );
                }
              },
            },
          ]}
        >
          {(fields, { add, remove }, { errors }) => (
            <Flex vertical gap="small" style={{ marginTop: 8 }}>
              {fields.map(({ key, name, ...restField }) => (
                <Flex key={key} gap="small" align="baseline">
                  <Form.Item
                    {...restField}
                    name={[name, 'name']}
                    rules={[
                      {
                        required: true,
                        whitespace: true,
                        message: 'Column name is required',
                      },
                    ]}
                    style={{ flex: 1, marginBottom: 0 }}
                  >
                    <Input placeholder="column name" />
                  </Form.Item>
                  <Form.Item
                    {...restField}
                    name={[name, 'type_name']}
                    rules={[{ required: true, message: 'Type is required' }]}
                    style={{ width: 160, marginBottom: 0 }}
                  >
                    <Select
                      options={CREATE_TABLE_COLUMN_TYPES.map((type) => ({
                        value: type,
                        label: type,
                      }))}
                    />
                  </Form.Item>
                  <MinusCircleOutlined
                    onClick={() => remove(name)}
                    style={{ color: '#999' }}
                  />
                </Flex>
              ))}
              <Button
                type="dashed"
                onClick={() =>
                  add({ name: '', type_name: ColumnTypeName.STRING })
                }
                icon={<PlusOutlined />}
              >
                Add column
              </Button>
              <Form.ErrorList errors={errors} />
            </Flex>
          )}
        </Form.List>
        <Form.Item
          label={<Typography.Text strong>Comment</Typography.Text>}
          name="comment"
          style={{ marginTop: 16 }}
        >
          <TextArea />
        </Form.Item>
        <Form.Item hidden>
          <Button type="primary" htmlType="submit" ref={submitRef}>
            Create
          </Button>
        </Form.Item>
      </Form>
    </Modal>
  );
}
