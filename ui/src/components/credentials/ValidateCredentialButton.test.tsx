import { fireEvent, screen, waitFor } from '@testing-library/react';
import { renderWithProviders } from '../../test-utils/render';
import { programClient, requestsTo } from '../../test-utils/mockClient';
import ValidateCredentialButton from './ValidateCredentialButton';

jest.mock('../../context/client', () => ({ CLIENT: { request: jest.fn() } }));

const CREDENTIAL = {
  name: 'demo-sts',
  aliyun_ram_role: { role_arn: 'acs:ram::1:role/r' },
};
const LOCATION = {
  name: 'loc1',
  url: 'oss://bkt/path',
  credential_name: 'demo-sts',
};

describe('ValidateCredentialButton', () => {
  it('is disabled when the credential has no bound external location', () => {
    programClient([]);
    renderWithProviders(
      <ValidateCredentialButton
        credential={CREDENTIAL}
        externalLocations={[]}
      />,
    );
    expect(screen.getByRole('button', { name: /validate/i })).toBeDisabled();
  });

  it('vends PATH_READ credentials for the bound location url', async () => {
    programClient([
      {
        method: 'post',
        url: '/temporary-path-credentials',
        response: { expiration_time: 123 },
      },
    ]);
    renderWithProviders(
      <ValidateCredentialButton
        credential={CREDENTIAL}
        externalLocations={[LOCATION]}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: /validate/i }));
    await waitFor(() =>
      expect(requestsTo('post', '/temporary-path-credentials')).toHaveLength(1),
    );
    expect(requestsTo('post', '/temporary-path-credentials')[0].data).toEqual({
      url: 'oss://bkt/path',
      operation: 'PATH_READ',
    });
    // Success notification names the probed path and the credential.
    await screen.findByText(
      /Credential vending succeeded for oss:\/\/bkt\/path using demo-sts/,
    );
  });

  it('surfaces the server error message on failure', async () => {
    programClient([
      {
        method: 'post',
        url: '/temporary-path-credentials',
        response: { message: 'STS AssumeRole denied for role' },
        status: 403,
      },
    ]);
    renderWithProviders(
      <ValidateCredentialButton
        credential={CREDENTIAL}
        externalLocations={[LOCATION]}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: /validate/i }));
    await screen.findByText(/STS AssumeRole denied for role/);
  });
});
