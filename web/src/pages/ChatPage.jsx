
import { useState } from "react";
import { Send, Sparkles, Bot, User } from "lucide-react";
import "./ChatPage.css";

export default function ChatPage() {
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState("");

  const sendMessage = (e) => {
    e.preventDefault();

    if (!input.trim()) return;

    const newMessage = {
      id: Date.now(),
      role: "user",
      content: input.trim(),
    };

    setMessages((prev) => [...prev, newMessage]);
    setInput("");
  };

  return (
    <div className="chat-page">
      <div className="page-header">
        <h1>AI Chat</h1>
        <p>Send prompts through the Switchboard AI Gateway.</p>
      </div>

      <div className="chat-container">
        <div className="chat-messages">
          {messages.length === 0 ? (
            <div className="chat-empty">
              <Sparkles size={40} />
              <h2>How can I help you today?</h2>
              <p>Start a conversation with Switchboard.</p>
            </div>
          ) : (
            messages.map((message) => (
              <div
                key={message.id}
                className={`chat-message ${message.role}`}
              >
                <div className="message-avatar">
                  {message.role === "user" ? (
                    <User size={18} />
                  ) : (
                    <Bot size={18} />
                  )}
                </div>

                <div className="message-content">
                  {message.content}
                </div>
              </div>
            ))
          )}
        </div>

        <form className="chat-input-area" onSubmit={sendMessage}>
          <input
            type="text"
            placeholder="Message Switchboard..."
            value={input}
            onChange={(e) => setInput(e.target.value)}
          />

          <button type="submit" disabled={!input.trim()}>
            <Send size={20} />
          </button>
        </form>
      </div>
    </div>
  );
}
