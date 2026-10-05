/** @type {import('tailwindcss').Config} */
export default {
  darkMode: "class",
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        // Channel form so opacity modifiers (bg-ink/10) work.
        bg: "rgb(var(--bg) / <alpha-value>)",
        card: "rgb(var(--card) / <alpha-value>)",
        line: "rgb(var(--line) / <alpha-value>)",
        subtle: "rgb(var(--subtle) / <alpha-value>)",
        muted: "rgb(var(--muted) / <alpha-value>)",
        ink: "rgb(var(--ink) / <alpha-value>)",
        inv: "rgb(var(--inv) / <alpha-value>)",
      },
      fontFamily: {
        sans: ["system-ui", "-apple-system", "Roboto", "\"Noto Sans JP\"", "\"Hiragino Sans\"", "sans-serif"],
        mono: ["ui-monospace", "\"Roboto Mono\"", "\"Noto Sans Mono\"", "monospace"],
      },
      borderRadius: { xl: "14px", "2xl": "18px" },
      keyframes: {
        "sheet-in": { from: { transform: "translateY(100%)" }, to: { transform: "translateY(0)" } },
        "sheet-out": { from: { transform: "translateY(0)" }, to: { transform: "translateY(100%)" } },
        "fade-in": { from: { opacity: 0 }, to: { opacity: 1 } },
        "fade-out": { from: { opacity: 1 }, to: { opacity: 0 } },
        indeterminate: { "0%": { transform: "translateX(-100%)" }, "100%": { transform: "translateX(250%)" } },
        "toast-in": { from: { transform: "translateY(12px)", opacity: 0 }, to: { transform: "translateY(0)", opacity: 1 } },
      },
      animation: {
        "sheet-in": "sheet-in 260ms cubic-bezier(.2,.8,.2,1)",
        "sheet-out": "sheet-out 200ms ease-in",
        "fade-in": "fade-in 200ms ease-out",
        "fade-out": "fade-out 160ms ease-in",
        indeterminate: "indeterminate 1.1s ease-in-out infinite",
        "toast-in": "toast-in 220ms ease-out",
      },
    },
  },
  plugins: [],
};
