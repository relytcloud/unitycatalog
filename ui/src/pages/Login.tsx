import { Flex, Layout, Typography } from 'antd';
import React from 'react';
import GoogleAuthButton from '../components/login/GoogleAuthButton';
import OktaAuthButton from '../components/login/OktaAuthButton';
import { useAuth } from '../context/auth-context';
import KeycloakAuthButton from '../components/login/KeycloakAuthButton';
import EntraAuthButton from '../components/login/EntraAuthButton';
import { useAuthProviders } from '../hooks/auth-providers';
import { useNavigate, useLocation } from 'react-router-dom';

export default function LoginPage() {
  const { loginWithToken } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const from = location.state?.from || '/';
  const googleEnabled = process.env.REACT_APP_GOOGLE_AUTH_ENABLED === 'true';
  const oktaEnabled = process.env.REACT_APP_OKTA_AUTH_ENABLED === 'true';
  const keycloakEnabled =
    process.env.REACT_APP_KEYCLOAK_AUTH_ENABLED === 'true';
  // Offered whenever the server hosts the flow; nothing to configure in the UI.
  const { data: providers } = useAuthProviders();
  const entraEnabled = providers?.hosted_login === true;

  const handleGoogleSignIn = async (idToken: string) => {
    await loginWithToken(idToken).then(() => navigate(from, { replace: true }));
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
            height: 276,
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
            <Typography.Title level={4}>
              Login to Unity Catalog
            </Typography.Title>
            {googleEnabled && (
              <GoogleAuthButton onGoogleSignIn={handleGoogleSignIn} />
            )}
            {oktaEnabled && (
              <OktaAuthButton
                onSuccess={(tokens: any) => console.log('tokens', tokens)}
                onError={(error: Error) => console.log('error', error)}
              />
            )}
            {keycloakEnabled && <KeycloakAuthButton />}
            {entraEnabled && <EntraAuthButton returnTo={from} />}
            {!googleEnabled &&
              !oktaEnabled &&
              !keycloakEnabled &&
              !entraEnabled && (
                <Flex vertical={true} align={'center'} gap={'small'}>
                  <Typography.Text strong>
                    No sign-in provider is configured on this server
                  </Typography.Text>
                  <Typography.Text
                    type="secondary"
                    style={{ textAlign: 'center' }}
                  >
                    Sign in with Microsoft appears here once the server has the
                    authorization URL, token URL, client id and client secret of
                    an identity provider. Whoever runs the server signs in with
                    a password or an access token instead.
                  </Typography.Text>
                </Flex>
              )}
          </Flex>
        </div>
      </Flex>
    </Layout>
  );
}
