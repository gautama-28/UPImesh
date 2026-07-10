import { useState } from 'react';
import { api } from './api/client';
import AccountBalances from './components/AccountBalances';
import LedgerTable from './components/LedgerTable';
import MeshView from './components/MeshView';
import InsightChat from './components/InsightChat';

function App() {
    const [refreshKey, setRefreshKey] = useState(0);
    const [lastGossip, setLastGossip] = useState(null);
    const [status, setStatus] = useState('');

    const [form, setForm] = useState({
        senderVpa: 'alice@demo',
        receiverVpa: 'bob@demo',
        amount: 500,
        pin: '1234',
    });

    const bump = () => setRefreshKey((k) => k + 1);

    const handleSend = async () => {
        setStatus('Sending...');
        try {
            const res = await api.sendPayment(form);
            setStatus(`Injected packet ${res.packetId.slice(0, 8)} at ${res.injectedAt}`);
            bump();
        } catch (err) {
            setStatus(`Error: ${err.message}`);
        }
    };

    const handleGossip = async () => {
        setStatus('Running gossip round...');
        try {
            const res = await api.runGossip();
            setLastGossip(res);
            setStatus(`Gossip round complete: ${res.transfers.length} hops`);
            bump();
        } catch (err) {
            setStatus(`Error: ${err.message}`);
        }
    };

    const handleFlush = async () => {
        setStatus('Bridges uploading...');
        try {
            const res = await api.flushBridges();
            const outcomes = res.results.map((r) => r.outcome).join(', ') || 'nothing to upload';
            setStatus(`Flush complete: ${outcomes}`);
            bump();
        } catch (err) {
            setStatus(`Error: ${err.message}`);
        }
    };

    const handleReset = async () => {
        setStatus('Resetting...');
        try {
            await api.resetMesh();
            setLastGossip(null);
            setStatus('Mesh and idempotency cache cleared');
            bump();
        } catch (err) {
            setStatus(`Error: ${err.message}`);
        }
    };

    return (
        <div className="min-h-screen bg-ink text-text">
            {/* Top status strip */}
            <header className="border-b border-hairline px-6 py-4 flex items-center justify-between">
                <div>
                    <h1 className="font-display text-xl font-semibold tracking-tight">
                        UPI Offline Mesh
                    </h1>
                    <p className="font-mono text-xs text-muted mt-0.5">
                        mesh-routed deferred settlement — demo console
                    </p>
                </div>
                <p className="font-mono text-xs text-signal">{status || 'idle'}</p>
            </header>

            <main className="p-6 grid grid-cols-1 lg:grid-cols-3 gap-6">
                {/* Left: mesh view + controls */}
                <div className="lg:col-span-2 space-y-6">
                    <MeshView refreshKey={refreshKey} lastGossip={lastGossip} />

                    {/* Controls panel */}
                    <div className="bg-panel border border-hairline rounded p-4">
                        <h2 className="font-display text-sm tracking-widest text-muted uppercase mb-3">
                            Controls
                        </h2>

                        <div className="grid grid-cols-2 gap-3 mb-4 font-mono text-sm">
                            <input
                                className="bg-raised border border-hairline rounded px-3 py-2 text-text"
                                value={form.senderVpa}
                                onChange={(e) => setForm({ ...form, senderVpa: e.target.value })}
                                placeholder="sender vpa"
                            />
                            <input
                                className="bg-raised border border-hairline rounded px-3 py-2 text-text"
                                value={form.receiverVpa}
                                onChange={(e) => setForm({ ...form, receiverVpa: e.target.value })}
                                placeholder="receiver vpa"
                            />
                            <input
                                type="number"
                                className="bg-raised border border-hairline rounded px-3 py-2 text-text"
                                value={form.amount}
                                onChange={(e) => setForm({ ...form, amount: Number(e.target.value) })}
                                placeholder="amount"
                            />
                            <input
                                className="bg-raised border border-hairline rounded px-3 py-2 text-text"
                                value={form.pin}
                                onChange={(e) => setForm({ ...form, pin: e.target.value })}
                                placeholder="pin"
                            />
                        </div>

                        <div className="flex flex-wrap gap-2">
                            <button
                                onClick={handleSend}
                                className="bg-signal text-ink font-medium px-4 py-2 rounded hover:opacity-90 transition"
                            >
                                Inject Payment
                            </button>
                            <button
                                onClick={handleGossip}
                                className="bg-raised border border-hairline text-text px-4 py-2 rounded hover:border-signal transition"
                            >
                                Run Gossip Round
                            </button>
                            <button
                                onClick={handleFlush}
                                className="bg-raised border border-hairline text-settle px-4 py-2 rounded hover:border-settle transition"
                            >
                                Flush Bridges
                            </button>
                            <button
                                onClick={handleReset}
                                className="bg-raised border border-hairline text-reject px-4 py-2 rounded hover:border-reject transition"
                            >
                                Reset Mesh
                            </button>
                        </div>
                    </div>
                </div>

                {/* Right: balances + ledger */}
                <div className="space-y-6">
                    <AccountBalances refreshKey={refreshKey} />
                    <LedgerTable refreshKey={refreshKey} />
                </div>
            </main>
            <InsightChat />
        </div>
    );
}

export default App;