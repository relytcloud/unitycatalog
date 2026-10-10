import { Button, Form, Modal, Radio, Select, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useNotification } from '../../utils/NotificationContext';
import {
  AccessLevel,
  AccessTarget,
  accessLevelsFor,
  grantsFor,
  isAdminOnly,
  useUpdateAccess,
} from '../../hooks/access';
import { useCanManageGrants, useIsMetastoreAdmin } from '../../hooks/authz';
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
 *
 * The same gates as GrantAccessModal apply: read on a schema or catalog is a
 * metastore admin's alone, and every grant writes USE_CATALOG, so the caller
 * must own the chosen catalog (or be an admin) for the Grant button to light
 * up. The server re-authorizes each PATCH regardless.
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

  const { data: isAdmin = false } = useIsMetastoreAdmin();
  // Every level writes USE_CATALOG on the chosen catalog, so that is the
  // authority to check — its owner, or a metastore admin.
  const catalogOwner = (catalogs.data?.catalogs ?? []).find(
    (c) => c.name === catalog,
  )?.owner;
  const canGrant = useCanManageGrants(SecurableType.catalog, catalog, [
    catalogOwner,
  ]);

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
  const isDisabled = (level: AccessLevel) =>
    !!target && isAdminOnly(target.securableType, level) && !isAdmin;
  const defaultLevel = levels.find((level) => !isDisabled(level)) ?? levels[0];
  const watchedLevel = Form.useWatch('level', form);
  const effectiveLevel =
    watchedLevel && levels.includes(watchedLevel) && !isDisabled(watchedLevel)
      ? watchedLevel
      : defaultLevel;
  // The admin signal arrives after the first render, and the target changes
  // with every pick; keep the form off a level that is not available.
  useEffect(() => {
    if (!open || !target) return;
    const current = form.getFieldValue('level') as AccessLevel | undefined;
    if (
      current === undefined ||
      !levels.includes(current) ||
      isDisabled(current)
    ) {
      form.setFieldValue('level', defaultLevel);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- defaultLevel already derives from target and isAdmin
  }, [open, form, defaultLevel, target?.fullName]);

  const plannedGrants =
    target && effectiveLevel ? grantsFor(target, effectiveLevel) : [];
  const grantBlocked = !!catalog && canGrant.ready && !canGrant.allowed;

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
        disabled: !target || !effectiveLevel || grantBlocked,
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
        {grantBlocked && (
          <Typography.Paragraph type="warning" style={{ fontSize: 12 }}>
            Granting on this catalog requires owning it (or being a metastore
            admin), because every level also writes USE CATALOG on it; the
            server enforces this.
          </Typography.Paragraph>
        )}
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
          <Form.Item
            label="Access level"
            name="level"
            initialValue={defaultLevel}
          >
            <Radio.Group
              options={levels.map((level) => ({
                label: level,
                value: level,
                disabled: isDisabled(level),
              }))}
            />
          </Form.Item>
        )}
        {target &&
          levels.some((level) => isAdminOnly(target.securableType, level)) && (
            <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
              Read on a {target.securableType} covers every table beneath it,
              including tables created later. Only a metastore admin can grant
              or revoke it
              {isAdmin ? '.' : ', which is why it is unavailable to you.'}
            </Typography.Paragraph>
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
