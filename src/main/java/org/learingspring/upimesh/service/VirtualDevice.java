package org.learingspring.upimesh.service;

import lombok.Getter;
import org.learingspring.upimesh.model.MeshPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * One simulated phone in the mesh. Holds packets it has received via gossip,
 * until either it passes them on to another device, or (if it has internet)
 * uploads them to the backend.
 */
@Getter
public class VirtualDevice {

    private final String deviceId;
    private final boolean hasInternet;
    private final List<MeshPacket> heldPackets = new ArrayList<>();

    public VirtualDevice(String deviceId, boolean hasInternet) {
        this.deviceId = deviceId;
        this.hasInternet = hasInternet;
    }

    public void receive(MeshPacket packet) {
        // Avoid holding duplicate copies of the exact same packet
        boolean alreadyHeld = heldPackets.stream()
                .anyMatch(p -> p.getPacketId().equals(packet.getPacketId()));
        if (!alreadyHeld) {
            heldPackets.add(packet);
        }
    }

    public int packetCount() {
        return heldPackets.size();
    }

    public void clear() {
        heldPackets.clear();
    }
}