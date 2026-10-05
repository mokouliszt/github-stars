// Bridge to MainActivity.Bridge. JS → window.Native.*, Native → window.__native(type, payload).

export interface Repo {
  id: string;
  fullName: string;
  name: string;
  owner: string;
  avatar: string;
  desc: string;
  lang: string;
  topics: string[];
  stars: number;
  forks: number;
  pushedAt: string;
  updatedAt: string;
  starredAt: string;
  url: string;
  homepage: string;
  archived: boolean;
  fork: boolean;
  license: string;
}

/** One language's summary. */
export interface SummaryBlock {
  text: string;
  tags: string[];
  model: string;
  at: number;
  basis: string;
  /** Previous version, kept after a regeneration so it can be restored. */
  prev?: SummaryBlock;
}

/** Summaries per language. Only the block for the current app language is shown. */
export interface Summary {
  ja?: SummaryBlock;
  en?: SummaryBlock;
}

export interface Settings {
  /** App language; summaries are created and shown in this language. */
  lang: "ja" | "en";
  model: string;
  effort: string;
  sandbox: "read-only" | "workspace-write" | "danger-full-access";
  webSearch: boolean;
  codexAutoUpdate: "wifi" | "always" | "off";
  batchSize: number;
  concurrency: number;
  hyperCandidates: number;
  githubClientId: string;
}

export interface GhUser { login: string; avatar: string; name: string }
export interface CodexUpdate {
  active: string;
  bundled: string;
  latest: string;
  checkedAt: number;
  available?: string;
  updated?: string;
  upToDate?: boolean;
}
export interface ModelStatus { available: boolean; loaded: boolean; threads: number; error: string }
export type ProgressKind = "sync" | "summarize" | "codexUpdate";
export interface Progress {
  kind: ProgressKind;
  done: number;
  total: number;
  failed?: number;
  version?: string;
}
/** One entry per kind of work currently running; concurrent jobs never overwrite each other. */
export type ProgressMap = Partial<Record<ProgressKind, Progress>>;
export interface CodexModel { id: string; displayName: string; description: string; isDefault: boolean; defaultEffort: string; efforts: string[] }

export interface AppState {
  github: { loggedIn: boolean; user: GhUser };
  clientIdSet: boolean;
  codex: { bundled: boolean; update: CodexUpdate };
  settings: Settings;
  syncedAt: number;
  repos: Repo[];
  summaries: Record<string, Summary>;
  model: ModelStatus;
  progress: ProgressMap;
  running: string[];
  version: string;
}

/* eslint-disable @typescript-eslint/no-explicit-any */
declare global {
  interface Window {
    Native?: any;
    __native?: (type: string, payload: any) => void;
  }
}

type Handler = (payload: any) => void;
const listeners = new Map<string, Set<Handler>>();
// The latest value of these is replayed to late subscribers, so an event sent before React has
// mounted (e.g. at page load on a cold start) is never lost.
const STICKY = new Set(["theme", "insets"]);
const sticky = new Map<string, any>();

window.__native = (type: string, payload: any) => {
  if (STICKY.has(type)) sticky.set(type, payload);
  listeners.get(type)?.forEach((h) => {
    try { h(payload); } catch (e) { console.error(e); }
  });
};

export function on(type: string, h: Handler): () => void {
  let set = listeners.get(type);
  if (!set) { set = new Set(); listeners.set(type, set); }
  set.add(h);
  if (sticky.has(type)) {
    try { h(sticky.get(type)); } catch (e) { console.error(e); }
  }
  return () => { set!.delete(h); };
}

let seq = 0;
export const newReq = (prefix: string) => `${prefix}-${Date.now().toString(36)}-${++seq}`;

const N = (): any => window.Native ?? mock;

export const native = {
  state: (): AppState => JSON.parse(N().state()),
  /** System dark mode, read synchronously so the first frame is already right. */
  isDark: (): boolean => {
    try { return !!N().isDark(); } catch { return false; }
  },
  saveSettings: (patch: Partial<Settings>): Settings => JSON.parse(N().saveSettings(JSON.stringify(patch))),
  githubLogin: (reqId: string) => N().githubLogin(reqId),
  githubLogout: () => N().githubLogout(),
  sync: (reqId: string) => N().sync(reqId),
  codexStatus: (reqId: string) => N().codexStatus(reqId),
  codexLogin: (reqId: string, device: boolean) => N().codexLogin(reqId, device),
  codexCancelLogin: () => N().codexCancelLogin(),
  codexLogout: (reqId: string) => N().codexLogout(reqId),
  codexModels: (reqId: string) => N().codexModels(reqId),
  codexCheckUpdate: (reqId: string) => N().codexCheckUpdate(reqId),
  codexUpdate: (reqId: string) => N().codexUpdate(reqId),
  summarize: (reqId: string, mode: string) => N().summarize(reqId, mode),
  clearSummaries: () => N().clearSummaries(),
  restoreSummary: (id: string): Summary | null => {
    const r: string = N().restoreSummary(id);
    return r ? JSON.parse(r) : null;
  },
  cancel: (reqId: string) => N().cancel(reqId),
  cancelAll: () => N().cancelAll(),
  cancelSummaries: () => N().cancelSummaries(),
  hyperRank: (reqId: string, query: string, ids: string[]) => N().hyperRank(reqId, query, JSON.stringify(ids)),
  modelStatus: (): ModelStatus => JSON.parse(N().modelStatus()),
  openUrl: (url: string) => N().openUrl(url),
  copy: (text: string) => N().copy(text),
  share: (text: string) => N().share(text),
  haptic: (kind: "tick" | "confirm" | "long" = "tick") => N().haptic(kind),
};

// ---------------------------------------------------------------------------
// Browser preview only (vite dev / screenshots). Never used inside the app.
const emit = (type: string, payload: any) => setTimeout(() => window.__native?.(type, payload), 0);
const sample: Repo[] = [
  ["yt-dlp", "yt-dlp", "A feature-rich command-line audio/video downloader", "Python", ["youtube-dl", "downloader", "video"], 98000],
  ["microsoft", "vscode", "Visual Studio Code", "TypeScript", ["editor", "electron"], 168000],
  ["tailwindlabs", "tailwindcss", "A utility-first CSS framework for rapid UI development.", "TypeScript", ["css", "framework"], 86000],
  ["ggerganov", "llama.cpp", "LLM inference in C/C++", "C++", ["llm", "inference", "ggml"], 78000],
  ["shadcn-ui", "ui", "A set of beautifully-designed, accessible components and a code distribution platform.", "TypeScript", ["components", "react", "ui"], 82000],
  ["junegunn", "fzf", "A command-line fuzzy finder", "Go", ["cli", "fuzzy-search", "terminal"], 70000],
  ["BurntSushi", "ripgrep", "ripgrep recursively searches directories for a regex pattern", "Rust", ["grep", "search", "cli"], 52000],
  ["termux", "termux-app", "Termux - a terminal emulator application for Android OS extendible by variety of packages.", "Java", ["android", "terminal"], 38000],
].map(([owner, name, desc, lang, topics, stars], i) => ({
  id: String(1000 + i), fullName: `${owner}/${name}`, name: name as string, owner: owner as string,
  avatar: "", desc: desc as string, lang: lang as string, topics: topics as string[], stars: stars as number,
  forks: Math.round((stars as number) / 9), pushedAt: new Date(Date.now() - i * 86400e3 * 9).toISOString(),
  updatedAt: "", starredAt: new Date(Date.now() - i * 86400e3 * 30).toISOString(),
  url: `https://github.com/${owner}/${name}`, homepage: "", archived: false, fork: false, license: "MIT",
}));
const blk = (text: string, tags: string[]): SummaryBlock => ({ text, tags, model: "gpt-6.1-sol", at: Date.now(), basis: "" });
const mockSummaries: Record<string, Summary> = {
  "1000": {
    ja: blk("YouTubeなど数千のサイトから動画や音声をダウンロードできるコマンドラインツール。youtube-dlの派生で、形式選択や字幕取得にも対応。", ["動画ダウンロード", "youtube", "cli"]),
    en: blk("Command-line downloader for video and audio from YouTube and thousands of other sites; a youtube-dl fork with format selection and subtitles.", ["video downloader", "youtube", "cli"]),
  },
  "1003": {
    ja: blk("大規模言語モデルをC/C++でローカル推論するランタイム。量子化モデル(GGUF)でCPUやGPU上でも軽快に動く。", ["llm", "ローカル推論", "量子化"]),
  },
  "1005": {
    ja: blk("ターミナルで使う汎用のあいまい検索ツール。ファイル、履歴、プロセスなど任意のリストを対話的に絞り込める。", ["あいまい検索", "ターミナル", "cli"]),
    en: blk("General-purpose fuzzy finder for the terminal that interactively filters any list: files, history, processes.", ["fuzzy finder", "terminal", "cli"]),
  },
};
const mock = {
  isDark: () => location.hash.includes("dark"),
  state: () => JSON.stringify({
    github: { loggedIn: !location.hash.includes("onboarding"), user: { login: "octocat", avatar: "", name: "" } },
    clientIdSet: !location.hash.includes("noclient"),
    codex: { bundled: true, update: { active: "0.160.0", bundled: "0.160.0", latest: "0.160.0", checkedAt: Date.now() } },
    settings: {
      lang: location.hash.includes("en") ? "en" : "ja", model: "", effort: "", sandbox: "workspace-write", webSearch: true,
      codexAutoUpdate: "wifi", batchSize: 6, concurrency: 2, hyperCandidates: 20, githubClientId: "",
    },
    syncedAt: Date.now(), repos: sample, summaries: mockSummaries,
    model: { available: true, loaded: false, threads: 0, error: "" }, progress: {}, running: [], version: "dev",
  }),
  saveSettings: (j: string) => JSON.stringify({ ...JSON.parse(mock.state()).settings, ...JSON.parse(j) }),
  githubLogin: (id: string) => emit("githubCode", { reqId: id, userCode: "WDJB-MJHT", verificationUri: "https://github.com/login/device", expiresIn: 900 }),
  githubLogout: () => {},
  sync: (id: string) => emit("done", { reqId: id }),
  codexStatus: (id: string) => emit("codexAccount", { reqId: id, loggedIn: !location.hash.includes("nocodex"), email: "you@example.com", plan: "plus" }),
  codexLogin: (id: string) => emit("codexLoginStarted", { reqId: id, type: "chatgpt" }),
  codexCancelLogin: () => {},
  codexLogout: (id: string) => emit("codexAccount", { reqId: id, loggedIn: false }),
  codexModels: (id: string) => emit("codexModels", { reqId: id, models: [
    { id: "gpt-6.1-sol", displayName: "GPT-6.1 Sol", description: "", isDefault: true, defaultEffort: "medium", efforts: ["low", "medium", "high", "xhigh"] },
    { id: "gpt-6.1-terra", displayName: "GPT-6.1 Terra", description: "", isDefault: false, defaultEffort: "medium", efforts: ["low", "medium", "high"] },
  ] }),
  codexCheckUpdate: (id: string) => emit("codexUpdate", { reqId: id, active: "0.160.0", bundled: "0.160.0", latest: "0.160.0", checkedAt: Date.now(), available: "" }),
  codexUpdate: (id: string) => emit("done", { reqId: id }),
  summarize: (id: string) => emit("done", { reqId: id }),
  clearSummaries: () => {},
  restoreSummary: () => "",
  cancel: () => {},
  cancelAll: () => {},
  cancelSummaries: () => {},
  hyperRank: (id: string, _q: string, idsJson: string) => {
    const ids: string[] = JSON.parse(idsJson);
    ids.forEach((rid, i) => setTimeout(() => window.__native?.("hyperScore", { reqId: id, id: rid, p: Math.max(0.02, 0.95 - i * 0.18) }), 120 * (i + 1)));
    setTimeout(() => window.__native?.("hyperDone", { reqId: id, model: { available: true, loaded: true, threads: 4, error: "" } }), 120 * (ids.length + 1));
  },
  modelStatus: () => JSON.stringify({ available: true, loaded: false, threads: 0, error: "" }),
  openUrl: (u: string) => console.log("open", u),
  copy: () => {},
  share: () => {},
  haptic: () => {},
};
