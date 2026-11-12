
import { NavLink } from "react-router-dom";
import { MessageSquare, LayoutDashboard, Settings, Zap } from "lucide-react";
import "./Sidebar.css";

export default function Sidebar() {
  return (
    <aside className="sidebar">
      <div className="sidebar-brand">
        <Zap size={26} />
        <span>Switchboard</span>
      </div>

      <div className="sidebar-section">WORKSPACE</div>

      <nav className="sidebar-nav">
        <NavLink
          to="/"
          end
          className={({ isActive }) =>
            `sidebar-link ${isActive ? "active" : ""}`
          }
        >
          <MessageSquare size={19} />
          <span>Chat</span>
        </NavLink>

        <NavLink
          to="/dashboard"
          className={({ isActive }) =>
            `sidebar-link ${isActive ? "active" : ""}`
          }
        >
          <LayoutDashboard size={19} />
          <span>Dashboard</span>
        </NavLink>

        <NavLink
          to="/settings"
          className={({ isActive }) =>
            `sidebar-link ${isActive ? "active" : ""}`
          }
        >
          <Settings size={19} />
          <span>Settings</span>
        </NavLink>
      </nav>

      <div className="sidebar-footer">
        <div className="status-dot" />
        <span>Switchboard v1.0</span>
      </div>
    </aside>
  );
}
