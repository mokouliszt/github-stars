import * as React from "react";
import { Archive, Copy, ExternalLink, GitFork, Globe, RefreshCw, Share2, Star, Undo2 } from "lucide-react";
import type { Repo, SummaryBlock } from "@/lib/native";
import { useI18n } from "@/lib/i18n";
import { langColor } from "@/lib/langColors";
import { ago, cn, compact, date } from "@/lib/utils";
import { Button, Sheet, Spinner } from "./ui";

function Avatar({ src, name, size = 20 }: { src: string; name: string; size?: number }) {
  const [ok, setOk] = React.useState(true);
  return ok && src ? (
    <img
      src={`${src}${src.includes("?") ? "&" : "?"}s=${size * 3}`}
      alt=""
      width={size}
      height={size}
      loading="lazy"
      onError={() => setOk(false)}
      className="shrink-0 rounded-full bg-subtle"
      style={{ width: size, height: size }}
    />
  ) : (
    <div className="flex shrink-0 items-center justify-center rounded-full bg-subtle text-[10px] font-semibold text-muted" style={{ width: size, height: size }}>
      {name.slice(0, 1).toUpperCase()}
    </div>
  );
}

/**
 * [block] is the summary in the current app language (undefined = no summary in this language,
 * even if the other language has one).
 */
export const RepoCard = React.memo(function RepoCard({
  repo,
  block,
  match,
  dim,
  busy,
  onOpen,
}: {
  repo: Repo;
  block?: SummaryBlock;
  match?: number;
  dim?: boolean;
  /** A single-repository summary run is in progress. */
  busy?: boolean;
  onOpen: (id: string) => void;
}) {
  const { t } = useI18n();
  const text = block?.text || repo.desc;
  const tags = (block?.tags?.length ? block.tags : repo.topics || []).slice(0, 3);
  return (
    <button
      onClick={() => onOpen(repo.id)}
      className={cn(
        "block w-full rounded-2xl border border-line bg-card p-4 text-left transition active:scale-[0.99] active:bg-subtle/60",
        dim && "opacity-50",
      )}
    >
      <div className="flex items-center gap-2">
        <Avatar src={repo.avatar} name={repo.owner} />
        <div className="min-w-0 flex-1 truncate text-[14.5px] leading-tight">
          <span className="text-muted">{repo.owner}/</span>
          <span className="font-semibold">{repo.name}</span>
        </div>
        {repo.archived && <Archive className="h-3.5 w-3.5 shrink-0 text-muted" aria-label="archived" />}
        {busy && <Spinner className="h-3.5 w-3.5 shrink-0" />}
        {match != null && (
          <div className="flex shrink-0 items-center gap-1.5" aria-label={t("match", { p: Math.round(match * 100) })}>
            <div className="h-[3px] w-8 overflow-hidden rounded-full bg-ink/10">
              <div className="h-full rounded-full bg-ink" style={{ width: `${Math.round(match * 100)}%` }} />
            </div>
          </div>
        )}
      </div>

      <p className={cn("clamp-2 mt-2 text-[13.5px] leading-[1.55]", text ? "text-ink/85" : "text-muted")}>
        {text || t("noDescription")}
      </p>

      {tags.length > 0 && (
        <div className="mt-2.5 flex flex-wrap gap-1.5">
          {tags.map((tag) => (
            <span key={tag} className="rounded-md bg-subtle px-1.5 py-[2px] text-[11px] text-muted">{tag}</span>
          ))}
        </div>
      )}

      <div className="num mt-3 flex items-center gap-4 text-[12px] text-muted">
        {repo.lang && (
          <span className="flex items-center gap-1.5">
            <span className="h-2 w-2 rounded-full" style={{ background: langColor(repo.lang) }} />
            {repo.lang}
          </span>
        )}
        <span className="flex items-center gap-1"><Star className="h-3 w-3" />{compact(repo.stars)}</span>
        {repo.pushedAt && <span>{t("updatedAgo", { ago: ago(repo.pushedAt, t) })}</span>}
      </div>
    </button>
  );
});

export function RepoSheet({
  open,
  repo,
  block,
  canSummarize,
  busy,
  onClose,
  onResummarize,
  onRestore,
  onOpenUrl,
  onCopy,
  onShare,
}: {
  open: boolean;
  repo: Repo | null;
  block?: SummaryBlock;
  canSummarize: boolean;
  busy: boolean;
  onClose: () => void;
  onResummarize: (id: string) => void;
  onRestore: (id: string) => void;
  onOpenUrl: (url: string) => void;
  onCopy: (text: string) => void;
  onShare: (text: string) => void;
}) {
  const { t } = useI18n();
  if (!repo) return null;
  const text = block?.text;
  const labels = Array.from(new Set([...(block?.tags ?? []), ...(repo.topics ?? [])]));

  return (
    <Sheet
      open={open}
      onOpenChange={(v) => !v && onClose()}
      footer={
        <div className="flex gap-2">
          <Button variant="primary" size="lg" className="flex-1" onClick={() => onOpenUrl(repo.url)}>
            <ExternalLink className="h-4 w-4" /> {t("openOnGitHub")}
          </Button>
          <Button variant="secondary" size="lg" className="w-12 px-0" aria-label={t("share")} onClick={() => onShare(`${repo.fullName}\n${repo.url}`)}>
            <Share2 className="h-4 w-4" />
          </Button>
        </div>
      }
    >
      <div className="flex items-start gap-3">
        <Avatar src={repo.avatar} name={repo.owner} size={40} />
        <div className="min-w-0 flex-1">
          <div className="text-[13px] text-muted">{repo.owner}</div>
          <div className="selectable break-words text-[20px] font-semibold leading-tight tracking-tight">{repo.name}</div>
        </div>
        <button className="mt-1 rounded-full p-2 text-muted active:bg-subtle" aria-label={t("copyName")} onClick={() => onCopy(repo.fullName)}>
          <Copy className="h-4 w-4" />
        </button>
      </div>

      <div className="mt-5">
        <div className="mb-2 text-[12px] font-medium text-muted">{t("summary")}</div>
        {text ? (
          <p className="selectable text-[15px] leading-[1.7]">{text}</p>
        ) : (
          <p className="text-[14px] leading-relaxed text-muted">
            {t("noSummaryYet")}{canSummarize ? t("noSummaryCan") : t("noSummaryLogin")}
          </p>
        )}
        <div className="mt-3 flex flex-wrap items-center gap-x-5 gap-y-2">
          <button
            disabled={!canSummarize || busy}
            onClick={() => onResummarize(repo.id)}
            className="inline-flex items-center gap-1.5 text-[13px] text-muted active:text-ink disabled:opacity-40"
          >
            <RefreshCw className={cn("h-3.5 w-3.5", busy && "animate-spin")} />
            {busy ? t("creating") : text ? t("regenerate") : t("createSummary")}
          </button>
          {block?.prev && !busy && (
            <button onClick={() => onRestore(repo.id)} className="inline-flex items-center gap-1.5 text-[13px] text-muted active:text-ink">
              <Undo2 className="h-3.5 w-3.5" /> {t("restorePrev")}
            </button>
          )}
        </div>
      </div>

      {repo.desc && repo.desc !== text && (
        <div className="mt-5">
          <div className="mb-1.5 text-[12px] font-medium text-muted">{t("repoDescription")}</div>
          <p className="selectable text-[14px] leading-relaxed text-ink/85">{repo.desc}</p>
        </div>
      )}

      {labels.length > 0 && (
        <div className="mt-5 flex flex-wrap gap-1.5">
          {labels.map((l) => (
            <span key={l} className="rounded-lg bg-subtle px-2 py-1 text-[12px] text-muted">{l}</span>
          ))}
        </div>
      )}

      <div className="num mt-6 grid grid-cols-2 gap-px overflow-hidden rounded-2xl border border-line bg-line">
        <Stat label={t("statStars")} value={<span className="flex items-center gap-1"><Star className="h-3.5 w-3.5" />{compact(repo.stars)}</span>} />
        <Stat label={t("statForks")} value={<span className="flex items-center gap-1"><GitFork className="h-3.5 w-3.5" />{compact(repo.forks)}</span>} />
        <Stat label={t("statLanguage")} value={repo.lang ? <span className="flex items-center gap-1.5"><span className="h-2 w-2 rounded-full" style={{ background: langColor(repo.lang) }} />{repo.lang}</span> : "—"} />
        <Stat label={t("statLicense")} value={repo.license || "—"} />
        <Stat label={t("statStarred")} value={date(repo.starredAt)} />
        <Stat label={t("statPushed")} value={repo.pushedAt ? ago(repo.pushedAt, t) : "—"} />
      </div>

      {repo.homepage && (
        <button onClick={() => onOpenUrl(repo.homepage)} className="mt-4 flex w-full items-center gap-2 rounded-xl px-1 py-2 text-[14px] active:bg-subtle">
          <Globe className="h-4 w-4 text-muted" />
          <span className="truncate">{repo.homepage.replace(/^https?:\/\//, "")}</span>
        </button>
      )}
      {block?.model && (
        <p className="mt-4 text-[11.5px] text-muted">
          {t("summaryMeta", { model: block.model, date: date(new Date(block.at).toISOString()) })}
        </p>
      )}
    </Sheet>
  );
}

function Stat({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="bg-card px-4 py-3">
      <div className="text-[11.5px] text-muted">{label}</div>
      <div className="mt-0.5 text-[14px]">{value}</div>
    </div>
  );
}
