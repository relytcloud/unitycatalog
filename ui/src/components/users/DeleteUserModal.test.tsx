import { fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import DeleteUserModal from './DeleteUserModal';
import {
  usePurgeScimUser,
  useScimUserOptions,
  useUserOwnedObjects,
} from '../../hooks/users';
import { useCurrentPrincipal } from '../../hooks/authz';

jest.mock('../../hooks/users', () => ({
  ...jest.requireActual('../../hooks/users'),
  useUserOwnedObjects: jest.fn(),
  useScimUserOptions: jest.fn(),
  usePurgeScimUser: jest.fn(),
}));
jest.mock('../../hooks/authz');
jest.mock('../../utils/NotificationContext', () => ({
  useNotification: () => ({ setNotification: jest.fn() }),
}));

const mockUseUserOwnedObjects = useUserOwnedObjects as jest.Mock;
const mockUseScimUserOptions = useScimUserOptions as jest.Mock;
const mockUsePurgeScimUser = usePurgeScimUser as jest.Mock;
const mockUseCurrentPrincipal = useCurrentPrincipal as jest.Mock;

const mutate = jest.fn();
const PRINCIPAL = 'alice@example.com';

function renderModal() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <DeleteUserModal
        open
        closeModal={jest.fn()}
        userId="user-1"
        principal={PRINCIPAL}
        displayName="Alice"
      />
    </QueryClientProvider>,
  );
}

function deleteButton() {
  return screen.getByRole('button', { name: 'Delete permanently' });
}

function ownsNothing() {
  mockUseUserOwnedObjects.mockReturnValue({
    data: { principal: PRINCIPAL, owned: [] },
    isPending: false,
    error: null,
  });
}

beforeEach(() => {
  jest.clearAllMocks();
  mockUsePurgeScimUser.mockReturnValue({ mutate, isPending: false });
  mockUseCurrentPrincipal.mockReturnValue({ data: 'admin@example.com' });
  mockUseScimUserOptions.mockReturnValue([
    { value: 'admin@example.com', label: 'Admin (admin@example.com)' },
    { value: PRINCIPAL, label: `Alice (${PRINCIPAL})` },
  ]);
  ownsNothing();
});

describe('DeleteUserModal', () => {
  it('stays disabled until the principal is typed back exactly', () => {
    renderModal();
    expect(deleteButton()).toBeDisabled();

    fireEvent.change(screen.getByLabelText('Confirm principal'), {
      target: { value: 'alice@example.co' },
    });
    expect(deleteButton()).toBeDisabled();

    fireEvent.change(screen.getByLabelText('Confirm principal'), {
      target: { value: PRINCIPAL },
    });
    expect(deleteButton()).toBeEnabled();
  });

  it('deletes without a reassignment when the user owns nothing', () => {
    renderModal();
    fireEvent.change(screen.getByLabelText('Confirm principal'), {
      target: { value: PRINCIPAL },
    });
    fireEvent.click(deleteButton());

    expect(mutate).toHaveBeenCalledWith(
      { id: 'user-1', principal: PRINCIPAL, reassignTo: undefined },
      expect.anything(),
    );
  });

  it('lists what the user owns and hands it to the acting administrator', () => {
    mockUseUserOwnedObjects.mockReturnValue({
      data: {
        principal: PRINCIPAL,
        owned: [
          {
            securable_type: 'catalog',
            full_name: 'sales',
            securable_id: 'c1',
          },
          {
            securable_type: 'table',
            full_name: 'sales.raw.orders',
            securable_id: 't1',
          },
        ],
      },
      isPending: false,
      error: null,
    });
    renderModal();

    expect(screen.getByText('sales.raw.orders')).toBeInTheDocument();
    // The acting administrator is the default target -- the built-in `admin`
    // account may itself be deactivated, so it is a poor place to park assets.
    expect(screen.getByText('Admin (admin@example.com)')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Confirm principal'), {
      target: { value: PRINCIPAL },
    });
    fireEvent.click(deleteButton());

    expect(mutate).toHaveBeenCalledWith(
      {
        id: 'user-1',
        principal: PRINCIPAL,
        reassignTo: 'admin@example.com',
      },
      expect.anything(),
    );
  });

  it('never offers the user being deleted as the new owner', () => {
    mockUseUserOwnedObjects.mockReturnValue({
      data: {
        principal: PRINCIPAL,
        owned: [
          {
            securable_type: 'catalog',
            full_name: 'sales',
            securable_id: 'c1',
          },
        ],
      },
      isPending: false,
      error: null,
    });
    renderModal();

    expect(screen.queryByText(`Alice (${PRINCIPAL})`)).not.toBeInTheDocument();
  });
});
