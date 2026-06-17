package io.unitycatalog.server.service.credential.aliyun;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.unitycatalog.server.service.credential.CredentialContext;
import io.unitycatalog.server.utils.NormalizedURL;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.SneakyThrows;
import org.apache.iceberg.exceptions.NotAuthorizedException;

/**
 * Generates an Aliyun RAM policy that scopes an STS AssumeRole session down to the specific OSS
 * bucket(s) and path prefixes a request needs. This is the Aliyun counterpart of {@link
 * io.unitycatalog.server.service.credential.aws.AwsPolicyGenerator}.
 *
 * <p>The policy uses the Aliyun RAM policy language ({@code "Version": "1"}), OSS actions ({@code
 * oss:GetObject}, {@code oss:PutObject}, ...) and OSS resource ARNs ({@code
 * acs:oss:*:*:bucket/path/*}). Only used by the STS mode; the static AK/SK mode does not scope.
 */
public class AliyunPolicyGenerator {

  static final List<String> SELECT_ACTIONS = List.of("oss:GetObject");
  static final List<String> UPDATE_ACTIONS =
      List.of(
          "oss:GetObject",
          "oss:PutObject",
          "oss:DeleteObject",
          "oss:AbortMultipartUpload",
          "oss:ListParts");
  // Listing is granted on the bucket resource, constrained by an oss:Prefix condition.
  static final List<String> LIST_ACTIONS = List.of("oss:ListObjects", "oss:GetBucketInfo");

  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

  @SneakyThrows
  public static String generatePolicy(
      Set<CredentialContext.Privilege> privileges, List<NormalizedURL> locations) {
    List<String> objectActions;
    if (privileges.contains(CredentialContext.Privilege.UPDATE)) {
      objectActions = UPDATE_ACTIONS;
    } else if (privileges.contains(CredentialContext.Privilege.SELECT)) {
      objectActions = SELECT_ACTIONS;
    } else {
      throw new NotAuthorizedException(
          String.format(
              "Can't generate policy for unknown privileges '%s' for locations: '%s'",
              privileges, locations));
    }

    ObjectNode policyRoot = JSON_MAPPER.createObjectNode();
    policyRoot.put("Version", "1");
    ArrayNode statements = policyRoot.putArray("Statement");

    getBucketToPathsMap(locations)
        .forEach(
            (bucketName, paths) -> {
              ObjectNode objectStmt = statements.addObject();
              objectStmt.put("Effect", "Allow");
              ArrayNode actions = objectStmt.putArray("Action");
              objectActions.forEach(actions::add);
              ArrayNode objectResources = objectStmt.putArray("Resource");

              ObjectNode listStmt = statements.addObject();
              listStmt.put("Effect", "Allow");
              ArrayNode listActions = listStmt.putArray("Action");
              LIST_ACTIONS.forEach(listActions::add);
              listStmt.putArray("Resource").add(String.format("acs:oss:*:*:%s", bucketName));
              ArrayNode prefixes =
                  listStmt.putObject("Condition").putObject("StringLike").putArray("oss:Prefix");

              paths.forEach(
                  path -> {
                    // remove any preceding forward slashes
                    String sanitizedPath = path.replaceAll("^/+", "");
                    if (sanitizedPath.isEmpty()) {
                      prefixes.add("*");
                      objectResources.add(String.format("acs:oss:*:*:%s/*", bucketName));
                    } else {
                      prefixes.add(sanitizedPath + "/*");
                      objectResources.add(
                          String.format("acs:oss:*:*:%s/%s/*", bucketName, sanitizedPath));
                      objectResources.add(
                          String.format("acs:oss:*:*:%s/%s", bucketName, sanitizedPath));
                    }
                  });
            });

    return JSON_MAPPER.writeValueAsString(policyRoot);
  }

  private static Map<String, List<String>> getBucketToPathsMap(List<NormalizedURL> locations) {
    Map<String, List<String>> bucketToPaths = new LinkedHashMap<>();
    for (NormalizedURL location : locations) {
      URI uri = location.toUri();
      bucketToPaths
          .computeIfAbsent(uri.getHost(), k -> new java.util.LinkedList<>())
          .add(uri.getPath());
    }
    return bucketToPaths;
  }
}
