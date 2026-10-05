import * as React from "react";
import * as Dialog from "@radix-ui/react-dialog";
import * as SwitchPrimitive from "@radix-ui/react-switch";
import { Check, ChevronRight, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";

// ---------------------------------------------------------------- Button
type Variant = "primary" | "secondary" | "ghost" | "outline" | "danger";
type Size = "sm" | "md" | "lg";

export const Button = React.forwardRef<
  HTMLButtonElement,
  React.ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; size?: Size; loading?: boolean }
>(({ className, variant = "secondary", size = "md", loading, disabled, children, ...props }, ref) => (
  <button
    ref={ref}
    disabled={disabled || loading}
    className={cn(
      "inline-flex items-center justify-center gap-2 rounded-xl font-medium transition active:scale-[0.98] disabled:opacity-40 disabled:active:scale-100",
      size === "sm" && "h-8 px-3 text-[13px]",
      size === "md" && "h-10 px-4 text-sm",
      size === "lg" && "h-12 px-5 text-[15px]",
      variant === "primary" && "bg-ink text-inv",
      variant === "secondary" && "bg-subtle text-ink",
      variant === "ghost" && "text-ink active:bg-subtle",
      variant === "outline" && "border border-line bg-card text-ink",
      variant === "danger" && "bg-subtle text-red-600 dark:text-red-400",
      className,
    )}
    {...props}
  >
    {loading && <Loader2 className="h-4 w-4 animate-spin" />}
    {children}
  </button>
));
Button.displayName = "Button";

export function IconButton({
  className,
  label,
  children,
  ...props
}: React.ButtonHTMLAttributes<HTMLButtonElement> & { label: string }) {
  return (
    <button
      aria-label={label}
      className={cn(
        "inline-flex h-10 w-10 items-center justify-center rounded-full text-ink transition active:bg-subtle active:scale-95 disabled:opacity-40",
        className,
      )}
      {...props}
    >
      {children}
    </button>
  );
}

// ---------------------------------------------------------------- Sheet (bottom)
export function Sheet({
  open,
  onOpenChange,
  title,
  children,
  footer,
  className,
}: {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  title?: React.ReactNode;
  children: React.ReactNode;
  footer?: React.ReactNode;
  className?: string;
}) {
  const startY = React.useRef<number | null>(null);
  const [drag, setDrag] = React.useState(0);
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-black/40 data-[state=open]:animate-fade-in data-[state=closed]:animate-fade-out" />
        <Dialog.Content
          aria-describedby={undefined}
          onOpenAutoFocus={(e) => e.preventDefault()}
          style={{ transform: drag ? `translateY(${drag}px)` : undefined }}
          className={cn(
            "fixed inset-x-0 bottom-0 z-50 flex max-h-[88vh] flex-col rounded-t-[22px] border-t border-line bg-card outline-none",
            "data-[state=open]:animate-sheet-in data-[state=closed]:animate-sheet-out",
            className,
          )}
        >
          <div
            className="flex shrink-0 flex-col items-center pb-1 pt-2.5"
            onTouchStart={(e) => (startY.current = e.touches[0].clientY)}
            onTouchMove={(e) => {
              if (startY.current == null) return;
              setDrag(Math.max(0, e.touches[0].clientY - startY.current));
            }}
            onTouchEnd={() => {
              if (drag > 90) onOpenChange(false);
              startY.current = null;
              setDrag(0);
            }}
          >
            <div className="h-1 w-9 rounded-full bg-line" />
            {title ? (
              <Dialog.Title className="mt-3 w-full px-5 text-[17px] font-semibold tracking-tight">{title}</Dialog.Title>
            ) : (
              <Dialog.Title className="sr-only">sheet</Dialog.Title>
            )}
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto px-5 pb-4 pt-2">{children}</div>
          {footer && <div className="shrink-0 border-t border-line px-5 pt-3 pb-safe-sheet">{footer}</div>}
          {!footer && <div className="pb-safe-sheet" />}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

// ---------------------------------------------------------------- Switch
export function Switch({ checked, onCheckedChange, disabled }: { checked: boolean; onCheckedChange: (v: boolean) => void; disabled?: boolean }) {
  return (
    <SwitchPrimitive.Root
      checked={checked}
      onCheckedChange={onCheckedChange}
      disabled={disabled}
      className="relative h-[26px] w-[44px] shrink-0 rounded-full bg-line transition-colors data-[state=checked]:bg-ink disabled:opacity-40"
    >
      <SwitchPrimitive.Thumb className="block h-[22px] w-[22px] translate-x-[2px] rounded-full bg-white shadow transition-transform data-[state=checked]:translate-x-[20px] dark:data-[state=checked]:bg-black" />
    </SwitchPrimitive.Root>
  );
}

// ---------------------------------------------------------------- Segmented
export function Segmented<T extends string>({
  value,
  onChange,
  options,
  className,
  size = "md",
}: {
  value: T;
  onChange: (v: T) => void;
  options: { value: T; label: React.ReactNode }[];
  className?: string;
  size?: "sm" | "md";
}) {
  return (
    <div className={cn("flex rounded-xl bg-subtle p-[3px]", className)}>
      {options.map((o) => (
        <button
          key={o.value}
          onClick={() => onChange(o.value)}
          className={cn(
            "flex-1 whitespace-nowrap rounded-[10px] font-medium transition",
            size === "md" ? "h-8 px-3 text-[13px]" : "h-7 px-2.5 text-[12px]",
            o.value === value ? "bg-card text-ink shadow-sm dark:bg-line" : "text-muted",
          )}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

// ---------------------------------------------------------------- Rows / groups
export function Group({ title, children, hint }: { title?: string; children: React.ReactNode; hint?: React.ReactNode }) {
  return (
    <section className="mb-6">
      {title && <h3 className="mb-2 px-1 text-[12px] font-medium text-muted">{title}</h3>}
      <div className="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-card">{children}</div>
      {hint && <p className="mt-2 px-1 text-[12px] leading-relaxed text-muted">{hint}</p>}
    </section>
  );
}

export function Row({
  label,
  detail,
  right,
  onClick,
  chevron,
  stack,
}: {
  label: React.ReactNode;
  detail?: React.ReactNode;
  right?: React.ReactNode;
  onClick?: () => void;
  chevron?: boolean;
  stack?: boolean;
}) {
  const Comp: React.ElementType = onClick ? "button" : "div";
  return (
    <Comp
      onClick={onClick}
      className={cn(
        "flex w-full min-h-[52px] items-center gap-3 px-4 py-3 text-left",
        onClick && "active:bg-subtle",
        stack && "flex-col items-stretch",
      )}
    >
      <div className="min-w-0 flex-1">
        <div className="text-[15px]">{label}</div>
        {detail && <div className="mt-0.5 text-[12.5px] leading-snug text-muted">{detail}</div>}
      </div>
      {right && <div className={cn("shrink-0", stack && "w-full")}>{right}</div>}
      {chevron && <ChevronRight className="h-4 w-4 shrink-0 text-muted" />}
    </Comp>
  );
}

export function RadioList<T extends string>({
  value,
  onChange,
  options,
}: {
  value: T;
  onChange: (v: T) => void;
  options: { value: T; label: React.ReactNode; detail?: React.ReactNode }[];
}) {
  return (
    <div className="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-card">
      {options.map((o) => (
        <button key={o.value} onClick={() => onChange(o.value)} className="flex w-full items-center gap-3 px-4 py-3 text-left active:bg-subtle">
          <div className="min-w-0 flex-1">
            <div className="text-[15px]">{o.label}</div>
            {o.detail && <div className="mt-0.5 text-[12.5px] text-muted">{o.detail}</div>}
          </div>
          <Check className={cn("h-4 w-4 shrink-0", o.value === value ? "opacity-100" : "opacity-0")} />
        </button>
      ))}
    </div>
  );
}

// ---------------------------------------------------------------- Progress
export function ProgressBar({ value, className }: { value?: number; className?: string }) {
  const indeterminate = value == null || !isFinite(value);
  return (
    <div className={cn("h-[3px] w-full overflow-hidden rounded-full bg-line", className)}>
      {indeterminate ? (
        <div className="h-full w-2/5 animate-indeterminate rounded-full bg-ink" />
      ) : (
        <div className="h-full rounded-full bg-ink transition-[width] duration-300" style={{ width: `${Math.max(2, Math.min(100, value * 100))}%` }} />
      )}
    </div>
  );
}

export function Spinner({ className }: { className?: string }) {
  return <Loader2 className={cn("h-4 w-4 animate-spin text-muted", className)} />;
}

// ---------------------------------------------------------------- Toast
export interface ToastItem { id: number; text: string; action?: { label: string; run: () => void } }

export function Toasts({ items, dismiss }: { items: ToastItem[]; dismiss: (id: number) => void }) {
  return (
    <div className="pointer-events-none fixed inset-x-0 z-[60] flex flex-col items-center gap-2 px-4" style={{ bottom: "calc(var(--sab) + var(--ime) + 16px)" }}>
      {items.map((t) => (
        <div key={t.id} className="pointer-events-auto flex w-full max-w-md animate-toast-in items-center gap-3 rounded-2xl bg-ink px-4 py-3 text-[13.5px] text-inv shadow-lg">
          <span className="flex-1 leading-snug">{t.text}</span>
          {t.action && (
            <button
              className="shrink-0 text-[13px] font-semibold underline-offset-2 active:underline"
              onClick={() => { t.action!.run(); dismiss(t.id); }}
            >
              {t.action.label}
            </button>
          )}
        </div>
      ))}
    </div>
  );
}
