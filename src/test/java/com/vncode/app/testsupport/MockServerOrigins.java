package com.vncode.app.testsupport;

import java.net.URI;
import java.util.Arrays;
import java.util.function.Predicate;
import okhttp3.mockwebserver.MockWebServer;

/** Trust only the origins of the supplied fixtures, including their ephemeral ports. */
public final class MockServerOrigins {
    private MockServerOrigins() { }

    public static Predicate<URI> allow(MockWebServer... servers) {
        var origins = Arrays.stream(servers).map(server -> server.url("/").uri()).toList();
        return uri -> origins.stream().anyMatch(origin -> origin.getScheme().equals(uri.getScheme())
                && origin.getHost().equals(uri.getHost()) && origin.getPort() == uri.getPort());
    }
}
