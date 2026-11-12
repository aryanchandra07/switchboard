
import { BarChart3 } from "lucide-react";

export default function DashboardPage() {
  return (
    <div className="page">
      <div className="page-header">
        <h1>Usage Dashboard</h1>
        <p>Monitor AI requests, token usage, and costs.</p>
      </div>

      <div className="welcome-container">
        <BarChart3 size={40} />
        <h2>Analytics Dashboard</h2>
        <p>Usage charts and statistics coming soon.</p>
      </div>
    </div>
  );
}
