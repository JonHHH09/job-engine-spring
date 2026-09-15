package org.instruct.jobenginespring.adapter.in.http.operator;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Decides whether an operator request's peer address is inside the supported local boundary.
 *
 * <p>On a host runtime only loopback peers are accepted. In the guarded container runtime the
 * MCP port is published on host loopback only, and Docker rewrites the source address of that
 * published traffic to the container's own default gateway, so the gateway address is accepted
 * as well. Sibling containers on the same bridge network keep their own addresses and stay
 * rejected, and the bearer token plus the loopback {@code Host}/{@code Origin} checks still
 * apply on top of this decision.
 */
final class OperatorPeerPolicy {

    private static final Path DEFAULT_ROUTE_TABLE = Path.of("/proc/net/route");

    private final boolean containerized;
    private final InetAddress containerGateway;

    OperatorPeerPolicy(boolean containerized) {
        this(containerized, containerized ? readDefaultGateway(DEFAULT_ROUTE_TABLE).orElse(null) : null);
    }

    OperatorPeerPolicy(boolean containerized, InetAddress containerGateway) {
        this.containerized = containerized;
        this.containerGateway = containerized ? containerGateway : null;
    }

    boolean isTrustedPeer(String remoteAddress) {
        InetAddress peer = parse(remoteAddress);
        if (peer == null) {
            return false;
        }
        if (peer.isLoopbackAddress()) {
            return true;
        }
        return containerized && containerGateway != null && containerGateway.equals(peer);
    }

    private static InetAddress parse(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return null;
        }
        try {
            // Numeric literals only; a hostname here would trigger a name lookup we do not want.
            return InetAddress.ofLiteral(remoteAddress.strip());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * Reads the IPv4 default gateway from a Linux {@code /proc/net/route} table.
     */
    static Optional<InetAddress> readDefaultGateway(Path routeTable) {
        List<String> lines;
        try {
            if (!Files.isReadable(routeTable)) {
                return Optional.empty();
            }
            lines = Files.readAllLines(routeTable, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
        for (String line : lines) {
            String[] columns = line.trim().split("\\s+");
            if (columns.length < 3 || !"00000000".equals(columns[1])) {
                continue;
            }
            Optional<InetAddress> gateway = parseLittleEndianIpv4(columns[2]);
            if (gateway.isPresent()) {
                return gateway;
            }
        }
        return Optional.empty();
    }

    private static Optional<InetAddress> parseLittleEndianIpv4(String hexAddress) {
        if (hexAddress.length() != 8) {
            return Optional.empty();
        }
        try {
            long value = Long.parseLong(hexAddress, 16);
            byte[] octets = new byte[]{
                    (byte) (value & 0xFF),
                    (byte) ((value >> 8) & 0xFF),
                    (byte) ((value >> 16) & 0xFF),
                    (byte) ((value >> 24) & 0xFF)
            };
            if (octets[0] == 0 && octets[1] == 0 && octets[2] == 0 && octets[3] == 0) {
                return Optional.empty();
            }
            return Optional.of(InetAddress.getByAddress(octets));
        } catch (NumberFormatException | java.net.UnknownHostException exception) {
            return Optional.empty();
        }
    }
}
