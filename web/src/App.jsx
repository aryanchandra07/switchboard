
import { BrowserRouter, Routes, Route } from "react-router-dom";
import Sidebar from "./components/Sidebar";
import ChatPage from "./pages/ChatPage";
import DashboardPage from "./pages/DashboardPage";
import "./App.css";

function App() {
  return (
    <BrowserRouter>
      <div className="app-layout">
        <Sidebar />

        <main className="main-content">
          <Routes>
            <Route path="/" element={<ChatPage />} />
            <Route path="/dashboard" element={<DashboardPage />} />
            <Route
              path="/settings"
              element={<h1>Settings — Coming Soon</h1>}
            />
          </Routes>
        </main>
      </div>
    </BrowserRouter>
  );
}

export default App;
