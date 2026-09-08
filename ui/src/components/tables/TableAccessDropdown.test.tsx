import { fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import TableAccessDropdown from './TableAccessDropdown';

jest.mock('../access/GrantAccessModal', () => ({
  GrantAccessModal: ({ open, target }: any) =>
    open ? <div data-testid="grant-modal">{target.fullName}</div> : null,
}));

function renderDropdown(props: {
  catalog?: string;
  schema?: string;
  table?: string;
}) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <TableAccessDropdown {...props} />
    </QueryClientProvider>,
  );
}

describe('TableAccessDropdown', () => {
  it('opens the grant modal for the row table without leaving the list', async () => {
    renderDropdown({ catalog: 'main', schema: 'sales', table: 'orders' });

    fireEvent.click(screen.getByLabelText('Access actions for orders'));
    fireEvent.click(await screen.findByText('Grant access'));

    expect(await screen.findByTestId('grant-modal')).toHaveTextContent(
      'main.sales.orders',
    );
  });

  /** A row without a full name has nothing to grant on. */
  it('renders nothing when the row is not addressable', () => {
    const { container } = renderDropdown({ catalog: 'main', schema: 'sales' });

    expect(container).toBeEmptyDOMElement();
  });

  it('does not trigger the row navigation when clicked', () => {
    const onRowClick = jest.fn();
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        {/* eslint-disable-next-line jsx-a11y/no-static-element-interactions, jsx-a11y/click-events-have-key-events -- stands in for the antd table row */}
        <div onClick={onRowClick}>
          <TableAccessDropdown catalog="main" schema="sales" table="orders" />
        </div>
      </QueryClientProvider>,
    );

    fireEvent.click(screen.getByLabelText('Access actions for orders'));

    expect(onRowClick).not.toHaveBeenCalled();
  });
});
