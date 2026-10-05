import * as React from "react";
import { Check, ChevronDown, Copy, ExternalLink, Star } from "lucide-react";
import { native } from "@/lib/native";
import { useI18n, type Lang } from "@/lib/i18n";
import { cn } from "@/lib/utils";
import { Button, Segmented, Spinner } from "./ui";

export interface DeviceCode { userCode: string; verificationUri: string }

export function ClientIdHelp({ open: initial = false }: { open?: boolean }) {
  const { t } = useI18n();
  const [open, setOpen] = React.useState(initial);
  return (
    <div className="mt-3">
      <button onClick={() => setOpen(!open)} className="flex items-center gap-1 text-[13px] text-muted active:text-ink">
        <ChevronDown className={cn("h-4 w-4 transition", open && "rotate-180")} /> {t("clientIdHow")}
      </button>
      {open && (
        <ol className="mt-2 list-decimal space-y-1.5 pl-5 text-[13px] leading-relaxed text-muted">
          <li>
            {t("clientIdStep1a")}
            <button className="text-ink underline underline-offset-2" onClick={() => native.openUrl("https://github.com/settings/applications/new")}>
              New OAuth App
            </button>
            {t("clientIdStep1b")}
          </li>
          <li>{t("clientIdStep2")}</li>
          <li>{t("clientIdStep3a")}<span className="text-ink">Enable Device Flow</span>{t("clientIdStep3b")}</li>
          <li>{t("clientIdStep4a")}<span className="text-ink">Client ID</span>{t("clientIdStep4b")}</li>
        </ol>
      )}
    </div>
  );
}

export function Onboarding({
  lang,
  onLang,
  clientIdSet,
  savedClientId,
  code,
  waiting,
  onSaveClientId,
  onLogin,
  onCancel,
}: {
  lang: Lang;
  onLang: (l: Lang) => void;
  clientIdSet: boolean;
  savedClientId: string;
  code: DeviceCode | null;
  waiting: boolean;
  onSaveClientId: (v: string) => void;
  onLogin: () => void;
  onCancel: () => void;
}) {
  const { t } = useI18n();
  const [cid, setCid] = React.useState(savedClientId);
  const [copied, setCopied] = React.useState(false);
  const step1Done = clientIdSet;

  return (
    <div className="flex min-h-full flex-col px-6 pt-safe pb-safe">
      <div className="flex justify-end pt-4">
        <Segmented size="sm" className="w-[150px]" value={lang} onChange={onLang}
          options={[{ value: "ja", label: "日本語" }, { value: "en", label: "English" }]} />
      </div>
      <div className="flex flex-1 flex-col justify-center py-8">
        <div className="mb-8 flex h-14 w-14 items-center justify-center rounded-2xl bg-ink text-inv">
          <Star className="h-7 w-7" fill="currentColor" strokeWidth={1.5} />
        </div>
        <h1 className="text-[28px] font-semibold tracking-tight">Github Stars</h1>
        <p className="mt-2 text-[15px] leading-relaxed text-muted">{t("tagline")}</p>

        <div className="mt-10 space-y-3">
          <div className="rounded-2xl border border-line bg-card p-4">
            <div className="flex items-center gap-3">
              <StepDot n={1} done={step1Done} />
              <div className="flex-1 text-[15px] font-medium">{t("clientIdTitle")}</div>
            </div>
            <div className="mt-3 flex gap-2">
              <input
                value={cid}
                onChange={(e) => setCid(e.target.value.trim())}
                placeholder="Ov23li..."
                autoCapitalize="off"
                autoCorrect="off"
                spellCheck={false}
                className="h-10 min-w-0 flex-1 rounded-xl border border-line bg-bg px-3 font-mono text-[14px] outline-none focus:border-ink"
              />
              <Button variant={step1Done && cid === savedClientId ? "secondary" : "primary"} disabled={!cid || cid === savedClientId}
                onClick={() => onSaveClientId(cid)}>
                {step1Done && cid === savedClientId ? <Check className="h-4 w-4" /> : t("save")}
              </Button>
            </div>
            <ClientIdHelp open={!step1Done} />
          </div>

          <div className={cn("rounded-2xl border border-line bg-card p-4", !step1Done && "opacity-50")}>
            <div className="flex items-center gap-3">
              <StepDot n={2} done={false} />
              <div className="flex-1 text-[15px] font-medium">{t("githubSignIn")}</div>
            </div>
            {!code ? (
              <Button variant="primary" size="lg" className="mt-4 w-full" disabled={!step1Done} loading={waiting} onClick={onLogin}>
                {t("getCode")}
              </Button>
            ) : (
              <div className="mt-4">
                <p className="text-[13px] text-muted">{t("enterCodeGitHub")}</p>
                <div className="selectable mt-2 rounded-xl bg-subtle py-4 text-center font-mono text-[28px] font-semibold tracking-[0.18em]">
                  {code.userCode}
                </div>
                <Button
                  variant="primary"
                  size="lg"
                  className="mt-3 w-full"
                  onClick={() => {
                    native.copy(code.userCode);
                    setCopied(true);
                    native.openUrl(code.verificationUri);
                  }}
                >
                  {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
                  {t("copyOpenGitHub")}
                  <ExternalLink className="h-3.5 w-3.5 opacity-60" />
                </Button>
                <div className="mt-4 flex items-center justify-between text-[13px] text-muted">
                  <span className="flex items-center gap-2"><Spinner /> {t("waitingApproval")}</span>
                  <button className="active:text-ink" onClick={onCancel}>{t("cancel")}</button>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>
      <p className="pb-6 text-center text-[12px] leading-relaxed text-muted">{t("privacyNote")}</p>
    </div>
  );
}

function StepDot({ n, done }: { n: number; done: boolean }) {
  return (
    <div className={cn("flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-[12px] font-semibold",
      done ? "bg-ink text-inv" : "bg-subtle text-muted")}>
      {done ? <Check className="h-3.5 w-3.5" /> : n}
    </div>
  );
}
