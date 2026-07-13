import { Flex, Tabs, Typography } from 'antd';
import { CloudServerOutlined } from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';
import CredentialsList from '../components/credentials/CredentialsList';
import ExternalLocationsList from '../components/externalLocations/ExternalLocationsList';

export default function ExternalData() {
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const activeKey = pathname.replace(/\/+$/, '').endsWith('/credentials')
    ? 'credentials'
    : 'external-locations';

  return (
    <Flex vertical gap="middle" style={{ flexGrow: 1 }}>
      <Typography.Title level={2}>
        <CloudServerOutlined /> External Data
      </Typography.Title>
      <Tabs
        activeKey={activeKey}
        onChange={(key) => navigate(`/external-data/${key}`)}
        items={[
          {
            key: 'external-locations',
            label: 'External Locations',
            children: <ExternalLocationsList />,
          },
          {
            key: 'credentials',
            label: 'Credentials',
            children: <CredentialsList />,
          },
        ]}
      />
    </Flex>
  );
}
