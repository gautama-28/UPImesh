import { useState, useRef, useEffect } from 'react';
import { MessageCircle, X, Send } from 'lucide-react';
import { api } from '../api/client';

function InsightChat() {
    const [open, setOpen] = useState(false);
    const [vpa, setVpa] = useState('alice@demo');
    const [question, setQuestion] = useState('');
    const [messages, setMessages] = useState([]);
    const [loading, setLoading] = useState(false);
    const bottomRef = useRef(null);

    useEffect(() => {
        bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
    }, [messages, open]);

    const handleAsk = async () => {
        if (!question.trim() || loading) return;
        const q = question;
        setMessages((prev) => [...prev, { role: 'user', text: q }]);
        setQuestion('');
        setLoading(true);
        try {
            const res = await api.askInsight(vpa, q);
            setMessages((prev) => [...prev, { role: 'assistant', text: res.answer }]);
        } catch (err) {
            setMessages((prev) => [...prev, { role: 'assistant', text: `Error: ${err.message}` }]);
        } finally {
            setLoading(false);
        }
    };

    return (
        <>
            {/* Floating toggle button */}
            <button
                onClick={() => setOpen((o) => !o)}
                className="fixed bottom-6 right-6 z-50 bg-signal text-ink rounded-full p-4 shadow-lg hover:opacity-90 transition"
                aria-label="Toggle spending insights chat"
            >
                {open ? <X size={22} /> : <MessageCircle size={22} />}
            </button>

            {/* Chat panel - only rendered when open */}
            {open && (
                <div className="fixed bottom-24 right-6 z-50 w-96 max-w-[calc(100vw-3rem)] bg-panel border border-hairline rounded flex flex-col shadow-xl" style={{ height: '480px' }}>
                    {/* Header */}
                    <div className="flex items-center justify-between px-4 py-3 border-b border-hairline">
                        <h2 className="font-display text-sm tracking-widest text-muted uppercase">
                            Insights
                        </h2>
                        <select
                            value={vpa}
                            onChange={(e) => setVpa(e.target.value)}
                            className="bg-raised border border-hairline rounded px-2 py-1 text-xs font-mono text-text"
                        >
                            <option value="alice@demo">alice@demo</option>
                            <option value="bob@demo">bob@demo</option>
                            <option value="carol@demo">carol@demo</option>
                        </select>
                    </div>

                    {/* Messages */}
                    <div className="flex-1 overflow-y-auto p-4 space-y-3">
                        {messages.length === 0 && (
                            <p className="text-muted text-xs font-mono">
                                Ask about {vpa}'s spending activity — e.g. "How much did I send this week?"
                            </p>
                        )}
                        {messages.map((m, i) => (
                            <div
                                key={i}
                                className={`text-sm rounded px-3 py-2 max-w-[85%] ${
                                    m.role === 'user'
                                        ? 'bg-raised ml-auto text-text'
                                        : 'bg-signal/10 border border-signal/30 text-text'
                                }`}
                            >
                                {m.text}
                            </div>
                        ))}
                        {loading && (
                            <p className="text-muted text-xs font-mono">thinking...</p>
                        )}
                        <div ref={bottomRef} />
                    </div>

                    {/* Input */}
                    <div className="flex gap-2 p-3 border-t border-hairline">
                        <input
                            value={question}
                            onChange={(e) => setQuestion(e.target.value)}
                            onKeyDown={(e) => e.key === 'Enter' && handleAsk()}
                            placeholder="Ask about your spending..."
                            className="flex-1 bg-raised border border-hairline rounded px-3 py-2 text-sm text-text"
                        />
                        <button
                            onClick={handleAsk}
                            disabled={loading}
                            className="bg-signal text-ink rounded p-2 disabled:opacity-50"
                        >
                            <Send size={18} />
                        </button>
                    </div>
                </div>
            )}
        </>
    );
}

export default InsightChat;