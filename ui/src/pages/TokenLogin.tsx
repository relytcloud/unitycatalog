import { Alert, Button, Flex, Form, Input, Layout, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../context/auth-context';

interface TokenLoginFormValues {
  token: string;
}

/**
 * The operator's way in with an access token this server issued -- the one in
 * etc/conf/token.txt. It replaces the proxy's blanket token injection, which
 * turned the application's own address into an unauthenticated entry point:
 * the token is now presented at an address of its own, and buys exactly the
 * identity it names.
 *
 * A token may also be put in the query string for a bookmarkable link. That is
 * a convenience with a cost -- the URL reaches browser history, proxy logs and
 * Referer headers -- so the page signs in and immediately drops the token from
 * the address bar, and the form is the way that leaves no trail.
 */
export default function TokenLoginPage() {
  const { loginWithAccessToken } = useAuth();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [submitting, setSubmitting] = useState(false);
  const [failed, setFailed] = useState(false);
  const attemptedFromUrl = useRef(false);

  const signIn = useCallback(
    async (token: string) => {
      setFailed(false);
      setSubmitting(true);
      try {
        await loginWithAccessToken(token);
        navigate('/', { replace: true });
      } catch {
        setFailed(true);
      } finally {
        setSubmitting(false);
      }
    },
    [loginWithAccessToken, navigate],
  );

  useEffect(() => {
    const fromUrl = searchParams.get('token');
    if (!fromUrl || attemptedFromUrl.current) return;
    attemptedFromUrl.current = true;
    // Out of the address bar before anything else happens to it.
    setSearchParams({}, { replace: true });
    signIn(fromUrl);
  }, [searchParams, setSearchParams, signIn]);

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
            <Typography.Title level={4}>Sign in with a token</Typography.Title>
            <Form<TokenLoginFormValues>
              layout="vertical"
              style={{ width: 320 }}
              onFinish={({ token }) => signIn(token.trim())}
            >
              <Form.Item
                name="token"
                label="Unity Catalog access token"
                rules={[{ required: true, message: 'A token is required' }]}
              >
                <Input.TextArea rows={4} autoComplete="off" />
              </Form.Item>
              {failed && (
                <Form.Item>
                  <Alert
                    type="error"
                    showIcon
                    message="Token is not valid for sign-in"
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
          </Flex>
        </div>
      </Flex>
    </Layout>
  );
}
