import { screen, fireEvent, within } from '@testing-library/react';
import { renderWithProviders } from '../test-utils/render';
import UsersList from './UsersList';
import { useListScimUsers, useMetastoreAdmins } from '../hooks/users';
import { useAuthorized } from '../hooks/authz';

jest.mock('../context/client', () => ({ CLIENT: { request: jest.fn() } }));
jest.mock('../hooks/users');
jest.mock('../hooks/authz');
jest.mock('../components/users/UserActionsDropdown', () => () => null);
jest.mock('../components/modals/CreateUserModal', () => ({
  CreateUserModal: () => null,
}));

const mockUseListScimUsers = useListScimUsers as jest.Mock;
const mockUseMetastoreAdmins = useMetastoreAdmins as jest.Mock;
const mockUseAuthorized = useAuthorized as jest.Mock;

const ACTIVE_ONE = {
  id: 'u1',
  displayName: 'Ada Active',
  active: true,
  emails: [{ primary: true, value: 'ada@example.com' }],
};
const ACTIVE_TWO = {
  id: 'u2',
  displayName: 'Ben Active',
  active: true,
  emails: [{ primary: true, value: 'ben@example.com' }],
};
const INACTIVE_ONE = {
  id: 'u3',
  displayName: 'Cleo Inactive',
  active: false,
  emails: [{ primary: true, value: 'cleo@example.com' }],
};

function renderList(users: unknown[]) {
  mockUseListScimUsers.mockReturnValue({
    data: { Resources: users },
    isLoading: false,
  });
  mockUseMetastoreAdmins.mockReturnValue({ data: new Set<string>() });
  mockUseAuthorized.mockReturnValue({ allowed: true, ready: true });
  renderWithProviders(<UsersList />);
}

describe('UsersList status filter', () => {
  beforeEach(() => jest.clearAllMocks());

  /**
   * Deactivated accounts keep their grants and their email, so they stay in the list forever.
   * Showing them alongside everyone else would make the list grow without bound and bury the
   * people who can actually sign in, which is why active is the default view rather than "all".
   */
  it('shows only active users by default', () => {
    renderList([ACTIVE_ONE, ACTIVE_TWO, INACTIVE_ONE]);

    expect(screen.getByText('Ada Active')).toBeInTheDocument();
    expect(screen.getByText('Ben Active')).toBeInTheDocument();
    expect(screen.queryByText('Cleo Inactive')).not.toBeInTheDocument();
  });

  it('shows the deactivated ones when asked, and only those', () => {
    renderList([ACTIVE_ONE, ACTIVE_TWO, INACTIVE_ONE]);

    fireEvent.click(screen.getByText(/^Inactive/));

    expect(screen.getByText('Cleo Inactive')).toBeInTheDocument();
    expect(screen.queryByText('Ada Active')).not.toBeInTheDocument();
    expect(screen.queryByText('Ben Active')).not.toBeInTheDocument();
  });

  /**
   * The count sits on the control rather than in the table, because the whole point is to tell an
   * administrator that deactivated accounts exist while they are looking at a list without them.
   */
  it('counts the deactivated accounts on the control', () => {
    renderList([ACTIVE_ONE, INACTIVE_ONE]);

    expect(screen.getByText('Inactive (1)')).toBeInTheDocument();
  });

  it('says just Inactive when there are none', () => {
    renderList([ACTIVE_ONE, ACTIVE_TWO]);

    expect(screen.getByText('Inactive')).toBeInTheDocument();
    expect(screen.queryByText(/Inactive \(/)).not.toBeInTheDocument();
  });

  it('marks each row with its state', () => {
    renderList([ACTIVE_ONE, INACTIVE_ONE]);

    // eslint-disable-next-line testing-library/no-node-access -- the state tag is a sibling cell, reachable only through the row
    const activeRow = screen.getByText('Ada Active').closest('tr');
    expect(
      within(activeRow as HTMLElement).getByText('Active'),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByText(/^Inactive/));
    // eslint-disable-next-line testing-library/no-node-access -- same reason
    const inactiveRow = screen.getByText('Cleo Inactive').closest('tr');
    expect(
      within(inactiveRow as HTMLElement).getByText('Inactive'),
    ).toBeInTheDocument();
  });
});
