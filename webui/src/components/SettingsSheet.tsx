import * as React from "react";
import { ChevronLeft } from "lucide-react";
import type { CodexModel, CodexUpdate, GhUser, Progress, Settings } from "@/lib/native";
import { effortLabel, useI18n } from "@/lib/i18n";
import { date } from "@/lib/utils";
import { ClientIdHelp } from "./Onboarding";
import { Button, Group, RadioList, Row, Segmented, Sheet, Spinner, Switch } from "./ui";

export interface CodexAccount { checked: boolean; loggedIn: boolean; email: string; plan: string }

export function SettingsSheet({
  open,
  onOpenChange,
  settings,
  onChange,
  user,
  codex,
  models,
  update,
  progress,
  version,
  onGithubLogout,
  onCodexLogin,
  onCodexLogout,
  onLoadModels,
  onCheckUpdate,
  onUpdate,
  onClearSummaries,
  summaryCount,
}: {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  settings: Settings;
  onChange: (patch: Partial<Settings>) => void;
  user: GhUser;
  codex: CodexAccount;
  models: CodexModel[] | null;
  update: CodexUpdate;
  progress: Progress | null;
  version: string;
  onGithubLogout: () => void;
  onCodexLogin: () => void;
  onCodexLogout: () => void;
  onLoadModels: () => void;
  onCheckUpdate: () => void;
  onUpdate: () => void;
  onClearSummaries: () => void;
  summaryCount: number;
}) {
  const { t } = useI18n();
  const [picker, setPicker] = React.useState<null | "model" | "effort" | "sandbox">(null);
  const [cid, setCid] = React.useState(settings.githubClientId);
  const [confirmClear, setConfirmClear] = React.useState(false);
  const [confirmLogout, setConfirmLogout] = React.useState(false);

  React.useEffect(() => {
    if (open) {
      setPicker(null);
      setCid(settings.githubClientId);
      setConfirmClear(false);
      setConfirmLogout(false);
      if (codex.loggedIn) onLoadModels(); // always refresh: the list comes from Codex
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const sandboxes: { value: Settings["sandbox"]; label: string; detail: string }[] = [
    { value: "workspace-write", label: t("sbWrite"), detail: t("sbWriteDetail") },
    { value: "read-only", label: t("sbRead"), detail: t("sbReadDetail") },
    { value: "danger-full-access", label: t("sbFull"), detail: t("sbFullDetail") },
  ];

  const defaultModel = models?.find((m) => m.isDefault) ?? models?.[0];
  const pinned = settings.model ? models?.find((m) => m.id === settings.model) : undefined;
  const pinnedMissing = !!settings.model && !!models && !pinned;
  const selectedModel = pinned ?? defaultModel;
  const modelLabel = settings.model
    ? pinnedMissing ? t("modelUnavailable", { m: settings.model }) : pinned?.displayName ?? settings.model
    : defaultModel ? t("modelDefaultLabel", { name: defaultModel.displayName }) : t("codexDefault");
  const effortText = settings.effort
    ? effortLabel(t, settings.effort)
    : selectedModel?.defaultEffort ? t("effortDefaultLabel", { e: effortLabel(t, selectedModel.defaultEffort) }) : t("effortDefault");
  const updating = progress?.kind === "codexUpdate";
  const newer = !!update.latest && update.latest !== update.active && cmp(update.latest, update.active) > 0;

  const back = (
    <button onClick={() => setPicker(null)} className="mb-3 -ml-1 flex items-center gap-1 text-[14px] text-muted active:text-ink">
      <ChevronLeft className="h-4 w-4" /> {t("back")}
    </button>
  );

  return (
    <Sheet open={open} onOpenChange={onOpenChange} title={picker ? undefined : t("settings")}>
      {picker === "model" && (
        <div>
          {back}
          <h3 className="mb-3 text-[17px] font-semibold">{t("modelPickerTitle")}</h3>
          {!models ? (
            <div className="flex items-center gap-2 py-6 text-[14px] text-muted">
              {codex.loggedIn ? <><Spinner /> {t("modelLoading")}</> : t("modelNeedLogin")}
            </div>
          ) : (
            <>
              <RadioList
                value={settings.model}
                onChange={(v) => { onChange({ model: v, effort: "" }); setPicker(null); }}
                options={[
                  { value: "", label: t("modelDefault"), detail: defaultModel ? t("modelDefaultNow", { name: defaultModel.displayName }) : t("modelDefaultFollow") },
                  ...models.map((m) => ({ value: m.id, label: m.displayName, detail: m.description || m.id })),
                  ...(pinnedMissing ? [{ value: settings.model, label: t("modelUnavailable", { m: settings.model }), detail: t("modelUnavailableHint") }] : []),
                ]}
              />
              <p className="mt-3 px-1 text-[12px] leading-relaxed text-muted">{t("modelFromCodex")}</p>
            </>
          )}
        </div>
      )}

      {picker === "effort" && (
        <div>
          {back}
          <h3 className="mb-3 text-[17px] font-semibold">{t("effortTitle")}</h3>
          <RadioList
            value={settings.effort}
            onChange={(v) => { onChange({ effort: v }); setPicker(null); }}
            options={[
              { value: "", label: t("effortDefault"), detail: selectedModel?.defaultEffort ? t("effortDefaultNow", { e: effortLabel(t, selectedModel.defaultEffort) }) : t("effortFollow") },
              ...(selectedModel?.efforts ?? []).map((e) => ({ value: e, label: effortLabel(t, e) })),
            ]}
          />
          <p className="mt-3 px-1 text-[12px] leading-relaxed text-muted">{t("effortHint")}</p>
        </div>
      )}

      {picker === "sandbox" && (
        <div>
          {back}
          <h3 className="mb-3 text-[17px] font-semibold">{t("sandboxTitle")}</h3>
          <RadioList value={settings.sandbox} onChange={(v) => { onChange({ sandbox: v }); setPicker(null); }}
            options={sandboxes.map((s) => ({ value: s.value, label: s.label, detail: s.detail }))} />
          <p className="mt-3 px-1 text-[12px] leading-relaxed text-muted">{t("sandboxHint")}</p>
        </div>
      )}

      {!picker && (
        <div className="pt-1">
          <Group title={t("setLanguage")} hint={t("setLanguageHint")}>
            <Row label={t("setLanguageRow")} stack right={
              <Segmented value={settings.lang} onChange={(v) => onChange({ lang: v })}
                options={[{ value: "ja", label: "日本語" }, { value: "en", label: "English" }]} />
            } />
          </Group>

          <Group title={t("setAccount")}>
            <Row
              label={user.login ? `@${user.login}` : "GitHub"}
              detail="GitHub"
              right={
                confirmLogout ? (
                  <div className="flex gap-1.5">
                    <Button size="sm" variant="ghost" onClick={() => setConfirmLogout(false)}>{t("keep")}</Button>
                    <Button size="sm" variant="danger" onClick={onGithubLogout}>{t("signOut")}</Button>
                  </div>
                ) : (
                  <Button size="sm" variant="secondary" onClick={() => setConfirmLogout(true)}>{t("signOut")}</Button>
                )
              }
            />
            <Row
              label={codex.loggedIn ? codex.email || "ChatGPT" : "ChatGPT"}
              detail={!codex.checked ? t("setChecking") : codex.loggedIn ? `ChatGPT${codex.plan ? ` · ${codex.plan}` : ""}` : t("setNotSignedIn")}
              right={
                codex.loggedIn ? (
                  <Button size="sm" variant="secondary" onClick={onCodexLogout}>{t("signOut")}</Button>
                ) : (
                  <Button size="sm" variant="primary" onClick={onCodexLogin} disabled={!codex.checked}>{t("signIn")}</Button>
                )
              }
            />
          </Group>

          <Group title={t("setSummaries")} hint={t("setSummariesHint")}>
            <Row label={t("setDeleteAll")} detail={t("setDeleteAllDetail", { n: summaryCount })} right={
              confirmClear ? (
                <div className="flex gap-1.5">
                  <Button size="sm" variant="ghost" onClick={() => setConfirmClear(false)}>{t("keep")}</Button>
                  <Button size="sm" variant="danger" onClick={() => { onClearSummaries(); setConfirmClear(false); }}>{t("delete")}</Button>
                </div>
              ) : (
                <Button size="sm" variant="secondary" disabled={summaryCount === 0} onClick={() => setConfirmClear(true)}>{t("delete")}</Button>
              )
            } />
          </Group>

          <Group title={t("setCodex")} hint={t("setCodexHint")}>
            <Row label={t("setModel")} detail={modelLabel} chevron onClick={() => { if (codex.loggedIn && !models) onLoadModels(); setPicker("model"); }} />
            <Row label={t("setEffort")} detail={effortText} chevron onClick={() => setPicker("effort")} />
            <Row label={t("setWebSearch")} detail={t("setWebSearchDetail")} right={
              <Switch checked={settings.webSearch} onCheckedChange={(v) => onChange({ webSearch: v })} />
            } />
            <Row label={t("setSandbox")} detail={sandboxes.find((s) => s.value === settings.sandbox)?.label} chevron onClick={() => setPicker("sandbox")} />
          </Group>

          <Group title={t("setVersion")} hint={update.checkedAt ? t("setLastChecked", { d: date(new Date(update.checkedAt).toISOString()) }) : undefined}>
            <Row
              label={`v${update.active}`}
              detail={update.active === update.bundled ? t("setBundled") : t("setUpdated", { b: update.bundled })}
              right={
                newer ? (
                  <Button size="sm" variant="primary" loading={updating} onClick={onUpdate}>{t("setUpdateTo", { v: update.latest })}</Button>
                ) : (
                  <Button size="sm" variant="secondary" loading={updating} onClick={onCheckUpdate}>{t("setCheck")}</Button>
                )
              }
            />
            {updating && progress && progress.total > 0 && (
              <div className="px-4 pb-3 text-[12px] text-muted">
                {t("setDownloading", { p: Math.round((progress.done / progress.total) * 100) })}
              </div>
            )}
            <Row label={t("setAutoUpdate")} stack right={
              <Segmented value={settings.codexAutoUpdate} onChange={(v) => onChange({ codexAutoUpdate: v })}
                options={[{ value: "wifi", label: t("setWifi") }, { value: "always", label: t("setAlways") }, { value: "off", label: t("setNever") }]} />
            } />
          </Group>

          <Group title={t("setHyper")} hint={t("setHyperHint")}>
            <Row label={t("setHyperCandidates")} detail={t("setHyperCandidatesDetail")} stack right={
              <Segmented value={String(settings.hyperCandidates) as "10" | "20" | "30"}
                onChange={(v) => onChange({ hyperCandidates: Number(v) })}
                options={[{ value: "10", label: "10" }, { value: "20", label: "20" }, { value: "30", label: "30" }]} />
            } />
          </Group>

          <Group title={t("setOAuth")} hint={t("setOAuthHint")}>
            <div className="px-4 py-3">
              <div className="flex gap-2">
                <input
                  value={cid}
                  onChange={(e) => setCid(e.target.value.trim())}
                  placeholder="Client ID"
                  autoCapitalize="off"
                  autoCorrect="off"
                  spellCheck={false}
                  className="h-10 min-w-0 flex-1 rounded-xl border border-line bg-bg px-3 font-mono text-[14px] outline-none focus:border-ink"
                />
                <Button variant="secondary" disabled={!cid || cid === settings.githubClientId} onClick={() => onChange({ githubClientId: cid })}>{t("save")}</Button>
              </div>
              <ClientIdHelp />
            </div>
          </Group>

          <Group title={t("setAbout")}>
            <Row label={t("setAppVersion")} right={<span className="num text-[14px] text-muted">{version}</span>} />
            <div className="px-4 py-3 text-[12px] leading-relaxed text-muted">{t("setLicenses")}</div>
          </Group>
        </div>
      )}
    </Sheet>
  );
}

function cmp(a: string, b: string): number {
  const x = a.split("-")[0].split(".").map(Number);
  const y = b.split("-")[0].split(".").map(Number);
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    const d = (x[i] || 0) - (y[i] || 0);
    if (d) return d;
  }
  return 0;
}
