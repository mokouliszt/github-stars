import * as React from "react";
import { ArrowDownUp, RefreshCw, Search, Settings as SettingsIcon, X } from "lucide-react";
import {
  native, newReq, on,
  type AppState, type CodexModel, type CodexUpdate, type ModelStatus, type Progress, type ProgressMap, type Repo, type Settings, type Summary,
} from "@/lib/native";
import { buildIndex, search } from "@/lib/search";
import { I18nContext, translate, type Key, type Lang, type T } from "@/lib/i18n";
import { cn } from "@/lib/utils";
import { CodexLoginSheet, type CodexLoginState } from "@/components/CodexLoginSheet";
import { Onboarding, type DeviceCode } from "@/components/Onboarding";
import { RepoCard, RepoSheet } from "@/components/RepoCard";
import { SettingsSheet, type CodexAccount } from "@/components/SettingsSheet";
import { SummarizeSheet } from "@/components/SummarizeSheet";
import { Button, IconButton, ProgressBar, RadioList, Segmented, Sheet, Spinner, Toasts, type ToastItem } from "@/components/ui";

type Mode = "normal" | "hyper";
type SortKey = "starred" | "updated" | "stars" | "name";
const SORTS: { value: SortKey; key: Key }[] = [
  { value: "starred", key: "sortStarred" },
  { value: "updated", key: "sortUpdated" },
  { value: "stars", key: "sortStars" },
  { value: "name", key: "sortName" },
];

interface HyperState {
  reqId: string;
  query: string;
  candidates: string[];
  lex: Record<string, number>;
  scores: Record<string, number>;
  running: boolean;
  error: string;
}

const SYNC_STALE_MS = 6 * 60 * 60 * 1000;

function useDebounced<T>(value: T, ms: number): T {
  const [v, setV] = React.useState(value);
  React.useEffect(() => {
    const t = setTimeout(() => setV(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return v;
}

export default function App() {
  const initial = React.useMemo<AppState>(() => native.state(), []);
  const [github, setGithub] = React.useState(initial.github);
  const [clientIdSet, setClientIdSet] = React.useState(initial.clientIdSet);
  const [settings, setSettings] = React.useState<Settings>(initial.settings);
  const [repos, setRepos] = React.useState<Repo[]>(initial.repos);
  const [summaries, setSummaries] = React.useState<Record<string, Summary>>(initial.summaries);
  const [laya, setLaya] = React.useState<ModelStatus>(initial.model);
  const [progress, setProgress] = React.useState<ProgressMap>(initial.progress ?? {});
  const [update, setUpdate] = React.useState<CodexUpdate>(initial.codex.update);
  const [codex, setCodex] = React.useState<CodexAccount>({ checked: false, loggedIn: false, email: "", plan: "" });
  const [models, setModels] = React.useState<CodexModel[] | null>(null);

  const [query, setQuery] = React.useState("");
  const [mode, setMode] = React.useState<Mode>("normal");
  const [langFilter, setLangFilter] = React.useState<string | null>(null);
  const [onlyMissing, setOnlyMissing] = React.useState(false);
  const [sort, setSort] = React.useState<SortKey>("starred");
  const [hyper, setHyper] = React.useState<HyperState | null>(null);

  const [openId, setOpenId] = React.useState<string | null>(null);
  const [sheet, setSheet] = React.useState<null | "settings" | "summarize" | "codexLogin" | "sort">(null);
  const [deviceCode, setDeviceCode] = React.useState<DeviceCode | null>(null);
  const [ghReq, setGhReq] = React.useState<string | null>(null);
  const [codexLogin, setCodexLogin] = React.useState<CodexLoginState & { reqId?: string }>({ phase: "idle" });
  // Single-repository runs in flight (restored from running jobs after the Activity is recreated).
  const [resummarizing, setResummarizing] = React.useState<Set<string>>(
    () => new Set(initial.running.filter((k) => k.startsWith("resum-")).map((k) => k.split("-")[1])),
  );
  const [toasts, setToasts] = React.useState<ToastItem[]>([]);
  const [limit, setLimit] = React.useState(40);
  const summarizeReq = React.useRef<string | null>(null);
  const hyperReq = React.useRef<string | null>(null);
  const loginPhase = React.useRef<CodexLoginState["phase"]>("idle");
  const [lastOpenId, setLastOpenId] = React.useState<string | null>(null);

  // App language: UI text and the language summaries are created/shown in.
  const lang: Lang = settings.lang;
  const t: T = React.useCallback((k, v) => translate(lang, k, v), [lang]);
  const tRef = React.useRef(t);
  tRef.current = t;
  React.useEffect(() => { document.documentElement.lang = lang; }, [lang]);

  const toast = React.useCallback((text: string, action?: ToastItem["action"]) => {
    const id = Date.now() + Math.random();
    setToasts((t) => [...t.slice(-2), { id, text, action }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), action ? 6000 : 3200);
  }, []);

  const changeSettings = React.useCallback((patch: Partial<Settings>) => {
    const s = native.saveSettings(patch);
    setSettings(s);
    if ("githubClientId" in patch) setClientIdSet(!!s.githubClientId);
  }, []);

  const startSync = React.useCallback(() => {
    native.sync(newReq("sync"));
  }, []);

  const checkCodex = React.useCallback(() => native.codexStatus(newReq("codex-status")), []);

  // ------------------------------------------------------------ native events
  React.useEffect(() => {
    const offs = [
      on("repos", (p) => {
        setRepos(p.items);
        if (p.user) setGithub((g) => ({ ...g, user: p.user }));
      }),
      on("summaries", (p) => setSummaries((s) => ({ ...s, ...p.entries }))),
      on("progress", (p: Progress) => setProgress((m) => ({ ...m, [p.kind]: p }))),
      on("progressEnd", (p: { kind: Progress["kind"] }) =>
        setProgress((m) => { const n = { ...m }; delete n[p.kind]; return n; })),
      on("githubCode", (p) => setDeviceCode({ userCode: p.userCode, verificationUri: p.verificationUri })),
      on("githubLoggedIn", (p) => {
        setGithub({ loggedIn: true, user: p.user });
        setDeviceCode(null);
        setGhReq(null);
        native.haptic("confirm");
        startSync();
        checkCodex();
      }),
      on("codexAccount", (p) => {
        setCodex({ checked: true, loggedIn: !!p.loggedIn, email: p.email || "", plan: p.plan || "" });
        if (p.loggedIn) {
          if (loginPhase.current !== "idle") {
            setSheet((s) => (s === "codexLogin" ? null : s));
            toast(tRef.current("toastCodexSignedIn"));
          }
          setCodexLogin({ phase: "idle" });
        } else setModels(null);
      }),
      on("codexLoginStarted", (p) =>
        setCodexLogin((c) => ({ ...c, verificationUrl: p.verificationUrl || undefined, userCode: p.userCode || undefined })),
      ),
      on("codexModels", (p) => setModels(p.models)),
      on("codexUpdate", (p) => {
        setUpdate((u) => ({ ...u, ...p }));
        const tt = tRef.current;
        if (p.updated) toast(tt("toastCodexUpdated", { v: p.updated }));
        else if (p.available) toast(tt("toastCodexAvailable", { v: p.available }), { label: tt("update"), run: () => native.codexUpdate(newReq("codex-update")) });
        else if (p.upToDate) toast(tt("toastCodexLatest"));
      }),
      on("hyperScore", (p) =>
        setHyper((h) => (h && h.reqId === p.reqId ? { ...h, scores: { ...h.scores, [p.id]: p.p } } : h)),
      ),
      on("hyperDone", (p) => {
        setHyper((h) => (h && h.reqId === p.reqId ? { ...h, running: false } : h));
        if (p.model) setLaya(p.model);
      }),
      on("summarized", (p) => {
        const tt = tRef.current;
        if (p.single) {
          toast(p.failed ? tt("toastOneFailed") : tt("toastOneDone"));
          return;
        }
        if (p.done > 0) toast(p.failed ? tt("toastBulkPartial", { n: p.done - p.failed, f: p.failed }) : tt("toastBulkDone", { n: p.done }));
      }),
      on("cancelled", () => toast(tRef.current("stopped"))),
      on("notice", (p) => toast(p.message)),
      on("error", (p) => {
        if (p.reqId && p.reqId === hyperReq.current) {
          setHyper((h) => (h && h.reqId === p.reqId ? { ...h, running: false, error: p.message } : h));
          return;
        }
        if (p.needGithubLogin) setGithub((g) => ({ ...g, loggedIn: false }));
        if (p.needCodexLogin) {
          setCodex((c) => ({ ...c, loggedIn: false, checked: true }));
          setSheet("codexLogin");
        }
        if (p.reqId?.startsWith("codex-status")) {
          setCodex((c) => ({ ...c, checked: true }));
          return;
        }
        toast(p.message);
      }),
      on("done", (p) => {
        const id: string = p.reqId || "";
        if (id.startsWith("gh-login")) { setGhReq(null); }
        if (id.startsWith("codex-login")) setCodexLogin((c) => (c.reqId === id ? { phase: "idle" } : c));
        if (id === summarizeReq.current) summarizeReq.current = null;
        if (id.startsWith("resum-")) {
          const rid = id.split("-")[1];
          setResummarizing((s) => { const n = new Set(s); n.delete(rid); return n; });
        }
      }),
    ];
    return () => offs.forEach((f) => f());
  }, [toast, startSync, checkCodex]);

  React.useEffect(() => { loginPhase.current = codexLogin.phase; }, [codexLogin.phase]);
  React.useEffect(() => { if (openId) setLastOpenId(openId); }, [openId]);

  // First launch after sign-in: refresh stale data and check the ChatGPT session.
  React.useEffect(() => {
    if (!github.loggedIn) return;
    checkCodex();
    if (repos.length === 0 || Date.now() - initial.syncedAt > SYNC_STALE_MS) startSync();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Android back: close what is open first.
  React.useEffect(() => {
    (window as unknown as { __back: () => boolean }).__back = () => {
      if (openId) { setOpenId(null); return true; }
      if (sheet) { setSheet(null); return true; }
      if (query) { setQuery(""); return true; }
      return false;
    };
  }, [openId, sheet, query]);

  // ------------------------------------------------------------ derived lists
  const index = React.useMemo(() => buildIndex(repos, summaries, lang), [repos, summaries, lang]);
  const byId = React.useMemo(() => new Map(repos.map((r) => [r.id, r])), [repos]);
  // "No summary" means none in the current language, even if the other language has one.
  const hasSummary = React.useCallback((id: string) => !!summaries[id]?.[lang]?.text, [summaries, lang]);
  const missingCount = React.useMemo(() => repos.filter((r) => !hasSummary(r.id)).length, [repos, hasSummary]);
  const languages = React.useMemo(() => {
    const m = new Map<string, number>();
    for (const r of repos) if (r.lang) m.set(r.lang, (m.get(r.lang) || 0) + 1);
    return [...m.entries()].sort((a, b) => b[1] - a[1]).slice(0, 10).map(([l]) => l);
  }, [repos]);

  const passes = React.useCallback(
    (r: Repo) => (!langFilter || r.lang === langFilter) && (!onlyMissing || !hasSummary(r.id)),
    [langFilter, onlyMissing, hasSummary],
  );

  const debounced = useDebounced(query, mode === "hyper" ? 450 : 120);
  const q = debounced.trim();

  // Hyper fuzzy: lexical candidates first, then Laya re-scores the top N on-device.
  const orHits = React.useMemo(() => (mode === "hyper" && q ? search(index, q, "or") : []), [mode, q, index]);
  const orHitsRef = React.useRef(orHits);
  orHitsRef.current = orHits;

  // Re-rank only when the query or filters change, not when summaries stream in.
  React.useEffect(() => {
    if (mode !== "hyper" || !q) {
      if (hyperReq.current) native.cancel(hyperReq.current);
      hyperReq.current = null;
      setHyper(null);
      return;
    }
    const hits = orHitsRef.current.filter((h) => { const r = byId.get(h.id); return !!r && passes(r); });
    const n = settings.hyperCandidates;
    const ids = hits.slice(0, n).map((h) => h.id);
    // Small libraries: let the model look at everything when words alone find too little.
    if (ids.length < n && repos.length <= n * 3) {
      for (const r of repos) {
        if (ids.length >= n) break;
        if (passes(r) && !ids.includes(r.id)) ids.push(r.id);
      }
    }
    const lex: Record<string, number> = {};
    const max = hits[0]?.score || 1;
    for (const h of hits) lex[h.id] = h.score / max;
    if (hyperReq.current) native.cancel(hyperReq.current);
    const reqId = newReq("hyper");
    hyperReq.current = reqId;
    setHyper({ reqId, query: q, candidates: ids, lex, scores: {}, running: laya.available && ids.length > 0, error: "" });
    if (laya.available && ids.length > 0) native.hyperRank(reqId, q, ids);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode, q, langFilter, onlyMissing, settings.hyperCandidates, laya.available, repos, lang]);

  const list: { repo: Repo; match?: number; dim?: boolean }[] = React.useMemo(() => {
    if (!q) {
      const arr = repos.filter(passes);
      const key = (r: Repo) => r.starredAt || "";
      arr.sort((a, b) => {
        switch (sort) {
          case "updated": return (b.pushedAt || "").localeCompare(a.pushedAt || "");
          case "stars": return b.stars - a.stars;
          case "name": return a.name.localeCompare(b.name, "en", { sensitivity: "base" });
          default: return key(b).localeCompare(key(a));
        }
      });
      return arr.map((repo) => ({ repo }));
    }
    if (mode === "normal") {
      return search(index, q, "and")
        .map((h) => byId.get(h.id)!)
        .filter((r) => r && passes(r))
        .map((repo) => ({ repo }));
    }
    if (!hyper || hyper.query !== q) return [];
    const scored = hyper.candidates.map((id) => {
      const p = hyper.scores[id];
      const l = hyper.lex[id] ?? 0;
      const v = p != null ? 0.75 * p + 0.25 * l : 0.375 + 0.25 * l;
      return { id, v, p };
    });
    scored.sort((a, b) => b.v - a.v);
    const out: { repo: Repo; match?: number; dim?: boolean }[] = scored.map((s) => ({
      repo: byId.get(s.id)!,
      match: s.p as number | undefined,
      dim: s.p != null && s.p < 0.15,
    })).filter((x) => x.repo);
    // Lexical hits beyond the re-ranked set follow, unscored.
    const seen = new Set(hyper.candidates);
    for (const h of orHits) {
      if (out.length >= hyper.candidates.length + 30) break;
      const r = byId.get(h.id);
      if (r && !seen.has(h.id) && passes(r)) out.push({ repo: r, match: undefined, dim: false });
    }
    return out;
  }, [q, mode, repos, passes, sort, index, byId, hyper, orHits]);

  React.useEffect(() => { setLimit(40); }, [q, mode, langFilter, onlyMissing, sort]);
  React.useEffect(() => { if (window.scrollY > 56) window.scrollTo({ top: 56 }); }, [q, mode]);

  // Incremental rendering for long lists.
  const sentinel = React.useRef<HTMLDivElement>(null);
  React.useEffect(() => {
    const el = sentinel.current;
    if (!el) return;
    const io = new IntersectionObserver((e) => { if (e[0].isIntersecting) setLimit((l) => l + 40); }, { rootMargin: "800px" });
    io.observe(el);
    return () => io.disconnect();
  }, [list.length]);

  // ------------------------------------------------------------ actions
  const openRepo = React.useCallback((id: string) => setOpenId(id), []);

  const startSummarize = (mode: "missing" | "all") => {
    const id = newReq("summarize");
    summarizeReq.current = id;
    native.summarize(id, mode);
    setSheet(null);
  };

  const resummarize = (rid: string) => {
    if (!codex.loggedIn) { setSheet("codexLogin"); return; }
    setResummarizing((s) => new Set(s).add(rid));
    native.summarize(`resum-${rid}-${Date.now().toString(36)}`, rid);
  };

  const startCodexLogin = (device: boolean) => {
    const reqId = newReq("codex-login");
    setCodexLogin({ phase: device ? "device" : "browser", reqId });
    native.codexLogin(reqId, device);
  };

  const cancelCodexLogin = () => {
    native.codexCancelLogin();
    if (codexLogin.reqId) native.cancel(codexLogin.reqId);
    setCodexLogin({ phase: "idle" });
  };

  const ctx = React.useMemo(() => ({ lang, t }), [lang, t]);

  // ------------------------------------------------------------ onboarding
  if (!github.loggedIn) {
    return (
      <I18nContext.Provider value={ctx}>
        <Onboarding
          lang={lang}
          onLang={(l) => changeSettings({ lang: l })}
          clientIdSet={clientIdSet}
          savedClientId={settings.githubClientId}
          code={deviceCode}
          waiting={!!ghReq && !deviceCode}
          onSaveClientId={(v) => changeSettings({ githubClientId: v })}
          onLogin={() => {
            const id = newReq("gh-login");
            setGhReq(id);
            native.githubLogin(id);
          }}
          onCancel={() => {
            if (ghReq) native.cancel(ghReq);
            setGhReq(null);
            setDeviceCode(null);
          }}
        />
        <Toasts items={toasts} dismiss={(id) => setToasts((x) => x.filter((y) => y.id !== id))} />
      </I18nContext.Provider>
    );
  }

  // ------------------------------------------------------------ main
  const syncing = !!progress.sync;
  const summarizing = !!progress.summarize;
  const sum = progress.summarize;
  const sheetRepo = lastOpenId ? byId.get(lastOpenId) ?? null : null;
  const defaultModelName = settings.model || models?.find((m) => m.isDefault)?.displayName || t("codexDefault");
  const visible = list.slice(0, limit);

  return (
    <I18nContext.Provider value={ctx}>
    <div className="min-h-full">
      {/* Covers content scrolling under the status bar. */}
      <div className="fixed inset-x-0 top-0 z-40 bg-bg" style={{ height: "var(--sat)" }} />
      {/* Title row scrolls away; the search block stays. */}
      <div className="pt-safe">
        <div className="flex h-14 items-center px-4">
          <div className="flex flex-1 items-baseline gap-2">
            <h1 className="text-[24px] font-semibold tracking-tight">Stars</h1>
            <span className="num text-[15px] text-muted">{repos.length.toLocaleString()}</span>
          </div>
          <IconButton label={t("sync")} onClick={startSync} disabled={syncing}>
            <RefreshCw className={cn("h-[19px] w-[19px]", syncing && "animate-spin")} />
          </IconButton>
          <IconButton label={t("settings")} onClick={() => setSheet("settings")}>
            {github.user.avatar ? (
              <img src={`${github.user.avatar}${github.user.avatar.includes("?") ? "&" : "?"}s=64`} alt="" className="h-7 w-7 rounded-full bg-subtle" />
            ) : (
              <SettingsIcon className="h-[19px] w-[19px]" />
            )}
          </IconButton>
        </div>
      </div>

      <div className="sticky z-30 bg-bg/95 pb-2 pt-1 backdrop-blur" style={{ top: "var(--sat)" }}>
        <div className="px-4">
          <div className="flex h-11 items-center gap-2 rounded-xl bg-subtle px-3">
            <Search className="h-4 w-4 shrink-0 text-muted" />
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={(e) => { if (e.key === "Enter") (e.target as HTMLInputElement).blur(); }}
              enterKeyHint="search"
              placeholder={mode === "hyper" ? t("searchHyper") : t("searchNormal")}
              className="h-full min-w-0 flex-1 bg-transparent text-[15px] outline-none placeholder:text-muted"
            />
            {query && (
              <button aria-label={t("clear")} onClick={() => setQuery("")} className="rounded-full p-1 text-muted active:text-ink">
                <X className="h-4 w-4" />
              </button>
            )}
          </div>
          <div className="mt-2 flex items-center gap-2">
            <Segmented
              size="sm"
              className={lang === "en" ? "w-[184px]" : "w-[200px]"}
              value={mode}
              onChange={(m) => { setMode(m); native.haptic(); }}
              options={[{ value: "normal", label: t("modeNormal") }, { value: "hyper", label: t("modeHyper") }]}
            />
            <div className="flex-1" />
            {!q && (
              <button onClick={() => setSheet("sort")} className="flex h-8 min-w-0 items-center gap-1.5 rounded-lg px-2 text-[12.5px] text-muted active:bg-subtle">
                <ArrowDownUp className="h-3.5 w-3.5 shrink-0" />
                <span className="truncate">{t(SORTS.find((x) => x.value === sort)!.key)}</span>
              </button>
            )}
          </div>
        </div>
        {(languages.length > 1 || missingCount > 0) && (
          <div className="no-scrollbar mt-2 flex gap-1.5 overflow-x-auto px-4">
            {missingCount > 0 && (
              <Chip active={onlyMissing} onClick={() => setOnlyMissing(!onlyMissing)}>{t("chipNoSummary", { n: missingCount })}</Chip>
            )}
            {languages.map((l) => (
              <Chip key={l} active={langFilter === l} onClick={() => setLangFilter(langFilter === l ? null : l)}>{l}</Chip>
            ))}
          </div>
        )}
        {(syncing || summarizing || (hyper?.running && mode === "hyper")) && (
          <div className="absolute inset-x-0 bottom-0">
            <ProgressBar
              className="h-[2px] rounded-none bg-transparent"
              value={sum && sum.total ? sum.done / sum.total
                : hyper?.running ? Object.keys(hyper.scores).length / Math.max(1, hyper.candidates.length) : undefined}
            />
          </div>
        )}
      </div>

      <main className="space-y-2.5 px-4 pt-2" style={{ paddingBottom: "calc(var(--sab) + 32px)" }}>
        {mode === "hyper" && q && <HyperStatus hyper={hyper} laya={laya} />}

        {!q && sum && (
          <Banner>
            <div className="flex items-center gap-3">
              <Spinner className="text-ink" />
              <div className="num flex-1 text-[13.5px]">{t("creatingProgress", { done: sum.done, total: sum.total })}</div>
              <Button size="sm" variant="ghost" onClick={() => setSheet("summarize")}>{t("details")}</Button>
            </div>
          </Banner>
        )}
        {!q && progress.sync && (
          <Banner>
            <div className="num flex items-center gap-3 text-[13.5px]">
              <Spinner className="text-ink" /> {progress.sync.done > 0 ? t("syncingCount", { n: progress.sync.done }) : t("syncing")}
            </div>
          </Banner>
        )}
        {!q && !summarizing && missingCount > 0 && codex.checked && (
          <Banner>
            <div className="flex items-center gap-3">
              <div className="flex-1 text-[13.5px] leading-snug">
                {codex.loggedIn ? t("missingBanner", { n: missingCount }) : t("signInBanner")}
              </div>
              {codex.loggedIn ? (
                <Button size="sm" variant="primary" onClick={() => setSheet("summarize")}>{t("create")}</Button>
              ) : (
                <Button size="sm" variant="primary" onClick={() => setSheet("codexLogin")}>{t("signIn")}</Button>
              )}
            </div>
          </Banner>
        )}

        {visible.map(({ repo, match, dim }) => (
          <RepoCard
            key={repo.id}
            repo={repo}
            block={summaries[repo.id]?.[lang]}
            match={mode === "hyper" && q ? match : undefined}
            dim={dim}
            busy={resummarizing.has(repo.id)}
            onOpen={openRepo}
          />
        ))}
        <div ref={sentinel} />

        {list.length === 0 && <Empty q={q} mode={mode} repos={repos.length} syncing={syncing} onHyper={() => setMode("hyper")} />}
      </main>

      <RepoSheet
        open={!!openId}
        repo={sheetRepo}
        block={lastOpenId ? summaries[lastOpenId]?.[lang] : undefined}
        canSummarize={codex.loggedIn}
        busy={!!lastOpenId && resummarizing.has(lastOpenId)}
        onClose={() => setOpenId(null)}
        onResummarize={resummarize}
        onRestore={(rid) => {
          const restored = native.restoreSummary(rid);
          if (restored) { setSummaries((x) => ({ ...x, [rid]: restored })); toast(t("toastRestored")); }
        }}
        onOpenUrl={native.openUrl}
        onCopy={(text) => { native.copy(text); toast(t("copied")); }}
        onShare={native.share}
      />

      <SummarizeSheet
        open={sheet === "summarize"}
        onOpenChange={(v) => setSheet(v ? "summarize" : null)}
        total={repos.length}
        missing={missingCount}
        progress={sum ?? null}
        loggedIn={codex.loggedIn}
        modelLabel={defaultModelName}
        batchSize={settings.batchSize}
        onStart={startSummarize}
        onCancel={() => native.cancelSummaries()}
        onLogin={() => setSheet("codexLogin")}
      />

      <CodexLoginSheet
        open={sheet === "codexLogin"}
        onOpenChange={(v) => { if (!v && codexLogin.phase !== "idle") cancelCodexLogin(); setSheet(v ? "codexLogin" : null); }}
        state={codexLogin}
        onStart={startCodexLogin}
        onCancel={cancelCodexLogin}
      />

      <Sheet open={sheet === "sort"} onOpenChange={(v) => setSheet(v ? "sort" : null)} title={t("sortTitle")}>
        <RadioList value={sort} onChange={(v) => { setSort(v); setSheet(null); }}
          options={SORTS.map((x) => ({ value: x.value, label: t(x.key) }))} />
      </Sheet>

      <SettingsSheet
        open={sheet === "settings"}
        onOpenChange={(v) => setSheet(v ? "settings" : null)}
        settings={settings}
        onChange={changeSettings}
        user={github.user}
        codex={codex}
        models={models}
        update={update}
        progress={progress.codexUpdate ?? null}
        version={initial.version}
        summaryCount={Object.keys(summaries).length}
        onGithubLogout={() => {
          native.githubLogout();
          setGithub({ loggedIn: false, user: { login: "", avatar: "", name: "" } });
          setRepos([]);
          setSheet(null);
        }}
        onCodexLogin={() => setSheet("codexLogin")}
        onCodexLogout={() => native.codexLogout(newReq("codex-logout"))}
        onLoadModels={() => native.codexModels(newReq("codex-models"))}
        onCheckUpdate={() => native.codexCheckUpdate(newReq("codex-check"))}
        onUpdate={() => native.codexUpdate(newReq("codex-update"))}
        onClearSummaries={() => { native.clearSummaries(); setSummaries({}); }}
      />

      <Toasts items={toasts} dismiss={(id) => setToasts((x) => x.filter((y) => y.id !== id))} />
    </div>
    </I18nContext.Provider>
  );
}

function Chip({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      onClick={onClick}
      className={cn(
        "num h-8 shrink-0 whitespace-nowrap rounded-full border px-3 text-[12.5px] transition active:scale-95",
        active ? "border-ink bg-ink text-inv" : "border-line bg-card text-ink/80",
      )}
    >
      {children}
    </button>
  );
}

function Banner({ children }: { children: React.ReactNode }) {
  return <div className="rounded-2xl border border-line bg-card px-4 py-3">{children}</div>;
}

function HyperStatus({ hyper, laya }: { hyper: HyperState | null; laya: ModelStatus }) {
  const { t } = React.useContext(I18nContext);
  if (!laya.available) return <p className="px-1 py-1 text-[12.5px] text-muted">{t("hyperNoModel")}</p>;
  if (!hyper) return null;
  if (hyper.error) return <p className="px-1 py-1 text-[12.5px] text-muted">{t("hyperError", { e: hyper.error })}</p>;
  const done = Object.keys(hyper.scores).length;
  if (hyper.running) {
    return (
      <div className="num flex items-center gap-2 px-1 py-1 text-[12.5px] text-muted">
        <Spinner className="h-3.5 w-3.5" />
        {done === 0 ? (laya.loaded ? t("hyperRunning") : t("hyperLoading")) : t("hyperProgress", { d: done, n: hyper.candidates.length })}
      </div>
    );
  }
  if (hyper.candidates.length === 0) return null;
  return <p className="px-1 py-1 text-[12.5px] text-muted">{t("hyperDone")}</p>;
}

function Empty({ q, mode, repos, syncing, onHyper }: { q: string; mode: Mode; repos: number; syncing: boolean; onHyper: () => void }) {
  const { t } = React.useContext(I18nContext);
  if (repos === 0) {
    return <div className="py-24 text-center text-[14px] text-muted">{syncing ? t("emptyLoading") : t("emptyNone")}</div>;
  }
  if (!q) return <div className="py-24 text-center text-[14px] text-muted">{t("emptyFilter")}</div>;
  return (
    <div className="py-20 text-center">
      <p className="text-[14px] text-muted">{t("emptyQuery", { q })}</p>
      {mode === "normal" && (
        <Button variant="secondary" size="sm" className="mt-4" onClick={onHyper}>{t("tryHyper")}</Button>
      )}
    </div>
  );
}
