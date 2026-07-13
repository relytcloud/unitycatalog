import { fireEvent, screen, waitFor } from '@testing-library/react';
import { renderWithProviders } from '../../test-utils/render';
import { clickModalOk } from '../../test-utils/antd';
import { programClient, requestsTo } from '../../test-utils/mockClient';
import { CreateUserModal } from './CreateUserModal';

jest.mock('../../context/client', () => ({ CLIENT: { request: jest.fn() } }));

describe('CreateUserModal', () => {
  it('creates a SCIM user with a primary email against the control API', async () => {
    programClient([
      {
        method: 'post',
        url: '/scim2/Users',
        response: { id: 'u1', displayName: 'Demo User' },
      },
    ]);
    const closeModal = jest.fn();
    renderWithProviders(<CreateUserModal open closeModal={closeModal} />);

    fireEvent.change(screen.getByLabelText('Display name'), {
      target: { value: 'Demo User' },
    });
    fireEvent.change(screen.getByLabelText('Email'), {
      target: { value: 'demo.user@example.com' },
    });
    clickModalOk();

    await waitFor(() =>
      expect(requestsTo('post', '/scim2/Users')).toHaveLength(1),
    );
    const request = requestsTo('post', '/scim2/Users')[0];
    // Payload contract: displayName + primary email.
    expect(request.data).toEqual({
      displayName: 'Demo User',
      emails: [{ primary: true, value: 'demo.user@example.com' }],
    });
    // Goes to the control-plane API, not the catalog API.
    expect(request.baseURL).toBe('/api/1.0/unity-control');
    await waitFor(() => expect(closeModal).toHaveBeenCalled());
  });

  it('rejects an invalid email before calling the API', async () => {
    programClient([]);
    renderWithProviders(<CreateUserModal open closeModal={jest.fn()} />);
    fireEvent.change(screen.getByLabelText('Display name'), {
      target: { value: 'X' },
    });
    fireEvent.change(screen.getByLabelText('Email'), {
      target: { value: 'not-an-email' },
    });
    clickModalOk();
    await screen.findByText(/must be a valid email/i);
    expect(requestsTo('post', '/scim2/Users')).toHaveLength(0);
  });
});
