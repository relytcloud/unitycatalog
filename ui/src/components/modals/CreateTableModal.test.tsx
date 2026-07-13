import { fireEvent, screen, waitFor } from '@testing-library/react';
import { renderWithProviders } from '../../test-utils/render';
import { programClient, requestsTo } from '../../test-utils/mockClient';
import { clickModalOk, selectAntdOption } from '../../test-utils/antd';
import { CreateTableModal } from './CreateTableModal';

jest.mock('../../context/client', () => ({ CLIENT: { request: jest.fn() } }));

const LOCATIONS = {
  external_locations: [
    { name: 'demo-loc', url: 'oss://bkt/base/', credential_name: 'cred-a' },
  ],
};

describe('CreateTableModal', () => {
  it('creates an EXTERNAL table under the chosen external location', async () => {
    programClient([
      { method: 'get', url: '/external-locations', response: LOCATIONS },
      {
        method: 'post',
        url: '/tables',
        response: { name: 't1', catalog_name: 'cat', schema_name: 'sch' },
      },
    ]);
    renderWithProviders(
      <CreateTableModal
        open
        closeModal={jest.fn()}
        catalog="cat"
        schema="sch"
      />,
    );

    fireEvent.change(screen.getByLabelText('Name'), {
      target: { value: 't1' },
    });
    // Select index 0 = data source format (keep DELTA default); index 1 =
    // external location.
    await selectAntdOption(1, /demo-loc/);
    // Shows the resolved credential for the picked location.
    await screen.findByText(/Credential: cred-a/);
    fireEvent.change(
      screen.getByLabelText('Subpath under the location (optional)'),
      { target: { value: '/tables/t1/' } },
    );
    // The final storage location is previewed live.
    await screen.findByText('oss://bkt/base/tables/t1');
    fireEvent.change(screen.getByPlaceholderText('column name'), {
      target: { value: 'id' },
    });
    clickModalOk();

    await waitFor(() => expect(requestsTo('post', '/tables')).toHaveLength(1));
    const body = requestsTo('post', '/tables')[0].data;
    expect(body).toMatchObject({
      name: 't1',
      catalog_name: 'cat',
      schema_name: 'sch',
      table_type: 'EXTERNAL',
      data_source_format: 'DELTA',
      storage_location: 'oss://bkt/base/tables/t1',
    });
    expect(body.columns).toHaveLength(1);
    expect(body.columns[0]).toMatchObject({
      name: 'id',
      type_name: 'STRING',
      type_text: 'string',
      position: 0,
      nullable: true,
    });
    expect(JSON.parse(body.columns[0].type_json)).toMatchObject({
      name: 'id',
      type: 'string',
    });
  });

  it('requires an external location before submitting', async () => {
    programClient([
      { method: 'get', url: '/external-locations', response: LOCATIONS },
    ]);
    renderWithProviders(
      <CreateTableModal
        open
        closeModal={jest.fn()}
        catalog="cat"
        schema="sch"
      />,
    );
    fireEvent.change(screen.getByLabelText('Name'), {
      target: { value: 't1' },
    });
    fireEvent.change(screen.getByPlaceholderText('column name'), {
      target: { value: 'id' },
    });
    clickModalOk();
    await screen.findByText('External location is required');
    expect(requestsTo('post', '/tables')).toHaveLength(0);
  });
});
