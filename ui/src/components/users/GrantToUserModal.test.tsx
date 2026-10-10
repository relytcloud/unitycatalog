import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import GrantToUserModal from './GrantToUserModal';
import { useListCatalogs } from '../../hooks/catalog';
import { useListSchemas } from '../../hooks/schemas';
import { useListTables } from '../../hooks/tables';
import { useUpdateAccess } from '../../hooks/access';
import { useCanManageGrants, useIsMetastoreAdmin } from '../../hooks/authz';

jest.mock('../../hooks/catalog');
jest.mock('../../hooks/schemas');
jest.mock('../../hooks/tables');
jest.mock('../../hooks/access', () => ({
  ...jest.requireActual('../../hooks/access'),
  useUpdateAccess: jest.fn(),
}));
jest.mock('../../utils/NotificationContext', () => ({
  useNotification: () => ({ setNotification: jest.fn() }),
}));
jest.mock('../../hooks/authz', () => ({
  useIsMetastoreAdmin: jest.fn(),
  useCanManageGrants: jest.fn(),
}));

const mockUseListCatalogs = useListCatalogs as jest.Mock;
const mockUseListSchemas = useListSchemas as jest.Mock;
const mockUseListTables = useListTables as jest.Mock;
const mockUseUpdateAccess = useUpdateAccess as jest.Mock;
const mockUseIsMetastoreAdmin = useIsMetastoreAdmin as jest.Mock;
const mockUseCanManageGrants = useCanManageGrants as jest.Mock;

const mutate = jest.fn();

function renderModal() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <GrantToUserModal
        open
        closeModal={jest.fn()}
        principal="customer@example.com"
      />
    </QueryClientProvider>,
  );
}

/** antd renders its Select dropdown in a body portal, so drive it by mouseDown. */
async function pick(selectIndex: number, optionText: string) {
  // eslint-disable-next-line testing-library/no-node-access -- antd Select renders in a body portal
  const selectors = document.querySelectorAll('.ant-select-selector');
  fireEvent.mouseDown(selectors[selectIndex]);
  const option = await screen.findByTitle(optionText);
  fireEvent.click(option);
}

beforeEach(() => {
  jest.clearAllMocks();
  mockUseUpdateAccess.mockReturnValue({ mutate, isPending: false });
  // Default: an ordinary user who owns the catalog they pick.
  mockUseIsMetastoreAdmin.mockReturnValue({ data: false });
  mockUseCanManageGrants.mockReturnValue({ allowed: true, ready: true });
  mockUseListCatalogs.mockReturnValue({
    data: { catalogs: [{ name: 'main', owner: 'me@example.com' }] },
    isLoading: false,
  });
  mockUseListSchemas.mockReturnValue({
    data: { schemas: [{ name: 'sales' }] },
    isLoading: false,
  });
  mockUseListTables.mockReturnValue({
    data: { tables: [{ name: 'orders' }] },
    isLoading: false,
  });
});

describe('GrantToUserModal', () => {
  /**
   * The point of granting from the user list is to avoid the per-user
   * permission scan, so nothing below the chosen level may be fetched up front.
   */
  it('does not list schemas or tables before their parent is chosen', () => {
    renderModal();

    expect(mockUseListSchemas).toHaveBeenCalledWith(
      expect.objectContaining({ options: { enabled: false } }),
    );
    expect(mockUseListTables).toHaveBeenCalledWith(
      expect.objectContaining({ options: { enabled: false } }),
    );
  });

  it('enables the schema listing once a catalog is chosen, tables still gated', async () => {
    renderModal();

    await pick(0, 'main');

    await waitFor(() =>
      expect(mockUseListSchemas).toHaveBeenLastCalledWith(
        expect.objectContaining({
          catalog_name: 'main',
          options: { enabled: true },
        }),
      ),
    );
    expect(mockUseListTables).toHaveBeenLastCalledWith(
      expect.objectContaining({ options: { enabled: false } }),
    );
  });

  it('grants read on the selected table to the fixed principal', async () => {
    renderModal();

    await pick(0, 'main');
    await pick(1, 'sales');
    await pick(2, 'orders');

    fireEvent.click(screen.getByRole('button', { name: 'Grant' }));

    await waitFor(() => expect(mutate).toHaveBeenCalled());
    expect(mutate).toHaveBeenCalledWith(
      expect.objectContaining({
        principal: 'customer@example.com',
        mode: 'grant',
        target: expect.objectContaining({ fullName: 'main.sales.orders' }),
      }),
      expect.anything(),
    );
  });

  /**
   * Read on a catalog or schema reaches every table beneath it and is the
   * metastore admin's alone (same gate as GrantAccessModal): an ordinary user
   * sees it disabled, lands on create, and the plan shows the create grants.
   */
  it('disables read on a catalog for a non-admin and defaults to create', async () => {
    renderModal();

    await pick(0, 'main');

    const read = await screen.findByRole('radio', { name: 'read' });
    expect(read).toBeDisabled();
    await waitFor(() =>
      expect(screen.getByRole('radio', { name: 'create' })).toBeChecked(),
    );
    expect(screen.getByText('CREATE SCHEMA')).toBeInTheDocument();
    expect(screen.queryByText('SELECT')).toBeNull();
    expect(
      screen.getByText(/which is why it is unavailable to you/),
    ).toBeInTheDocument();
  });

  it('lets a metastore admin grant catalog read, writing USE_* and SELECT on the catalog', async () => {
    mockUseIsMetastoreAdmin.mockReturnValue({ data: true });
    renderModal();

    await pick(0, 'main');

    const read = await screen.findByRole('radio', { name: 'read' });
    expect(read).toBeEnabled();
    await waitFor(() => expect(read).toBeChecked());
    expect(screen.getByText('USE CATALOG')).toBeInTheDocument();
    expect(screen.getByText('USE SCHEMA')).toBeInTheDocument();
    expect(screen.getByText('SELECT')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Grant' }));

    await waitFor(() => expect(mutate).toHaveBeenCalled());
    expect(mutate).toHaveBeenCalledWith(
      expect.objectContaining({
        level: 'read',
        target: expect.objectContaining({ fullName: 'main' }),
      }),
      expect.anything(),
    );
  });

  /**
   * Every level writes USE_CATALOG on the chosen catalog, so a user who is
   * neither its owner nor an admin cannot complete any grant there: the
   * button stays off and the reason is shown, instead of a 403 after the fact.
   */
  it('blocks granting on a catalog the user does not own', async () => {
    mockUseCanManageGrants.mockReturnValue({ allowed: false, ready: true });
    renderModal();

    await pick(0, 'main');

    expect(
      await screen.findByText(
        /requires owning it \(or being a metastore admin\)/,
      ),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Grant' })).toBeDisabled();
  });
});
