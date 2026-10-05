import * as React from "react";
import type { Progress } from "@/lib/native";
import { useI18n } from "@/lib/i18n";
import { Button, ProgressBar, RadioList, Sheet } from "./ui";

type Mode = "missing" | "all";

/**
 * Summaries are only created from here (or per repository from the detail sheet), always in the
 * current app language. "Regenerate all" overwrites existing text, so it is never preselected
 * and needs a second tap.
 */
export function SummarizeSheet({
  open,
  onOpenChange,
  total,
  missing,
  progress,
  loggedIn,
  modelLabel,
  batchSize,
  onStart,
  onCancel,
  onLogin,
}: {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  total: number;
  missing: number;
  progress: Progress | null;
  loggedIn: boolean;
  modelLabel: string;
  batchSize: number;
  onStart: (mode: Mode) => void;
  onCancel: () => void;
  onLogin: () => void;
}) {
  const { t } = useI18n();
  const [mode, setMode] = React.useState<Mode>("missing");
  const [confirming, setConfirming] = React.useState(false);
  React.useEffect(() => {
    if (open) { setMode("missing"); setConfirming(false); }
  }, [open]);
  React.useEffect(() => setConfirming(false), [mode]);

  const running = progress?.kind === "summarize";
  const count = mode === "missing" ? missing : total;
  const requests = Math.ceil(count / Math.max(1, batchSize));

  let footer: React.ReactNode;
  if (running) {
    footer = <Button variant="secondary" size="lg" className="w-full" onClick={onCancel}>{t("sumStop")}</Button>;
  } else if (!loggedIn) {
    footer = <Button variant="primary" size="lg" className="w-full" onClick={onLogin}>{t("codexLoginTitle")}</Button>;
  } else if (mode === "all" && !confirming) {
    footer = (
      <Button variant="secondary" size="lg" className="w-full" disabled={count === 0} onClick={() => setConfirming(true)}>
        {t("sumRegenAllAsk", { n: count })}
      </Button>
    );
  } else if (mode === "all") {
    footer = (
      <div className="flex gap-2">
        <Button variant="ghost" size="lg" className="flex-1" onClick={() => setConfirming(false)}>{t("keep")}</Button>
        <Button variant="primary" size="lg" className="flex-[2]" onClick={() => onStart("all")}>{t("sumOverwrite")}</Button>
      </div>
    );
  } else {
    footer = (
      <Button variant="primary" size="lg" className="w-full" disabled={count === 0} onClick={() => onStart("missing")}>
        {count === 0 ? t("sumNothing") : t("sumCreateN", { n: count })}
      </Button>
    );
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange} title={t("sumTitle")} footer={footer}>
      {running && progress ? (
        <div className="py-2">
          <div className="num flex items-baseline justify-between">
            <span className="text-[28px] font-semibold tracking-tight">{progress.done}<span className="text-[16px] text-muted"> / {progress.total}</span></span>
            {!!progress.failed && <span className="text-[13px] text-muted">{t("sumFailed", { n: progress.failed })}</span>}
          </div>
          <ProgressBar className="mt-3" value={progress.total ? progress.done / progress.total : undefined} />
          <p className="mt-4 text-[13px] leading-relaxed text-muted">{t("sumRunningNote")}</p>
        </div>
      ) : (
        <>
          <RadioList
            value={mode}
            onChange={setMode}
            options={[
              { value: "missing", label: t("sumOptMissing"), detail: t("sumOptMissingDetail", { n: missing }) },
              { value: "all", label: t("sumOptAll"), detail: t("sumOptAllDetail", { n: total }) },
            ]}
          />
          {mode === "all" && confirming && (
            <p className="mt-4 rounded-xl bg-subtle px-4 py-3 text-[13px] leading-relaxed">{t("sumConfirm", { n: total })}</p>
          )}
          <p className="mt-4 text-[12.5px] leading-relaxed text-muted">
            {t("sumNote")}{count > 0 && <> {t("sumRequests", { r: requests, b: batchSize })}</>} {t("sumModel", { m: modelLabel })}
          </p>
        </>
      )}
    </Sheet>
  );
}
