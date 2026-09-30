package at.aimon.sandbox.opensandbox;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.sandbox.provider.CredentialScope;
import at.aimon.sandbox.provider.SandboxProviderException;

/**
 * The egress sidecar's credential vault (port 18080), reached through endpoint resolution with the
 * {@code OPENSANDBOX-EGRESS-AUTH} header the lifecycle server hands out (docs/design/opensandbox-spike.md §6; the
 * header
 * was confirmed on the Docker runtime in implementation step 4). Only the bindings a sandbox's profile names are sent:
 * a secret no profile asked for never reaches a sandbox's sidecar.
 */
final class VaultClient {

    private final ExecdClient sidecar;

    VaultClient(ExecdClient sidecar) {
        this.sidecar = sidecar;
    }

    /** @return the binding names the vault holds, or empty when there is no vault yet */
    Optional<TreeSet<String>> bindingNames() {
        final HttpResponse<byte[]> response = sidecar.send(ep -> ep.request("/credential-vault").GET(),
                "read credential vault");
        try {
            HttpErrors.check(response, "read credential vault");
        } catch (HttpErrors.NotFound e) {
            sidecar.requireSandbox();
            return Optional.empty();
        } catch (SandboxProviderException e) {
            throw classify(response.statusCode(), e);
        }
        final TreeSet<String> names = new TreeSet<>();
        for (JsonNode binding : sidecar.transport().read(response).path("bindings")) {
            names.add(binding.path("name").asText());
        }
        return Optional.of(names);
    }

    /** Creates the vault with the named bindings, their secrets read now. */
    void create(List<String> names, Map<String, CredentialDefinition> definitions) {
        final ObjectNode body = sidecar.transport().json.createObjectNode();
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
        final byte[] payload = sidecar.transport().write(body);
        final HttpResponse<byte[]> response = sidecar.send(ep -> ep.request("/credential-vault")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(payload)),
                "configure credential vault");
        try {
            HttpErrors.check(response, "configure credential vault");
        } catch (HttpErrors.NotFound e) {
            sidecar.requireSandbox();
            throw HttpErrors.failure(404, "configure credential vault", e.getMessage());
        } catch (SandboxProviderException e) {
            throw classify(response.statusCode(), e);
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
