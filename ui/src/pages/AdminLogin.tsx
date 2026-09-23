import { Alert, Button, Flex, Form, Input, Layout, Typography } from 'antd';
import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/auth-context';
import { useAuthProviders } from '../hooks/auth-providers';

interface AdminLoginFormValues {
  username: string;
  password: string;
}

/**
 * The administrator's entry point, deliberately not linked from the login
 * page: it exists for whoever runs the server, not for the people signing in
 * with their Microsoft account. The password lives in server.properties and is
 * checked server-side; a successful sign-in ends in the same UC_TOKEN cookie a
 * Microsoft sign-in does, so nothing downstream distinguishes the two.
 */
export default function AdminLoginPage() {
  const { loginWithPassword } = useAuth();
  const { data: providers } = useAuthProviders();
  const navigate = useNavigate();
  const [submitting, setSubmitting] = useState(false);
  const [failed, setFailed] = useState(false);

  const configured = providers?.admin_login === true;

  const onFinish = async ({ username, password }: AdminLoginFormValues) => {
    setFailed(false);
    setSubmitting(true);
    try {
      await loginWithPassword(username, password);
      navigate('/', { replace: true });
    } catch {
      setFailed(true);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Layout
      hasSider={false}
      style={{
        height: '100vh',
        width: '100vw',
        background: 'linear-gradient(#131D35,#252342,#1E2E3A)',
      }}
    >
      <Flex
        vertical={true}
        align={'center'}
        justify={'center'}
        gap={'middle'}
        style={{ height: '100%', width: '100%' }}
      >
        <div>
          <img
            src="/uc-logo-horiz-reverse.svg"
            height={32}
            alt="uc-logo-horizontal"
          />
        </div>
        <div
          style={{
            width: 400,
            backgroundColor: '#F6F7F9',
            borderRadius: '16px',
          }}
        >
          <Flex
            vertical={true}
            align={'center'}
            justify={'center'}
            gap={'middle'}
            style={{ padding: 24 }}
          >
            <Typography.Title level={4}>Administrator sign-in</Typography.Title>
            {providers && !configured ? (
              <Alert
                type="warning"
                showIcon
                message="Administrator sign-in is not configured on this server"
                description="Set server.admin-password in server.properties to enable it."
              />
            ) : (
              <Form<AdminLoginFormValues>
                layout="vertical"
                style={{ width: 300 }}
                initialValues={{ username: 'admin' }}
                onFinish={onFinish}
                disabled={!configured}
              >
                <Form.Item
                  name="username"
                  label="Username"
                  rules={[{ required: true, message: 'Username is required' }]}
                >
                  <Input prefix={<UserOutlined />} autoComplete="username" />
                </Form.Item>
                <Form.Item
                  name="password"
                  label="Password"
                  rules={[{ required: true, message: 'Password is required' }]}
                >
                  <Input.Password
                    prefix={<LockOutlined />}
                    autoComplete="current-password"
                  />
                </Form.Item>
                {failed && (
                  <Form.Item>
                    <Alert
                      type="error"
                      showIcon
                      message="Wrong username or password"
                    />
                  </Form.Item>
                )}
                <Form.Item style={{ marginBottom: 0 }}>
                  <Button
                    type="primary"
                    htmlType="submit"
                    block
                    loading={submitting}
                  >
                    Sign in
                  </Button>
                </Form.Item>
              </Form>
            )}
          </Flex>
        </div>
      </Flex>
    </Layout>
  );
}
