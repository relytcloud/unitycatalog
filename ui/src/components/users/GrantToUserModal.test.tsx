import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import GrantToUserModal from './GrantToUserModal';
import { useListCatalogs } from '../../hooks/catalog';
import { useListSchemas } from '../../hooks/schemas';
import { useListTables } from '../../hooks/tables';
import { useUpdateAccess } from '../../hooks/access';

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

const mockUseListCatalogs = useListCatalogs as jest.Mock;
const mockUseListSchemas = useListSchemas as jest.Mock;
const mockUseListTables = useListTables as jest.Mock;
const mockUseUpdateAccess = useUpdateAccess as jest.Mock;

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
  mockUseListCatalogs.mockReturnValue({
    data: { catalogs: [{ name: 'main' }] },
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
});
