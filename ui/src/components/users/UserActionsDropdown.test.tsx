import { fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import UserActionsDropdown from './UserActionsDropdown';
import { useSetMetastoreAdmin, useSetScimUserActive } from '../../hooks/users';
import { useCurrentPrincipal, useIsMetastoreAdmin } from '../../hooks/authz';

jest.mock('../../hooks/users', () => ({
  ...jest.requireActual('../../hooks/users'),
  useSetScimUserActive: jest.fn(),
  useSetMetastoreAdmin: jest.fn(),
}));
jest.mock('../../hooks/authz');
// Both modals open in a portal and pull in their own data; the dropdown's job
// is only to offer the right entries.
jest.mock('./GrantToUserModal', () => () => null);
// Stands in for the confirmation dialog with just enough to click inside: what matters here is
// whether such a click escapes to the row, not what the real dialog renders.
jest.mock('./DeleteUserModal', () => {
  const react = jest.requireActual('react');
  return ({ open }: { open: boolean }) =>
    open
      ? react.createElement('input', { 'aria-label': 'type the principal' })
      : null;
});
jest.mock('../../utils/NotificationContext', () => ({
  useNotification: () => ({ setNotification: jest.fn() }),
}));

const mockUseSetScimUserActive = useSetScimUserActive as jest.Mock;
const mockUseSetMetastoreAdmin = useSetMetastoreAdmin as jest.Mock;
const mockUseIsMetastoreAdmin = useIsMetastoreAdmin as jest.Mock;
const mockUseCurrentPrincipal = useCurrentPrincipal as jest.Mock;

const mutate = jest.fn();
const setAdminMutate = jest.fn();

function openMenu({
  principal = 'alice@example.com',
  active = true,
  isAdmin = false,
}: { principal?: string; active?: boolean; isAdmin?: boolean } = {}) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <UserActionsDropdown
        principal={principal}
        userId="user-1"
        active={active}
        isAdmin={isAdmin}
        onShowAccessDetails={jest.fn()}
      />
    </QueryClientProvider>,
  );
  fireEvent.click(screen.getByLabelText(`Actions for ${principal}`));
}

beforeEach(() => {
  jest.clearAllMocks();
  mockUseSetScimUserActive.mockReturnValue({ mutate, isPending: false });
  mockUseSetMetastoreAdmin.mockReturnValue({
    mutate: setAdminMutate,
    isPending: false,
  });
  mockUseIsMetastoreAdmin.mockReturnValue({ data: true });
  mockUseCurrentPrincipal.mockReturnValue({ data: 'admin@example.com' });
});

describe('UserActionsDropdown', () => {
  it('offers deactivation, but not deletion, for an active user', async () => {
    openMenu({ active: true });

    expect(await screen.findByText('Deactivate')).toBeInTheDocument();
    expect(screen.queryByText('Reactivate')).not.toBeInTheDocument();
    // Deleting is only reachable once the account is already deactivated, so
    // the irreversible step sits behind a state the admin has seen.
    expect(screen.queryByText('Delete permanently')).not.toBeInTheDocument();
  });

  it('offers reactivation and permanent deletion for an inactive user', async () => {
    openMenu({ active: false });

    expect(await screen.findByText('Reactivate')).toBeInTheDocument();
    expect(screen.getByText('Delete permanently')).toBeInTheDocument();
    expect(screen.queryByText('Deactivate')).not.toBeInTheDocument();
  });

  it('deactivates through a SCIM patch rather than a delete', async () => {
    openMenu({ active: true });

    fireEvent.click(await screen.findByText('Deactivate'));

    expect(mutate).toHaveBeenCalledWith(
      { id: 'user-1', active: false },
      expect.anything(),
    );
  });

  it('offers to promote an ordinary active user', async () => {
    openMenu({ active: true, isAdmin: false });

    fireEvent.click(await screen.findByText('Make administrator'));

    expect(setAdminMutate).toHaveBeenCalledWith(
      { email: 'alice@example.com', admin: true },
      expect.anything(),
    );
  });

  it('offers to withdraw the privilege from an administrator', async () => {
    openMenu({ active: true, isAdmin: true });

    fireEvent.click(await screen.findByText('Withdraw administrator'));

    expect(setAdminMutate).toHaveBeenCalledWith(
      { email: 'alice@example.com', admin: false },
      expect.anything(),
    );
  });

  it('does not offer administrator status for a deactivated user', async () => {
    openMenu({ active: false });

    expect(await screen.findByText('Reactivate')).toBeInTheDocument();
    expect(screen.queryByText('Make administrator')).not.toBeInTheDocument();
    expect(
      screen.queryByText('Withdraw administrator'),
    ).not.toBeInTheDocument();
  });

  it('hides state changes from a caller who is not a metastore admin', async () => {
    mockUseIsMetastoreAdmin.mockReturnValue({ data: false });
    openMenu({ active: true });

    expect(await screen.findByText('Grant access')).toBeInTheDocument();
    expect(screen.queryByText('Deactivate')).not.toBeInTheDocument();
    expect(screen.queryByText('Delete permanently')).not.toBeInTheDocument();
  });

  it('will not let an admin deactivate their own account', async () => {
    mockUseCurrentPrincipal.mockReturnValue({ data: 'alice@example.com' });
    openMenu({ principal: 'alice@example.com', active: true });

    fireEvent.click(await screen.findByText('Deactivate'));

    expect(mutate).not.toHaveBeenCalled();
  });

  it('will not let an admin delete their own deactivated account', async () => {
    mockUseCurrentPrincipal.mockReturnValue({ data: 'alice@example.com' });
    openMenu({ principal: 'alice@example.com', active: false });

    const item = await screen.findByText('Delete permanently');
    // eslint-disable-next-line testing-library/no-node-access -- the disabled state lives on the antd menu item wrapping this label
    expect(item.closest('.ant-dropdown-menu-item-disabled')).not.toBeNull();
  });

  it('will not offer to delete the bootstrap administrator', async () => {
    openMenu({ principal: 'admin', active: false });

    const item = await screen.findByText('Delete permanently');
    // eslint-disable-next-line testing-library/no-node-access -- the disabled state lives on the antd menu item wrapping this label
    expect(item.closest('.ant-dropdown-menu-item-disabled')).not.toBeNull();
  });

  /**
   * The bootstrap administrator is the account the password sign-in accepts — the way back in when
   * the identity provider is unavailable. Deactivating it closes that door, and taking its
   * administrator status away leaves an account that signs in and administers nothing, which no
   * restart repairs. The server refuses both; the menu should not offer them either.
   */
  it('will not offer to deactivate the bootstrap administrator', async () => {
    openMenu({ principal: 'admin', active: true });

    const item = await screen.findByText('Deactivate');
    // eslint-disable-next-line testing-library/no-node-access -- the disabled state lives on the antd menu item wrapping this label
    expect(item.closest('.ant-dropdown-menu-item-disabled')).not.toBeNull();
  });

  it('will not offer to withdraw the bootstrap administrator’s status', async () => {
    openMenu({ principal: 'admin', active: true, isAdmin: true });

    const item = await screen.findByText('Withdraw administrator');
    // eslint-disable-next-line testing-library/no-node-access -- the disabled state lives on the antd menu item wrapping this label
    expect(item.closest('.ant-dropdown-menu-item-disabled')).not.toBeNull();
  });

  /**
   * The dropdown sits inside a table row whose own click opens the user drawer. Ant Design puts
   * the menu in a portal, but React events travel the React tree rather than the DOM, so without
   * this every action also opened the drawer — including Access details, which opens a panel of
   * its own and so opened two.
   */
  it('does not let a menu click reach the row underneath', async () => {
    const rowClick = jest.fn();
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions -- stands in for the table row, which antd makes clickable the same way
      <div onClick={rowClick}>
        <QueryClientProvider client={client}>
          <UserActionsDropdown
            principal="alice@example.com"
            userId="user-1"
            active={true}
            isAdmin={false}
            onShowAccessDetails={jest.fn()}
          />
        </QueryClientProvider>
      </div>,
    );

    // Opening the menu must not reach the row...
    fireEvent.click(screen.getByLabelText('Actions for alice@example.com'));
    expect(rowClick).not.toHaveBeenCalled();

    // ...nor must choosing an action, which still does its own job.
    fireEvent.click(await screen.findByText('Deactivate'));
    expect(mutate).toHaveBeenCalledWith(
      { id: 'user-1', active: false },
      expect.anything(),
    );
    expect(rowClick).not.toHaveBeenCalled();
  });

  /**
   * And the same for a dialog an action opened. Typing the principal into the delete confirmation
   * was opening the drawer behind the dialog on every click in the field — the modal is a portal,
   * but on the React tree it is still rendered from inside the row.
   */
  it('does not let a click inside a dialog reach the row underneath', async () => {
    const rowClick = jest.fn();
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions -- stands in for the table row, which antd makes clickable the same way
      <div onClick={rowClick}>
        <QueryClientProvider client={client}>
          <UserActionsDropdown
            principal="alice@example.com"
            userId="user-1"
            active={false}
            isAdmin={false}
            onShowAccessDetails={jest.fn()}
          />
        </QueryClientProvider>
      </div>,
    );

    fireEvent.click(screen.getByLabelText('Actions for alice@example.com'));
    fireEvent.click(await screen.findByText('Delete permanently'));

    fireEvent.click(screen.getByLabelText('type the principal'));

    expect(rowClick).not.toHaveBeenCalled();
  });
});
