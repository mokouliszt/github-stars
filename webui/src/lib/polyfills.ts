/**
 * 古い Android WebView (Chrome < 110 相当) 向けのポリフィル。
 *
 * minSdk 26 の端末では WebView が数年前の版のままのことがある。
 * 依存パッケージが使っている比較的新しいビルトインをここで補う:
 *   - Array.prototype.toSorted / toReversed / with (Chrome 110+, Radix UI が使用)
 *   - Array.prototype.findLast / findLastIndex (97+)
 *   - Object.hasOwn (93+, react-markdown 系が使用)
 *   - Array/String.prototype.at (92+)
 *   - structuredClone (98+)
 *   - String.prototype.replaceAll (85+)
 *
 * どの import よりも先に評価される必要があるため、main.tsx の先頭で読み込む。
 */

/* eslint-disable @typescript-eslint/no-explicit-any */

const AP = Array.prototype as any;

if (!AP.toSorted) {
  AP.toSorted = function (cmp?: (a: any, b: any) => number) {
    return Array.prototype.slice.call(this).sort(cmp);
  };
}
if (!AP.toReversed) {
  AP.toReversed = function () {
    return Array.prototype.slice.call(this).reverse();
  };
}
if (!AP.with) {
  AP.with = function (i: number, v: any) {
    const copy = Array.prototype.slice.call(this);
    const n = copy.length;
    const idx = i < 0 ? n + i : i;
    if (idx < 0 || idx >= n) throw new RangeError("Invalid index");
    copy[idx] = v;
    return copy;
  };
}
if (!AP.findLast) {
  AP.findLast = function (fn: (v: any, i: number, a: any[]) => boolean, thisArg?: any) {
    for (let i = this.length - 1; i >= 0; i--) {
      if (fn.call(thisArg, this[i], i, this)) return this[i];
    }
    return undefined;
  };
}
if (!AP.findLastIndex) {
  AP.findLastIndex = function (fn: (v: any, i: number, a: any[]) => boolean, thisArg?: any) {
    for (let i = this.length - 1; i >= 0; i--) {
      if (fn.call(thisArg, this[i], i, this)) return i;
    }
    return -1;
  };
}
if (!AP.at) {
  AP.at = function (i: number) {
    const n = this.length;
    const idx = i < 0 ? n + i : i;
    return idx >= 0 && idx < n ? this[idx] : undefined;
  };
}
if (!(String.prototype as any).at) {
  (String.prototype as any).at = function (i: number) {
    const n = this.length;
    const idx = i < 0 ? n + i : i;
    return idx >= 0 && idx < n ? this.charAt(idx) : undefined;
  };
}
if (!(String.prototype as any).replaceAll) {
  (String.prototype as any).replaceAll = function (search: any, replace: any) {
    if (search instanceof RegExp) {
      if (!search.flags.includes("g")) throw new TypeError("replaceAll must be called with a global RegExp");
      return this.replace(search, replace);
    }
    return this.split(String(search)).join(
      typeof replace === "function" ? undefined as any : String(replace),
    );
  };
}
if (!(Object as any).hasOwn) {
  (Object as any).hasOwn = function (o: object, k: PropertyKey) {
    return Object.prototype.hasOwnProperty.call(o, k);
  };
}
if (typeof (globalThis as any).structuredClone !== "function") {
  (globalThis as any).structuredClone = function (v: any) {
    return v === undefined ? undefined : JSON.parse(JSON.stringify(v));
  };
}
if (typeof (globalThis as any).queueMicrotask !== "function") {
  (globalThis as any).queueMicrotask = function (fn: () => void) {
    Promise.resolve().then(fn);
  };
}

/**
 * それでも起動時に落ちた場合、真っ黒のまま固まるのが一番困る。
 * 最低限、何が起きたかを画面に出す。
 */
function showFatal(message: string) {
  const root = document.getElementById("root");
  if (!root || root.childElementCount > 0) return; // 描画済みなら黙る
  root.innerHTML =
    '<div style="padding:24px 20px;font-family:sans-serif;color:#ede8de;background:#0b0c0e;min-height:100vh;box-sizing:border-box">' +
    '<p style="font-size:15px;margin:0 0 8px">起動に失敗しました</p>' +
    '<p style="font-size:12px;color:#8b8171;word-break:break-all;margin:0">' +
    message.replace(/&/g, "&amp;").replace(/</g, "&lt;") +
    "</p></div>";
}

window.addEventListener("error", (e) => showFatal(String(e.message || e.error || "unknown error")));
window.addEventListener("unhandledrejection", (e) => showFatal("unhandled: " + String((e as any).reason)));

export {};
