import type { Repo, Summary } from "./native";

/** NFKC, lower case, katakana → hiragana, so 「ダウンロード」 matches 「だうんろーど」 and full-width text. */
export function normalize(s: string): string {
  return (s || "")
    .normalize("NFKC")
    .toLowerCase()
    .replace(/[\u30a1-\u30f6]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0x60));
}

const CJK = /[\u3040-\u30ff\u3400-\u9fff\uf900-\ufaff]/;

export interface Indexed {
  id: string;
  name: string;
  owner: string;
  full: string;
  lang: string;
  labels: string[]; // topics + generated tags
  text: string; // description + summaries
  words: string[]; // latin words for typo tolerance
}

/** Only the current language's summary and tags are searchable, matching what the list shows. */
export function buildIndex(repos: Repo[], summaries: Record<string, Summary>, lang: "ja" | "en"): Map<string, Indexed> {
  const m = new Map<string, Indexed>();
  for (const r of repos) {
    const s = summaries[r.id]?.[lang];
    const labels = [...(r.topics || []), ...(s?.tags || [])].map(normalize);
    const text = normalize([r.desc, s?.text].filter(Boolean).join(" \n "));
    const words = new Set<string>();
    for (const src of [r.name, r.owner, ...labels, text]) {
      for (const w of normalize(src).split(/[^a-z0-9]+/)) if (w.length >= 4) words.add(w);
    }
    m.set(r.id, {
      id: r.id,
      name: normalize(r.name),
      owner: normalize(r.owner),
      full: normalize(r.fullName),
      lang: normalize(r.lang),
      labels,
      text,
      words: [...words],
    });
  }
  return m;
}

interface Term {
  norm: string;
  cjk: boolean;
  grams: string[];
}

function bigrams(s: string): string[] {
  const chars = [...s];
  if (chars.length < 2) return [s];
  const out: string[] = [];
  for (let i = 0; i < chars.length - 1; i++) out.push(chars[i] + chars[i + 1]);
  return out;
}

const STOP = new Set(["の", "を", "が", "は", "に", "で", "と", "や", "a", "an", "the", "for", "to", "of", "and", "with"]);

export function terms(query: string): Term[] {
  const q = normalize(query).trim();
  if (!q) return [];
  return q
    .split(/[\s,、。・/|]+/)
    .filter((t) => t && !STOP.has(t))
    .map((t) => ({ norm: t, cjk: CJK.test(t), grams: CJK.test(t) ? bigrams(t) : [] }));
}

/** Levenshtein distance with an early exit once it exceeds [max]. */
function within(a: string, b: string, max: number): boolean {
  if (Math.abs(a.length - b.length) > max) return false;
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    const cur = [i];
    let best = i;
    for (let j = 1; j <= b.length; j++) {
      const v = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
      cur.push(v);
      if (v < best) best = v;
    }
    if (best > max) return false;
    prev = cur;
  }
  return prev[b.length] <= max;
}

function termScore(ix: Indexed, t: Term): number {
  const q = t.norm;
  let s = 0;
  if (ix.name === q) s = Math.max(s, 12);
  else if (ix.name.startsWith(q)) s = Math.max(s, 8);
  else if (ix.name.includes(q)) s = Math.max(s, 6);
  if (ix.owner === q) s = Math.max(s, 5);
  else if (ix.full.includes(q)) s = Math.max(s, 3);
  if (ix.lang && ix.lang === q) s = Math.max(s, 4);
  for (const l of ix.labels) {
    if (l === q) { s = Math.max(s, 6); break; }
    if (l.includes(q)) s = Math.max(s, 3.5);
  }
  if (ix.text.includes(q)) s = Math.max(s, 2.5);
  if (s > 0) return s;

  if (t.cjk && t.grams.length > 1) {
    // Partial Japanese match: share of the query's bigrams found in labels/text.
    const hay = ix.labels.join(" ") + " " + ix.text + " " + ix.name;
    let hit = 0;
    for (const g of t.grams) if (hay.includes(g)) hit++;
    const ratio = hit / t.grams.length;
    if (ratio >= 0.5) return 2 * ratio;
    return 0;
  }
  if (!t.cjk && q.length >= 4) {
    // Typo tolerance for latin words.
    const max = q.length >= 7 ? 2 : 1;
    for (const w of ix.words) if (within(q, w, max)) return 1.5;
  }
  return 0;
}

export interface Hit { id: string; score: number }

/**
 * mode "and": every term must match somewhere (normal search).
 * mode "or": any term contributes (candidate generation for hyper fuzzy search).
 */
export function search(index: Map<string, Indexed>, query: string, mode: "and" | "or"): Hit[] {
  const ts = terms(query);
  if (ts.length === 0) return [];
  const out: Hit[] = [];
  for (const ix of index.values()) {
    let total = 0;
    let matched = 0;
    for (const t of ts) {
      const s = termScore(ix, t);
      if (s > 0) { matched++; total += s; } else if (mode === "and") { total = 0; break; }
    }
    if (total > 0) out.push({ id: ix.id, score: total * (mode === "or" ? 0.6 + 0.4 * (matched / ts.length) : 1) });
  }
  out.sort((a, b) => b.score - a.score);
  return out;
}
