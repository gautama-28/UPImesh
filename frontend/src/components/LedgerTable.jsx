import { useEffect, useState } from 'react';
import { api } from '../api/client.jsx';

function LedgerTable({ refreshKey }) {
    const [transactions, setTransactions] = useState([]);

    useEffect(() => {
        api.getTransactions().then(setTransactions).catch(console.error);
    }, [refreshKey]);

    return (
        <div className="bg-panel border border-hairline rounded p-4">
            <h2 className="font-display text-sm tracking-widest text-muted uppercase mb-3">
                Transaction Ledger
            </h2>
            {transactions.length === 0 ? (
                <p className="text-muted text-sm font-mono">— no settlements yet —</p>
            ) : (
                <div className="overflow-x-auto">
                    <table className="w-full text-sm font-mono">
                        <thead>
                        <tr className="text-left text-muted border-b border-hairline">
                            <th className="pb-2 font-normal">Sender</th>
                            <th className="pb-2 font-normal">Receiver</th>
                            <th className="pb-2 font-normal">Amount</th>
                            <th className="pb-2 font-normal">Bridge</th>
                            <th className="pb-2 font-normal">Hops</th>
                            <th className="pb-2 font-normal">Settled</th>
                        </tr>
                        </thead>
                        <tbody>
                        {transactions.map((tx) => (
                            <tr key={tx.id} className="border-b border-hairline/50">
                                <td className="py-2 text-text">{tx.senderVpa}</td>
                                <td className="py-2 text-text">{tx.receiverVpa}</td>
                                <td className="py-2 text-settle">
                                    ₹{Number(tx.amount).toLocaleString('en-IN')}
                                </td>
                                <td className="py-2 text-muted">{tx.bridgeNodeId}</td>
                                <td className="py-2 text-muted">{tx.hopCount}</td>
                                <td className="py-2 text-muted">
                                    {new Date(tx.settledAt).toLocaleTimeString()}
                                </td>
                            </tr>
                        ))}
                        </tbody>
                    </table>
                </div>
            )}
        </div>
    );
}

export default LedgerTable;