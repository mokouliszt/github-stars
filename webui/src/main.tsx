import "./lib/polyfills";
import { createRoot } from "react-dom/client";
import App from "./App";
import { native, on } from "./lib/native";
import "./index.css";

// Theme is applied before the first render: the native side answers synchronously, so a cold
// start already follows the system dark mode (no reliance on an event racing React's mount).
document.documentElement.classList.toggle("dark", native.isDark());
on("theme", (p: { dark: boolean }) => document.documentElement.classList.toggle("dark", !!p.dark));
on("insets", (p: { top: number; bottom: number; ime: number }) => {
  const r = document.documentElement.style;
  r.setProperty("--sat", `${p.top}px`);
  r.setProperty("--sab", `${p.bottom}px`);
  r.setProperty("--ime", `${p.ime}px`);
});

function Fatal({ error }: { error: unknown }) {
  return (
    <div style={{ padding: 24, fontFamily: "system-ui", fontSize: 14 }}>
      <p style={{ fontWeight: 600 }}>起動できませんでした</p>
      <pre style={{ whiteSpace: "pre-wrap", opacity: 0.7 }}>{String((error as Error)?.stack || error)}</pre>
    </div>
  );
}

const root = createRoot(document.getElementById("root")!);
try {
  root.render(<App />);
} catch (e) {
  root.render(<Fatal error={e} />);
}
window.addEventListener("error", (e) => console.error(e.error || e.message));
