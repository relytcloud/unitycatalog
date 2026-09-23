package io.unitycatalog.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import io.unitycatalog.server.base.auth.BaseAuthCRUDTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The deployment template ships the four OAuth client properties blank, which is how the hosted
 * login flow is switched off. Its endpoints must then refuse clearly rather than redirect to an
 * empty URL or redeem a code against nothing.
 */
public class AuthServiceHostedLoginDisabledTest extends BaseAuthCRUDTest {

  private static final String AUTH_PATH = "/api/1.0/unity-control/auth";

  private WebClient client;

  @BeforeEach
  @Override
  public void setUp() {
    super.setUp();
    client = WebClient.builder(serverConfig.getServerUrl()).build();
  }

  @Test
  public void loginIsRefusedWhenNotConfigured() {
    AggregatedHttpResponse response =
        client.execute(RequestHeaders.of(HttpMethod.GET, AUTH_PATH + "/login")).aggregate().join();

    assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.contentUtf8()).contains("Hosted login is not configured");
  }

  @Test
  public void callbackIsRefusedWhenNotConfigured() {
    AggregatedHttpResponse response =
        client
            .execute(RequestHeaders.of(HttpMethod.GET, AUTH_PATH + "/callback?code=x&state=y"))
            .aggregate()
            .join();

    assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  /** The token exchange endpoint is unaffected by the hosted flow being off. */
  @Test
  public void tokenExchangeStillWorks() {
    // Nothing to assert beyond reachability here; AuthServiceTest covers the exchange itself.
    AggregatedHttpResponse response =
        client
            .execute(RequestHeaders.of(HttpMethod.POST, AUTH_PATH + "/tokens"))
            .aggregate()
            .join();

    assertThat(response.status()).isNotEqualTo(HttpStatus.NOT_FOUND);
  }
}
