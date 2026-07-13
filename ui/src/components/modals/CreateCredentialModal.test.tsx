import { fireEvent, screen, waitFor } from '@testing-library/react';
import { renderWithProviders } from '../../test-utils/render';
import { clickModalOk } from '../../test-utils/antd';
import { programClient, requestsTo } from '../../test-utils/mockClient';
import { CreateCredentialModal } from './CreateCredentialModal';

jest.mock('../../context/client', () => ({ CLIENT: { request: jest.fn() } }));

describe('CreateCredentialModal', () => {
  it('creates an Aliyun STS credential (role_arn only)', async () => {
    programClient([
      { method: 'post', url: '/credentials', response: { name: 'c1' } },
    ]);
    renderWithProviders(<CreateCredentialModal open closeModal={jest.fn()} />);

    fireEvent.change(screen.getByLabelText('Name'), {
      target: { value: 'c1' },
    });
    fireEvent.change(screen.getByLabelText('Role ARN'), {
      target: { value: 'acs:ram::1:role/demo' },
    });
    clickModalOk();

    await waitFor(() =>
      expect(requestsTo('post', '/credentials')).toHaveLength(1),
    );
    expect(requestsTo('post', '/credentials')[0].data).toMatchObject({
      name: 'c1',
      purpose: 'STORAGE',
      aliyun_ram_role: { role_arn: 'acs:ram::1:role/demo' },
    });
  });

  it('switches to static AK/SK fields and sends them (never a role_arn)', async () => {
    programClient([
      { method: 'post', url: '/credentials', response: { name: 'c2' } },
    ]);
    renderWithProviders(<CreateCredentialModal open closeModal={jest.fn()} />);

    fireEvent.click(screen.getByLabelText('Aliyun static AK/SK'));
    // Role ARN input disappears, AK/SK inputs appear.
    expect(screen.queryByLabelText('Role ARN')).toBeNull();
    fireEvent.change(screen.getByLabelText('Name'), {
      target: { value: 'c2' },
    });
    fireEvent.change(screen.getByLabelText('Access key ID'), {
      target: { value: 'LTAI5tXXXX' },
    });
    fireEvent.change(screen.getByLabelText('Access key secret'), {
      target: { value: 's3cret' },
    });
    clickModalOk();

    await waitFor(() =>
      expect(requestsTo('post', '/credentials')).toHaveLength(1),
    );
    const body = requestsTo('post', '/credentials')[0].data;
    expect(body).toMatchObject({
      name: 'c2',
      purpose: 'STORAGE',
      aliyun_ram_role: {
        access_key_id: 'LTAI5tXXXX',
        access_key_secret: 's3cret',
      },
    });
    expect(body.aliyun_ram_role.role_arn).toBeUndefined();
    expect(body.aws_iam_role).toBeUndefined();
  });

  it('creates an AWS IAM role credential', async () => {
    programClient([
      { method: 'post', url: '/credentials', response: { name: 'c3' } },
    ]);
    renderWithProviders(<CreateCredentialModal open closeModal={jest.fn()} />);

    fireEvent.click(screen.getByLabelText('AWS IAM role'));
    fireEvent.change(screen.getByLabelText('Name'), {
      target: { value: 'c3' },
    });
    fireEvent.change(screen.getByLabelText('Role ARN'), {
      target: { value: 'arn:aws:iam::1:role/demo' },
    });
    clickModalOk();

    await waitFor(() =>
      expect(requestsTo('post', '/credentials')).toHaveLength(1),
    );
    expect(requestsTo('post', '/credentials')[0].data).toMatchObject({
      name: 'c3',
      aws_iam_role: { role_arn: 'arn:aws:iam::1:role/demo' },
    });
  });

  it('surfaces a server-side permission error', async () => {
    programClient([
      {
        method: 'post',
        url: '/credentials',
        response: { message: 'Access denied: CREATE_STORAGE_CREDENTIAL' },
        status: 403,
      },
    ]);
    renderWithProviders(<CreateCredentialModal open closeModal={jest.fn()} />);
    fireEvent.change(screen.getByLabelText('Name'), {
      target: { value: 'c4' },
    });
    fireEvent.change(screen.getByLabelText('Role ARN'), {
      target: { value: 'acs:ram::1:role/demo' },
    });
    clickModalOk();
    await screen.findByText(/Access denied: CREATE_STORAGE_CREDENTIAL/);
  });
});
