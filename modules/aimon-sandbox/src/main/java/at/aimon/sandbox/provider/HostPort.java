package at.aimon.sandbox.provider;

import java.util.Objects;

/**
 * A host and a TCP port: one endpoint a sandbox must not reach ({@link ProviderCapabilities#controlPlaneEndpoints()}).
 */
public final class HostPort {

    private final String host;
    private final int port;

    private HostPort(String host, int port) {
        Objects.requireNonNull(host, "host must not be null");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be 1-65535, got " + port);
        }
        this.host = host;
        this.port = port;
    }

    /**
     * @param host
     *            a host name or address
     * @param port
     *            the TCP port
     * @return the endpoint
     */
    public static HostPort of(String host, int port) {
        return new HostPort(host, port);
    }

    /**
     * @param value
     *            {@code host:port}; an IPv6 address in brackets ({@code [::1]:80})
     * @return the endpoint
     */
    public static HostPort parse(String value) {
        Objects.requireNonNull(value, "value must not be null");
        final int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            throw new IllegalArgumentException("expected host:port, got '" + value + "'");
        }
        String host = value.substring(0, colon);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        try {
            return new HostPort(host, Integer.parseInt(value.substring(colon + 1)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected host:port, got '" + value + "'", e);
        }
    }

    /** @return the host */
    public String host() {
        return host;
    }

    /** @return the port */
    public int port() {
        return port;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HostPort that)) {
            return false;
        }
        return port == that.port && host.equals(that.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port);
    }

    @Override
    public String toString() {
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }
}
