import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";
import type { T } from "./i18n";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function compact(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(n >= 10_000_000 ? 0 : 1).replace(/\.0$/, "") + "m";
  if (n >= 1000) return (n / 1000).toFixed(n >= 10_000 ? 0 : 1).replace(/\.0$/, "") + "k";
  return String(n);
}

/** Relative time in the app language. */
export function ago(iso: string, t: T): string {
  if (!iso) return "";
  const ms = Date.parse(iso);
  if (isNaN(ms)) return "";
  const d = (Date.now() - ms) / 1000;
  if (d < 60) return t("agoNow");
  if (d < 3600) return t("agoMin", { n: Math.floor(d / 60) });
  if (d < 86400) return t("agoHour", { n: Math.floor(d / 3600) });
  if (d < 86400 * 30) return t("agoDay", { n: Math.floor(d / 86400) });
  if (d < 86400 * 365) return t("agoMonth", { n: Math.floor(d / (86400 * 30)) });
  return t("agoYear", { n: Math.floor(d / (86400 * 365)) });
}

export function date(iso: string): string {
  const t = Date.parse(iso);
  if (isNaN(t)) return "—";
  const d = new Date(t);
  return `${d.getFullYear()}/${String(d.getMonth() + 1).padStart(2, "0")}/${String(d.getDate()).padStart(2, "0")}`;
}

export function mb(bytes: number): string {
  return `${Math.round(bytes / 1_048_576)}MB`;
}
