import { fireEvent, screen, waitFor } from '@testing-library/react';
import { renderWithProviders } from '../../test-utils/render';
import { programClient, requestsTo } from '../../test-utils/mockClient';
import { clickModalOk, selectAntdOption } from '../../test-utils/antd';
import { CreateExternalLocationModal } from './CreateExternalLocationModal';

jest.mock('../../context/client', () => ({ CLIENT: { request: jest.fn() } }));

describe('CreateExternalLocationModal', () => {
  it('creates a location bound to an existing credential', async () => {
    programClient([
      {
        method: 'get',
        url: '/credentials',
        response: { credentials: [{ name: 'cred-a' }, { name: 'cred-b' }] },
      },
      {
        method: 'post',
        url: '/external-locations',
        response: { name: 'loc1' },
      },
    ]);
    renderWithProviders(
      <CreateExternalLocationModal open closeModal={jest.fn()} />,
    );

    fireEvent.change(screen.getByLabelText('Name'), {
      target: { value: 'loc1' },
    });
    fireEvent.change(screen.getByLabelText('URL'), {
      target: { value: 'oss://bkt/data' },
    });
    await selectAntdOption(0, 'cred-a');
    clickModalOk();

    await waitFor(() =>
      expect(requestsTo('post', '/external-locations')).toHaveLength(1),
    );
    expect(requestsTo('post', '/external-locations')[0].data).toMatchObject({
      name: 'loc1',
      url: 'oss://bkt/data',
      credential_name: 'cred-a',
    });
  });

  it('requires name, url and credential', async () => {
    programClient([
      {
        method: 'get',
        url: '/credentials',
        response: { credentials: [] },
      },
    ]);
    renderWithProviders(
      <CreateExternalLocationModal open closeModal={jest.fn()} />,
    );
    clickModalOk();
    await screen.findByText('Name is required');
    await screen.findByText('URL is required');
    await screen.findByText('Credential is required');
    expect(requestsTo('post', '/external-locations')).toHaveLength(0);
  });
});
