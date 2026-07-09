package org.learingspring.upimesh.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The wire format that actually travels through the mesh, device to device.
 * Outer fields (packetId, ttl, createdAt) are readable by every intermediate
 * hop - only ciphertext is opaque to them.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MeshPacket {

    private String packetId;   // UUID, set by the sender
    private int ttl;           // decrements per hop, packet dropped at 0
    private long createdAt;    // epoch millis, when the packet was first created
    private String ciphertext; // Base64 hybrid-encrypted blob - opaque to intermediates
}