package io.unitycatalog.server.service.credential.aliyun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.unitycatalog.server.service.credential.CredentialContext;
import io.unitycatalog.server.utils.NormalizedURL;
import java.util.List;
import java.util.Set;
import org.apache.iceberg.exceptions.NotAuthorizedException;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link AliyunPolicyGenerator}. No Aliyun SDK calls, no network — exercises
 * the RAM policy scoping logic that backs the STS credential mode.
 */
public class AliyunPolicyGeneratorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode policyFor(
      Set<CredentialContext.Privilege> privileges, String... locations) throws Exception {
    List<NormalizedURL> urls = List.of(locations).stream().map(NormalizedURL::from).toList();
    return MAPPER.readTree(AliyunPolicyGenerator.generatePolicy(privileges, urls));
  }

  @Test
  public void selectScopesObjectsToPathAndListsBucketWithPrefix() throws Exception {
    JsonNode policy = policyFor(Set.of(CredentialContext.Privilege.SELECT), "oss://bkt/data/tbl");

    assertThat(policy.get("Version").asText()).isEqualTo("1");
    JsonNode statements = policy.get("Statement");
    assertThat(statements).hasSize(2);

    // Object statement: GetObject only, scoped to the path prefix and the prefix object itself.
    JsonNode objectStmt = statements.get(0);
    assertThat(actions(objectStmt)).containsExactly("oss:GetObject");
    assertThat(resources(objectStmt))
        .containsExactlyInAnyOrder("acs:oss:*:*:bkt/data/tbl/*", "acs:oss:*:*:bkt/data/tbl");

    // List statement: ListObjects/GetBucketInfo on the BUCKET, constrained by an oss:Prefix.
    JsonNode listStmt = statements.get(1);
    assertThat(actions(listStmt)).containsExactly("oss:ListObjects", "oss:GetBucketInfo");
    assertThat(resources(listStmt)).containsExactly("acs:oss:*:*:bkt");
    JsonNode prefixes = listStmt.get("Condition").get("StringLike").get("oss:Prefix");
    assertThat(prefixes.get(0).asText()).isEqualTo("data/tbl/*");
  }

  @Test
  public void updateGrantsWriteActions() throws Exception {
    JsonNode policy = policyFor(Set.of(CredentialContext.Privilege.UPDATE), "oss://bkt/data/tbl");
    JsonNode objectStmt = policy.get("Statement").get(0);
    assertThat(actions(objectStmt))
        .containsExactly(
            "oss:GetObject",
            "oss:PutObject",
            "oss:DeleteObject",
            "oss:AbortMultipartUpload",
            "oss:ListParts");
  }

  @Test
  public void bucketRootGrantsWholeBucket() throws Exception {
    JsonNode policy = policyFor(Set.of(CredentialContext.Privilege.SELECT), "oss://bkt");
    JsonNode objectStmt = policy.get("Statement").get(0);
    assertThat(resources(objectStmt)).containsExactly("acs:oss:*:*:bkt/*");
    JsonNode prefixes =
        policy.get("Statement").get(1).get("Condition").get("StringLike").get("oss:Prefix");
    assertThat(prefixes.get(0).asText()).isEqualTo("*");
  }

  @Test
  public void emptyPrivilegesThrows() {
    assertThatThrownBy(() -> policyFor(Set.of(), "oss://bkt/data"))
        .isInstanceOf(NotAuthorizedException.class);
  }

  private static List<String> actions(JsonNode statement) {
    return MAPPER.convertValue(statement.get("Action"), new TypeReference<>() {});
  }

  private static List<String> resources(JsonNode statement) {
    return MAPPER.convertValue(statement.get("Resource"), new TypeReference<>() {});
  }
}
