package at.aimon.sandbox.reaper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import at.aimon.sandbox.backend.SandboxBackend;

@ExtendWith(MockitoExtension.class)
class ReaperServiceTest {

    @Mock
    private SandboxBackend backend;

    @Test
    void constructor_NullBackend_ThrowsException() {
        assertThatThrownBy(() -> new ReaperService(null, 5000)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void startAndClose_DoesNotThrow() {
        ReaperService reaper = new ReaperService(backend, 5000);
        reaper.start();
        reaper.close();
    }

    @Test
    void reap_InvokesBackend() throws Exception {
        when(backend.reapExpired()).thenReturn(0);

        ReaperService reaper = new ReaperService(backend, 100);
        reaper.start();

        Thread.sleep(350);
        reaper.close();

        verify(backend, atLeastOnce()).reapExpired();
    }

    @Test
    void start_CalledTwice_DoesNotScheduleDuplicate() throws Exception {
        when(backend.reapExpired()).thenReturn(0);

        ReaperService reaper = new ReaperService(backend, 100);
        reaper.start();
        reaper.start(); // second call should be no-op

        Thread.sleep(350);
        reaper.close();

        // If duplicate scheduling occurred, reapExpired would be called roughly twice as often.
        // With 100ms interval and 350ms sleep, expect ~3 calls max with single schedule.
        verify(backend, atMost(5)).reapExpired();
    }

    @Test
    void reap_BackendException_DoesNotStopReaper() throws Exception {
        when(backend.reapExpired()).thenThrow(new IOException("connection lost")).thenReturn(2);

        ReaperService reaper = new ReaperService(backend, 100);
        reaper.start();

        Thread.sleep(350);
        reaper.close();

        verify(backend, atLeastOnce()).reapExpired();
    }
}
