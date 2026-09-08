import { Button, Form, Modal, Radio, Select, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useNotification } from '../../utils/NotificationContext';
import {
  AccessLevel,
  AccessTarget,
  accessLevelsFor,
  grantsFor,
  useUpdateAccess,
} from '../../hooks/access';
import { useListCatalogs } from '../../hooks/catalog';
import { useListSchemas } from '../../hooks/schemas';
import { useListTables } from '../../hooks/tables';
import { SecurableType } from '../../types/api/catalog.gen';

interface GrantToUserModalProps {
  open: boolean;
  closeModal: () => void;
  /** The user being granted to; fixed, unlike GrantAccessModal. */
  principal: string;
}

interface GrantToUserFormValues {
  catalog: string;
  schema?: string;
  table?: string;
  level: AccessLevel;
}

/**
 * Grants access to a fixed user, picking the securable instead of the
 * principal — the mirror image of {@link GrantAccessModal}, which starts from a
 * securable page and picks the user.
 *
 * Unity Catalog has no per-user permissions lookup, so the per-user view has to
 * traverse every catalog, schema and table (see UserAccessDetails). Granting
 * must not pay that cost: each level here is fetched only once its parent is
 * chosen, so opening this modal costs a single catalog listing.
 */
export default function GrantToUserModal({
  open,
  closeModal,
  principal,
}: GrantToUserModalProps) {
  const mutation = useUpdateAccess();
  const { setNotification } = useNotification();
  const submitRef = useRef<HTMLButtonElement>(null);
  const [form] = Form.useForm<GrantToUserFormValues>();

  const [catalog, setCatalog] = useState<string | undefined>();
  const [schema, setSchema] = useState<string | undefined>();
  const [table, setTable] = useState<string | undefined>();

  const catalogs = useListCatalogs();
  // Lazily chained: no schema listing until a catalog is picked, no table
  // listing until a schema is.
  const schemas = useListSchemas({
    catalog_name: catalog ?? '',
    options: { enabled: open && !!catalog },
  });
  const tables = useListTables({
    catalog_name: catalog ?? '',
    schema_name: schema ?? '',
    options: { enabled: open && !!catalog && !!schema },
  });

  useEffect(() => {
    if (open) {
      form.resetFields();
      setCatalog(undefined);
      setSchema(undefined);
      setTable(undefined);
    }
  }, [open, form]);

  // The securable is the deepest level picked, so the same modal can grant on a
  // catalog or a schema when no table is chosen.
  const target: AccessTarget | null = catalog
    ? {
        securableType: table
          ? SecurableType.table
          : schema
            ? SecurableType.schema
            : SecurableType.catalog,
        fullName: [catalog, schema, table].filter(Boolean).join('.'),
      }
    : null;

  const levels = target ? accessLevelsFor(target.securableType) : [];
  const watchedLevel = Form.useWatch('level', form);
  const effectiveLevel =
    watchedLevel && levels.includes(watchedLevel) ? watchedLevel : levels[0];
  const plannedGrants =
    target && effectiveLevel ? grantsFor(target, effectiveLevel) : [];

  const handleSubmit = useCallback(() => {
    submitRef.current?.click();
  }, []);

  return (
    <Modal
      title={`Grant access to ${principal}`}
      open={open}
      onCancel={closeModal}
      okText="Grant"
      okButtonProps={{
        loading: mutation.isPending,
        disabled: !target || !effectiveLevel,
      }}
      onOk={handleSubmit}
      destroyOnClose
    >
      <Form<GrantToUserFormValues>
        form={form}
        layout="vertical"
        onFinish={() => {
          if (!target || !effectiveLevel) return;
          mutation.mutate(
            { target, level: effectiveLevel, principal, mode: 'grant' },
            {
              onSuccess: () => {
                setNotification(
                  `Granted ${effectiveLevel} on ${target.fullName} to ${principal}`,
                  'success',
                );
                closeModal();
              },
              onError: (error: Error) => {
                setNotification(error.message, 'error');
              },
            },
          );
        }}
      >
        <Form.Item label="Catalog" required>
          <Select
            showSearch
            filterOption={(input, option) =>
              String(option?.label ?? '')
                .toLowerCase()
                .includes(input.toLowerCase())
            }
            aria-label="Catalog"
            placeholder="Select a catalog"
            loading={catalogs.isLoading}
            value={catalog}
            onChange={(value) => {
              setCatalog(value);
              setSchema(undefined);
              setTable(undefined);
            }}
            options={(catalogs.data?.catalogs ?? []).map((c) => ({
              label: c.name,
              value: c.name,
            }))}
          />
        </Form.Item>
        <Form.Item label="Schema">
          <Select
            showSearch
            filterOption={(input, option) =>
              String(option?.label ?? '')
                .toLowerCase()
                .includes(input.toLowerCase())
            }
            aria-label="Schema"
            placeholder={catalog ? 'Select a schema' : 'Select a catalog first'}
            disabled={!catalog}
            loading={schemas.isLoading}
            value={schema}
            onChange={(value) => {
              setSchema(value);
              setTable(undefined);
            }}
            options={(schemas.data?.schemas ?? []).map((s) => ({
              label: s.name,
              value: s.name,
            }))}
          />
        </Form.Item>
        <Form.Item label="Table">
          <Select
            showSearch
            filterOption={(input, option) =>
              String(option?.label ?? '')
                .toLowerCase()
                .includes(input.toLowerCase())
            }
            aria-label="Table"
            placeholder={schema ? 'Select a table' : 'Select a schema first'}
            disabled={!schema}
            loading={tables.isLoading}
            value={table}
            onChange={(value) => setTable(value)}
            options={(tables.data?.tables ?? []).map((t) => ({
              label: t.name,
              value: t.name,
            }))}
          />
        </Form.Item>
        {target && levels.length > 0 && (
          <Form.Item label="Access level" name="level" initialValue={levels[0]}>
            <Radio.Group
              options={levels.map((level) => ({
                label: level,
                value: level,
              }))}
            />
          </Form.Item>
        )}
        {plannedGrants.length > 0 && (
          <>
            <Typography.Text type="secondary">
              This writes the following privileges:
            </Typography.Text>
            <ul>
              {plannedGrants.map((grant) => (
                <li
                  key={`${grant.securable_type}-${grant.full_name}-${grant.privilege}`}
                >
                  <Typography.Text code>{grant.privilege}</Typography.Text> on{' '}
                  {grant.securable_type}{' '}
                  <Typography.Text code>{grant.full_name}</Typography.Text>
                </li>
              ))}
            </ul>
          </>
        )}
        <Button type="primary" htmlType="submit" ref={submitRef} hidden />
      </Form>
    </Modal>
  );
}
