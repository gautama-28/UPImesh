import { useEffect, useState } from 'react';
import { api } from '../api/client.jsx';

function AccountBalances({ refreshKey }) {
    const [accounts, setAccounts] = useState([]);

    useEffect(() => {
        api.getAccounts().then(setAccounts).catch(console.error);
    }, [refreshKey]);

    return (
        <div className="bg-panel border border-hairline rounded p-4">
            <h2 className="font-display text-sm tracking-widest text-muted uppercase mb-3">
                Account Balances
            </h2>
            <div className="space-y-2">
                {accounts.map((acc) => (
                    <div
                        key={acc.vpa}
                        className="flex justify-between items-center bg-raised border border-hairline rounded px-3 py-2"
                    >
                        <div>
                            <p className="font-medium text-text">{acc.holderName}</p>
                            <p className="text-xs font-mono text-muted">{acc.vpa}</p>
                        </div>
                        <p className="text-lg font-mono text-settle">
                            ₹{Number(acc.balance).toLocaleString('en-IN')}
                        </p>
                    </div>
                ))}
            </div>
        </div>
    );
}

export default AccountBalances;