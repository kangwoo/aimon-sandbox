package at.aimon.sandbox;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import at.aimon.sandbox.provider.CreateSpec;
import at.aimon.sandbox.provider.ProviderCapabilities;
import at.aimon.sandbox.provider.ProviderSandbox;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.SandboxConnection;
import at.aimon.sandbox.provider.SandboxProvider;
import at.aimon.sandbox.provider.SharedVolumes;

/** A provider that forwards everything; tests override what they change. */
public class DelegatingProvider implements SandboxProvider {

    protected final SandboxProvider delegate;

    public DelegatingProvider(SandboxProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return delegate.capabilities();
    }

    @Override
    public ProviderSandboxRef create(CreateSpec spec) {
        return delegate.create(spec);
    }

    @Override
    public Optional<ProviderSandbox> status(ProviderSandboxRef ref) {
        return delegate.status(ref);
    }

    @Override
    public void pause(ProviderSandboxRef ref) {
        delegate.pause(ref);
    }

    @Override
    public void resume(ProviderSandboxRef ref) {
        delegate.resume(ref);
    }

    @Override
    public void extendExpiry(ProviderSandboxRef ref, Instant until) {
        delegate.extendExpiry(ref, until);
    }

    @Override
    public void destroy(ProviderSandboxRef ref) {
        delegate.destroy(ref);
    }

    @Override
    public List<ProviderSandbox> list(Map<String, String> labels) {
        return delegate.list(labels);
    }

    @Override
    public Optional<SharedVolumes> sharedVolumes() {
        return delegate.sharedVolumes();
    }

    @Override
    public SandboxConnection connect(ProviderSandboxRef ref) {
        return delegate.connect(ref);
    }

    @Override
    public void close() {
        // borrowed
    }
}
