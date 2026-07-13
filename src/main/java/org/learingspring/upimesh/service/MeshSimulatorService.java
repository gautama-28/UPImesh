package org.learingspring.upimesh.service;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.learingspring.upimesh.model.MeshPacket;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Simulates a Bluetooth-style gossip mesh across a fixed set of virtual devices,
 * on a single laptop. "Bluetooth range" in this simulator means everyone -
 * every device that holds a packet broadcasts it to every other device each round.
 */
@Service
@Slf4j
public class MeshSimulatorService {

    private final Map<String, VirtualDevice> devices = new LinkedHashMap<>();

    // Bluetooth "range" - who can directly hear whom. Alice is NOT adjacent
    // to the bridge - she must route through a stranger, same as someone in
    // a basement can't reach the outside world in one hop.
    private static final Map<String, Set<String>> ADJACENCY = Map.of(
            "phone-alice", Set.of("stranger1", "stranger2"),
            "stranger1", Set.of("phone-alice", "phone-bridge"),
            "stranger2", Set.of("phone-alice", "phone-bridge"),
            "phone-bridge", Set.of("stranger1", "stranger2")
    );

    public MeshSimulatorService() {
        resetMesh();
    }

    /** Seeds the default mesh: 3 strangers + 1 bridge with internet. */
    public void resetMesh() {
        devices.clear();
        devices.put("phone-alice", new VirtualDevice("phone-alice", false));
        devices.put("stranger1", new VirtualDevice("stranger1", false));
        devices.put("stranger2", new VirtualDevice("stranger2", false));
        devices.put("phone-bridge", new VirtualDevice("phone-bridge", true));
        log.info("Mesh reset - 4 devices seeded");
    }

    public Collection<VirtualDevice> getDevices() {
        return devices.values();
    }

    /** Inject a freshly-created packet at a given starting device. */
    public void inject(String startDeviceId, MeshPacket packet) {
        VirtualDevice device = devices.get(startDeviceId);
        if (device == null) {
            throw new IllegalArgumentException("Unknown device: " + startDeviceId);
        }
        device.receive(packet);
        log.info("Injected packet {} at {}", packet.getPacketId(), startDeviceId);
    }

    /**
     * One gossip round: every device that holds packets broadcasts each one to
     * every OTHER device. TTL decrements per hop; packets with TTL <= 0 are dropped.
     */
    public GossipResult gossipOnce() {
        List<String> transfers = new ArrayList<>();

        // Snapshot what everyone holds BEFORE this round, so devices don't
        // immediately re-broadcast packets they just received in this same round.
        Map<String, List<MeshPacket>> snapshot = new LinkedHashMap<>();
        for (VirtualDevice d : devices.values()) {
            snapshot.put(d.getDeviceId(), new ArrayList<>(d.getHeldPackets()));
        }

        for (Map.Entry<String, List<MeshPacket>> entry : snapshot.entrySet()) {
            String fromDeviceId = entry.getKey();
            for (MeshPacket packet : entry.getValue()) {
                if (packet.getTtl() <= 0) continue;

                MeshPacket hopped = new MeshPacket(
                        packet.getPacketId(), packet.getTtl() - 1,
                        packet.getCreatedAt(), packet.getCiphertext());

                Set<String> neighbors = ADJACENCY.getOrDefault(fromDeviceId, Set.of());
                for (VirtualDevice target : devices.values()) {
                    if (neighbors.contains(target.getDeviceId())) {
                        target.receive(hopped);
                        transfers.add(fromDeviceId + " -> " + target.getDeviceId() + " (" + packet.getPacketId().substring(0, 8) + ")");
                    }
                }
            }
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        devices.values().forEach(d -> counts.put(d.getDeviceId(), d.packetCount()));

        return new GossipResult(transfers, counts);
    }
    /**
     * "All bridge nodes simultaneously walk outside and get 4G."
     * Returns one BridgeUpload per (bridge device, held packet) pair - if a
     * single bridge holds 3 packets, that's 3 uploads from it.
     */
    public List<BridgeUpload> collectBridgeUploads() {
        List<BridgeUpload> uploads = new ArrayList<>();
        for (VirtualDevice device : devices.values()) {
            if (device.isHasInternet()) {
                for (MeshPacket packet : device.getHeldPackets()) {
                    uploads.add(new BridgeUpload(device.getDeviceId(), packet));
                }
            }
        }
        return uploads;
    }

    @Getter
    @AllArgsConstructor
    public static class BridgeUpload {
        private final String bridgeNodeId;
        private final MeshPacket packet;
    }

    @Getter
    @AllArgsConstructor
    public static class GossipResult {
        private final List<String> transfers;
        private final Map<String, Integer> deviceCounts;
    }
}