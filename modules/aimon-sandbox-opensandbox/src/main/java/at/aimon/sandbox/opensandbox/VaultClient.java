package at.aimon.sandbox.opensandbox;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.sandbox.provider.CredentialScope;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.SandboxNotFoundException;
import at.aimon.sandbox.provider.SandboxProviderException;

/**
 * A sandbox's credential vault, through the lifecycle server's {@code /sandboxes/{id}/credential-vault} (OpenSandbox
 * {@code release-1.2.0}, upstream #2263). On the Docker and Kubernetes runtimes the server proxies the egress
 * sidecar's own {@code /credential-vault} (port 18080) with the sidecar's auth token, so the semantics are the
 * sidecar's (docs/design/opensandbox-spike.md §6), but the provider needs neither the sidecar's address nor its token
 * — it works without {@code use-server-proxy} as well. Only the bindings a sandbox's profile names are sent: a secret
 * no profile asked for never reaches a sandbox's sidecar.
 *
 * <p>
 * A 404 is the sandbox gone, the vault absent (the sidecar's plain-text answer), or — from a server older than 1.2.0 —
 * the route itself missing (FastAPI's {@code {"detail":"Not Found"}}). The first is told apart by asking for the
 * sandbox, the last by its body, and reported as permanent: no retry gives an old server the route.
 */
final class VaultClient {

    private static final String PATH = "credential-vault";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final LifecycleClient lifecycle;
    private final Transport transport;
    private final ProviderSandboxRef ref;

    VaultClient(LifecycleClient lifecycle, Transport transport, ProviderSandboxRef ref) {
        this.lifecycle = lifecycle;
        this.transport = transport;
        this.ref = ref;
    }

    /** @return the binding names the vault holds, or empty when there is no vault yet */
    Optional<TreeSet<String>> bindingNames() {
        final HttpResponse<byte[]> response = lifecycle.sandboxCall(ref.sandboxId(), PATH, "GET", null,
                "read credential vault");
        try {
            HttpErrors.check(response, "read credential vault");
        } catch (HttpErrors.NotFound e) {
            requireSandbox(response);
            return Optional.empty();
        } catch (SandboxProviderException e) {
            throw classify(response.statusCode(), e);
        }
        final TreeSet<String> names = new TreeSet<>();
        for (JsonNode binding : transport.read(response).path("bindings")) {
            names.add(binding.path("name").asText());
        }
        return Optional.of(names);
    }

    /** Creates the vault with the named bindings, their secrets read now. */
    void create(List<String> names, Map<String, CredentialDefinition> definitions) {
        final ObjectNode body = transport.json.createObjectNode();
        final ArrayNode credentials = body.putArray("credentials");
        final ArrayNode bindings = body.putArray("bindings");
        for (String name : names) {
            final CredentialDefinition definition = definitions.get(name);
            if (definition == null) {
                throw new SandboxProviderException("credential binding '" + name + "' is not configured",
                        SandboxProviderException.Kind.PERMANENT, null);
            }
            credentials.addObject().put("name", name).putObject("source").put("type", "inline").put("value",
                    definition.secret().get());
            final ObjectNode binding = bindings.addObject().put("name", name);
            final CredentialScope scope = definition.scope();
            final ObjectNode match = binding.putObject("match");
            scope.schemes().forEach(match.putArray("schemes")::add);
            scope.hosts().forEach(match.putArray("hosts")::add);
            scope.methods().forEach(match.putArray("methods")::add);
            scope.paths().forEach(match.putArray("paths")::add);
            final ObjectNode auth = binding.putObject("auth").put("type", definition.auth().type()).put("credential",
                    name);
            if (definition.auth().headerName() != null) {
                auth.put("name", definition.auth().headerName());
            }
        }
        final HttpResponse<byte[]> response = lifecycle.sandboxCall(ref.sandboxId(), PATH, "POST",
                transport.write(body), "configure credential vault");
        try {
            HttpErrors.check(response, "configure credential vault");
        } catch (HttpErrors.NotFound e) {
            requireSandbox(response);
            throw HttpErrors.failure(404, "configure credential vault", e.getMessage());
        } catch (SandboxProviderException e) {
            throw classify(response.statusCode(), e);
        }
    }

    /** Removes the vault; an absent vault is already removed. */
    void delete() {
        final HttpResponse<byte[]> response = lifecycle.sandboxCall(ref.sandboxId(), PATH, "DELETE", null,
                "delete credential vault");
        try {
            HttpErrors.check(response, "delete credential vault");
        } catch (HttpErrors.NotFound e) {
            requireSandbox(response);
        } catch (SandboxProviderException e) {
            throw classify(response.statusCode(), e);
        }
    }

    /**
     * After a 404: the sandbox must still exist, and the server must have the route.
     *
     * @throws SandboxNotFoundException
     *             when the sandbox is gone
     * @throws SandboxProviderException
     *             (permanent) when the server has no credential-vault route
     */
    private void requireSandbox(HttpResponse<byte[]> response) {
        if (lifecycle.get(ref.sandboxId()).isEmpty()) {
            throw new SandboxNotFoundException(ref);
        }
        if (routeMissing(response.body())) {
            throw new SandboxProviderException("the OpenSandbox server has no credential-vault API: it is older than "
                    + "1.2.0, which the provider requires", SandboxProviderException.Kind.PERMANENT, null);
        }
    }

    /** @return whether a 404 body is FastAPI's answer for a route it does not have */
    static boolean routeMissing(byte[] body) {
        try {
            final JsonNode json = JSON.readTree(body);
            return json != null && json.size() == 1 && "Not Found".equals(json.path("detail").asText(null));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** 412 is the sidecar's "not in dns+nft, or no MITM": the server's configuration, not a passing fault. */
    private static SandboxProviderException classify(int status, SandboxProviderException e) {
        if (status == 412) {
            return new SandboxProviderException(
                    e.getMessage() + " (the egress sidecar is not set up for the "
                            + "credential vault: dns+nft and the credential proxy are required)",
                    SandboxProviderException.Kind.PERMANENT, e);
        }
        return e;
    }
}
