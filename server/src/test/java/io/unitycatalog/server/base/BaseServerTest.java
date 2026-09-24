package io.unitycatalog.server.base;

import io.unitycatalog.server.UnityCatalogServer;
import io.unitycatalog.server.persist.utils.HibernateConfigurator;
import io.unitycatalog.server.service.credential.CloudCredentialVendor;
import io.unitycatalog.server.utils.ServerProperties;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import lombok.SneakyThrows;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

public abstract class BaseServerTest {

  public static final ServerConfig serverConfig = new ServerConfig("http://localhost", "");
  protected UnityCatalogServer unityCatalogServer;
  protected Properties serverProperties;
  protected HibernateConfigurator hibernateConfigurator;
  protected CloudCredentialVendor cloudCredentialVendor;

  // All test data should be written under this directory. It will be cleaned up.
  @TempDir protected Path testDirectoryRoot;
  // The storage root URL for managed tables to be set in server properties.
  protected String tableStorageRoot;

  /**
   * This function should be overriden if the test wants to start UC server to take emulated cloud
   * path as managed storage. The emulated cloud FS is provided by subclasses of
   * CredentialTestFileSystem.
   */
  protected String managedStorageCloudScheme() {
    // By default, just use local FS for managed storage.
    return "file";
  }

  /** Returns string of the emulated cloud URL (or just the absolute local path) for a local path */
  protected String getManagedStorageCloudPath(Path localPath) {
    String localPathString;
    localPathString = localPath.toAbsolutePath().normalize().toString();
    String scheme = managedStorageCloudScheme();
    if (scheme.equals("file")) {
      return "file://" + localPathString;
    } else {
      return scheme + "://test-bucket0" + localPathString;
    }
  }

  protected void setUpProperties() {
    serverProperties = new Properties();
    serverProperties.setProperty(Property.SERVER_ENV.getKey(), "test");
    // Enable managed table creation for tests
    serverProperties.setProperty(Property.MANAGED_TABLE_ENABLED.getKey(), "true");
    tableStorageRoot = getManagedStorageCloudPath(testDirectoryRoot);
    serverProperties.setProperty(Property.TABLE_STORAGE_ROOT.getKey(), tableStorageRoot);
  }

  protected void setUpCredentialOperations(ServerProperties serverProperties) {}

  @SneakyThrows
  @BeforeEach
  public void setUp() {
    if (serverConfig == null) {
      throw new IllegalArgumentException("Server config is required");
    }
    if (serverConfig.getServerUrl() == null) {
      throw new IllegalArgumentException("Server URL is required");
    }
    if (serverConfig.getAuthToken() == null) {
      throw new IllegalArgumentException("Auth token is required");
    }
    if (serverConfig.getServerUrl().contains("localhost")) {
      System.out.println("Running tests on localhost..");
      // start the server on a random port
      int port = findAvailablePort();
      Files.createDirectories(testDirectoryRoot);

      setUpProperties();
      ServerProperties initServerProperties = new ServerProperties(serverProperties);
      setUpCredentialOperations(initServerProperties);
      hibernateConfigurator = new HibernateConfigurator(initServerProperties);
      startOnAFreePort(port, initServerProperties);
    }
  }

  /**
   * Starts the server, retrying on a fresh port if the chosen one was taken in the meantime.
   *
   * <p>Picking a port and binding it are separate steps: {@link #findAvailablePort()} closes its
   * socket before returning, and the server only binds after Hibernate and the credential vendor
   * are built. On a loaded CI runner — several test JVMs at once — that window is wide enough for
   * something else to take the port, and the server needs two of them, since it also listens on
   * {@code port + 1}. The failure surfaced as {@code bind(..) failed: Address already in use}
   * thrown from setUp, which fails every test in the class for a reason that has nothing to do with
   * them.
   */
  private void startOnAFreePort(int firstAttemptPort, ServerProperties properties)
      throws IOException {
    int port = firstAttemptPort;
    for (int attempt = 1; ; attempt++) {
      UnityCatalogServer server =
          UnityCatalogServer.builder()
              .port(port)
              .serverProperties(properties)
              .credentialOperations(cloudCredentialVendor)
              .build();
      try {
        server.start();
        unityCatalogServer = server;
        serverConfig.setServerUrl("http://localhost:" + port);
        return;
      } catch (RuntimeException e) {
        // Whatever did bind has to be released before trying again: a half-started server left
        // listening would still answer requests aimed at the one this test ends up using.
        try {
          server.stop();
        } catch (RuntimeException ignored) {
          // Nothing started, or it is already down; the original failure is the one that matters.
        }
        if (attempt == START_ATTEMPTS || !isAddressInUse(e)) {
          throw e;
        }
        System.out.println("Port " + port + " was taken before the server bound it; retrying");
        port = findAvailablePort();
      }
    }
  }

  /** How many times to re-pick a port before giving up; the race is rare, so a few is plenty. */
  private static final int START_ATTEMPTS = 5;

  /** Whether the failure is the port race rather than something worth reporting. */
  private static boolean isAddressInUse(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof BindException) {
        return true;
      }
      String message = cause.getMessage();
      if (message != null && message.contains("Address already in use")) {
        return true;
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return false;
  }

  /**
   * Finds an available port for the UC server. Both this port and the next one must be free: the
   * given port serves the URL transcoder and {@code port + 1} the API itself.
   */
  private int findAvailablePort() throws IOException {
    for (int attempt = 1; ; attempt++) {
      int candidate;
      try (ServerSocket socket = new ServerSocket(0)) {
        candidate = socket.getLocalPort();
      }
      try (ServerSocket ignored = new ServerSocket(candidate + 1)) {
        return candidate;
      } catch (IOException neighbourTaken) {
        if (attempt == START_ATTEMPTS) {
          return candidate; // Let the start attempt report it.
        }
      }
    }
  }

  @AfterEach
  public void tearDown() {
    if (unityCatalogServer != null) {

      // TODO: Figure out a better way to clear the database
      SessionFactory sessionFactory = hibernateConfigurator.getSessionFactory();
      Session session = sessionFactory.openSession();
      Transaction tx = session.beginTransaction();
      session.createMutationQuery("delete from FunctionParameterInfoDAO").executeUpdate();
      session.createMutationQuery("delete from FunctionInfoDAO").executeUpdate();
      session.createMutationQuery("delete from VolumeInfoDAO").executeUpdate();
      session.createMutationQuery("delete from ColumnInfoDAO").executeUpdate();
      session.createMutationQuery("delete from TableInfoDAO").executeUpdate();
      session.createMutationQuery("delete from StagingTableDAO").executeUpdate();
      session.createMutationQuery("delete from SchemaInfoDAO").executeUpdate();
      session.createMutationQuery("delete from CatalogInfoDAO").executeUpdate();
      session.createMutationQuery("delete from UserDAO").executeUpdate();
      session.createMutationQuery("delete from ExternalLocationDAO").executeUpdate();
      session.createMutationQuery("delete from CredentialDAO").executeUpdate();
      tx.commit();
      session.close();

      unityCatalogServer.stop();
    }
  }
}
