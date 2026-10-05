import * as React from "react";
import { Check, Copy, ExternalLink } from "lucide-react";
import { native } from "@/lib/native";
import { useI18n } from "@/lib/i18n";
import { Button, Sheet, Spinner } from "./ui";

export interface CodexLoginState {
  phase: "idle" | "browser" | "device";
  verificationUrl?: string;
  userCode?: string;
}

export function CodexLoginSheet({
  open,
  onOpenChange,
  state,
  onStart,
  onCancel,
}: {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  state: CodexLoginState;
  onStart: (device: boolean) => void;
  onCancel: () => void;
}) {
  const { t } = useI18n();
  const [copied, setCopied] = React.useState(false);
  React.useEffect(() => { if (!open) setCopied(false); }, [open]);

  return (
    <Sheet open={open} onOpenChange={onOpenChange} title={t("codexLoginTitle")}>
      <p className="text-[14px] leading-relaxed text-muted">{t("codexLoginBody")}</p>

      {state.phase === "idle" && (
        <div className="mt-6 space-y-2">
          <Button variant="primary" size="lg" className="w-full" onClick={() => onStart(false)}>{t("codexLoginBrowser")}</Button>
          <Button variant="secondary" size="lg" className="w-full" onClick={() => onStart(true)}>{t("codexLoginCode")}</Button>
          <p className="pt-2 text-[12px] leading-relaxed text-muted">{t("codexLoginHint")}</p>
        </div>
      )}

      {state.phase === "browser" && (
        <div className="mt-6">
          <div className="flex items-center gap-2 rounded-xl bg-subtle px-4 py-4 text-[14px]">
            <Spinner /> {t("codexLoginFinish")}
          </div>
          <Button variant="ghost" className="mt-3 w-full" onClick={onCancel}>{t("cancel")}</Button>
        </div>
      )}

      {state.phase === "device" && (
        <div className="mt-6">
          {state.userCode ? (
            <>
              <p className="text-[13px] text-muted">{t("codexEnterCode")}</p>
              <div className="selectable mt-2 rounded-xl bg-subtle py-4 text-center font-mono text-[26px] font-semibold tracking-[0.14em]">
                {state.userCode}
              </div>
              <Button
                variant="primary"
                size="lg"
                className="mt-3 w-full"
                onClick={() => {
                  native.copy(state.userCode!);
                  setCopied(true);
                  if (state.verificationUrl) native.openUrl(state.verificationUrl);
                }}
              >
                {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
                {t("codexCopyOpen")}
                <ExternalLink className="h-3.5 w-3.5 opacity-60" />
              </Button>
              <p className="mt-3 text-[12px] leading-relaxed text-muted">{t("codexDeviceHint")}</p>
            </>
          ) : (
            <div className="flex items-center gap-2 text-[14px] text-muted"><Spinner /> {t("codexGettingCode")}</div>
          )}
          <div className="mt-4 flex items-center justify-between text-[13px] text-muted">
            <span className="flex items-center gap-2"><Spinner /> {t("waitingApproval")}</span>
            <button className="active:text-ink" onClick={onCancel}>{t("cancel")}</button>
          </div>
        </div>
      )}
    </Sheet>
  );
}
