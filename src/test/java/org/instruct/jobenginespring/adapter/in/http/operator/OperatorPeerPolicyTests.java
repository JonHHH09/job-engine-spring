package org.instruct.jobenginespring.adapter.in.http.operator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperatorPeerPolicyTests {

    private static final String ROUTE_TABLE = """
            Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\tMTU\tWindow\tIRTT
            eth0\t000013AC\t00000000\t0001\t0\t0\t0\t0000FFFF\t0\t0\t0
            eth0\t00000000\t010013AC\t0003\t0\t0\t0\t00000000\t0\t0\t0
            """;

    @Test
    void hostRuntimeAcceptsOnlyLoopbackPeers() {
        OperatorPeerPolicy policy = new OperatorPeerPolicy(false);
        assertTrue(policy.isTrustedPeer("127.0.0.1"));
        assertTrue(policy.isTrustedPeer("::1"));
        assertFalse(policy.isTrustedPeer("172.19.0.1"));
        assertFalse(policy.isTrustedPeer("203.0.113.9"));
    }

    @Test
    void rejectsMissingAndUnparseablePeers() {
        OperatorPeerPolicy policy = new OperatorPeerPolicy(true, loopbackGateway());
        assertFalse(policy.isTrustedPeer(null));
        assertFalse(policy.isTrustedPeer(""));
        assertFalse(policy.isTrustedPeer("   "));
        assertFalse(policy.isTrustedPeer("not-a-valid-address"));
        // A hostname must never be resolved here; it is not a numeric literal.
        assertFalse(policy.isTrustedPeer("localhost"));
    }

    @Test
    void containerRuntimeAcceptsTheDefaultGatewayButNoOtherContainer() throws Exception {
        OperatorPeerPolicy policy = new OperatorPeerPolicy(true, InetAddress.getByName("172.19.0.1"));
        assertTrue(policy.isTrustedPeer("127.0.0.1"));
        assertTrue(policy.isTrustedPeer("172.19.0.1"));
        assertFalse(policy.isTrustedPeer("172.19.0.7"), "sibling containers must stay rejected");
        assertFalse(policy.isTrustedPeer("203.0.113.9"));
    }

    @Test
    void containerGatewayIsIgnoredWhenTheRuntimeIsNotContainerized() throws Exception {
        OperatorPeerPolicy policy = new OperatorPeerPolicy(false, InetAddress.getByName("172.19.0.1"));
        assertFalse(policy.isTrustedPeer("172.19.0.1"));
    }

    @Test
    void containerRuntimeWithoutAResolvableGatewayStaysLoopbackOnly() {
        OperatorPeerPolicy policy = new OperatorPeerPolicy(true, null);
        assertTrue(policy.isTrustedPeer("127.0.0.1"));
        assertFalse(policy.isTrustedPeer("172.19.0.1"));
    }

    @Test
    void readsTheLittleEndianDefaultGatewayFromTheRouteTable(@TempDir Path tempDir) throws IOException {
        Path routeTable = tempDir.resolve("route");
        Files.writeString(routeTable, ROUTE_TABLE, StandardCharsets.UTF_8);
        Optional<InetAddress> gateway = OperatorPeerPolicy.readDefaultGateway(routeTable);
        assertTrue(gateway.isPresent());
        assertEquals("172.19.0.1", gateway.get().getHostAddress());
    }

    @Test
    void missingMalformedOrGatewaylessRouteTablesYieldNoGateway(@TempDir Path tempDir) throws IOException {
        assertTrue(OperatorPeerPolicy.readDefaultGateway(tempDir.resolve("absent")).isEmpty());

        Path malformed = tempDir.resolve("malformed");
        Files.writeString(malformed, "not a route table\n", StandardCharsets.UTF_8);
        assertTrue(OperatorPeerPolicy.readDefaultGateway(malformed).isEmpty());

        Path zeroGateway = tempDir.resolve("zero");
        Files.writeString(zeroGateway, "eth0\t00000000\t00000000\t0003\t0\t0\t0\t00000000\t0\t0\t0\n",
                StandardCharsets.UTF_8);
        assertTrue(OperatorPeerPolicy.readDefaultGateway(zeroGateway).isEmpty());

        Path shortHex = tempDir.resolve("short");
        Files.writeString(shortHex, "eth0\t00000000\t0100\t0003\t0\t0\t0\t00000000\t0\t0\t0\n",
                StandardCharsets.UTF_8);
        assertTrue(OperatorPeerPolicy.readDefaultGateway(shortHex).isEmpty());
    }

    @Test
    void unreadableTruncatedAndNonHexRouteTableRowsYieldNoGateway(@TempDir Path tempDir) throws IOException {
        // A directory is "readable" but reading it as a file fails, exercising the I/O guard.
        Path directory = tempDir.resolve("route-dir");
        Files.createDirectory(directory);
        assertTrue(OperatorPeerPolicy.readDefaultGateway(directory).isEmpty());

        Path truncatedRow = tempDir.resolve("truncated");
        Files.writeString(truncatedRow, "eth0\t00000000\n", StandardCharsets.UTF_8);
        assertTrue(OperatorPeerPolicy.readDefaultGateway(truncatedRow).isEmpty());

        Path nonHex = tempDir.resolve("non-hex");
        Files.writeString(nonHex, "eth0\t00000000\tZZZZZZZZ\t0003\t0\t0\t0\t00000000\t0\t0\t0\n",
                StandardCharsets.UTF_8);
        assertTrue(OperatorPeerPolicy.readDefaultGateway(nonHex).isEmpty());
    }

    @Test
    void continuesPastNonDefaultRoutesToTheRealDefaultGateway(@TempDir Path tempDir) throws IOException {
        Path routeTable = tempDir.resolve("multi");
        Files.writeString(routeTable, """
                eth0\t000013AC\t00000000\t0001\t0\t0\t0\t0000FFFF\t0\t0\t0
                eth0\t00000000\t00000100\t0003\t0\t0\t0\t00000000\t0\t0\t0
                """, StandardCharsets.UTF_8);
        Optional<InetAddress> gateway = OperatorPeerPolicy.readDefaultGateway(routeTable);
        assertTrue(gateway.isPresent());
        assertEquals("0.1.0.0", gateway.get().getHostAddress());
    }

    private static InetAddress loopbackGateway() {
        return InetAddress.getLoopbackAddress();
    }
}
