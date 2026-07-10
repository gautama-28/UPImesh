import { useEffect, useState, useRef } from 'react';
import { api } from '../api/client';

const LAYOUT = {
    'phone-alice': { x: 80, y: 200, label: 'Alice' },
    'stranger1': { x: 260, y: 80, label: 'Stranger 1' },
    'stranger2': { x: 260, y: 320, label: 'Stranger 2' },
    'phone-bridge': { x: 460, y: 200, label: 'Bridge (has signal)' },
};
const ADJACENCY = {
    'phone-alice': ['stranger1', 'stranger2'],
    'stranger1': ['phone-alice', 'stranger2', 'phone-bridge'],
    'stranger2': ['phone-alice', 'stranger1', 'phone-bridge'],
    'phone-bridge': ['stranger1', 'stranger2'],
};

function MeshView({ refreshKey, lastGossip }) {
    const [devices, setDevices] = useState([]);
    const [animating, setAnimating] = useState([]);
    const animId = useRef(0);

    useEffect(() => {
        api.getMeshState().then((data) => setDevices(data.devices)).catch(console.error);
    }, [refreshKey]);

    useEffect(() => {
        if (!lastGossip || lastGossip.transfers.length === 0) return;

        const hops = lastGossip.transfers.map((t) => {
            const [fromId, rest] = t.split(' -> ');
            const [toId] = rest.split(' (');
            return { fromId, toId };
        });

        const newDots = hops
            .filter((h) => LAYOUT[h.fromId] && LAYOUT[h.toId])
            .map((h) => ({
                key: animId.current++,
                from: LAYOUT[h.fromId],
                to: LAYOUT[h.toId],
                arrived: false,
            }));

        setAnimating(newDots);

        requestAnimationFrame(() => {
            setAnimating((prev) => prev.map((d) => ({ ...d, arrived: true })));
        });

        const timeout = setTimeout(() => setAnimating([]), 900);
        return () => clearTimeout(timeout);
    }, [lastGossip]);

    return (
        <div className="bg-panel border border-hairline rounded p-4">
            <h2 className="font-display text-sm tracking-widest text-muted uppercase mb-3">
                Mesh Network
            </h2>
            <svg viewBox="0 0 540 400" className="w-full h-auto">

                // inside the component's render, replacing the old line-drawing map:
                {Object.entries(ADJACENCY).flatMap(([fromId, neighbors]) =>
                    neighbors
                        .filter((toId) => fromId < toId) // avoid drawing each edge twice
                        .map((toId) => (
                            <line
                                key={`${fromId}-${toId}`}
                                x1={LAYOUT[fromId].x} y1={LAYOUT[fromId].y}
                                x2={LAYOUT[toId].x} y2={LAYOUT[toId].y}
                                stroke="#263140" strokeWidth="1"
                            />
                        ))
                )}
                {/*{Object.entries(LAYOUT).map(([idA, posA], i) =>*/}
                {/*    Object.entries(LAYOUT).slice(i + 1).map(([idB, posB]) => (*/}
                {/*        <line*/}
                {/*            key={`${idA}-${idB}`}*/}
                {/*            x1={posA.x} y1={posA.y} x2={posB.x} y2={posB.y}*/}
                {/*            stroke="#263140" strokeWidth="1"*/}
                {/*        />*/}
                {/*    ))*/}
                {/*)}*/}

                {devices.map((d) => {
                    const pos = LAYOUT[d.deviceId];
                    if (!pos) return null;
                    const hasPackets = d.packetCount > 0;

                    return (
                        <g key={d.deviceId}>
                            <circle
                                cx={pos.x} cy={pos.y} r="28"
                                fill={hasPackets ? '#FF9F1C' : '#1B2430'}
                                stroke={d.hasInternet ? '#3DDC97' : '#263140'}
                                strokeWidth={d.hasInternet ? 3 : 1}
                            />
                            <text
                                x={pos.x} y={pos.y + 5}
                                textAnchor="middle"
                                className="font-mono text-xs"
                                fill={hasPackets ? '#0B0F14' : '#7C8B9B'}
                            >
                                {d.packetCount}
                            </text>
                            <text
                                x={pos.x} y={pos.y + 48}
                                textAnchor="middle"
                                className="font-mono text-xs"
                                fill="#7C8B9B"
                            >
                                {pos.label}
                            </text>
                        </g>
                    );
                })}

                {animating.map((dot) => (
                    <circle
                        key={dot.key}
                        cx={dot.arrived ? dot.to.x : dot.from.x}
                        cy={dot.arrived ? dot.to.y : dot.from.y}
                        r="6"
                        fill="#FF9F1C"
                        style={{ transition: 'cx 0.8s ease-in-out, cy 0.8s ease-in-out' }}
                    />
                ))}
            </svg>
        </div>
    );
}

export default MeshView;