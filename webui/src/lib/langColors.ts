// Subset of GitHub linguist colours. Used only for the small language dot.
const C: Record<string, string> = {
  TypeScript: "#3178c6", JavaScript: "#f1e05a", Python: "#3572A5", Java: "#b07219", Kotlin: "#A97BFF",
  Swift: "#F05138", Go: "#00ADD8", Rust: "#dea584", "C++": "#f34b7d", C: "#555555", "C#": "#178600",
  Ruby: "#701516", PHP: "#4F5D95", Dart: "#00B4AB", Shell: "#89e051", HTML: "#e34c26", CSS: "#663399",
  Vue: "#41b883", Svelte: "#ff3e00", Lua: "#000080", Scala: "#c22d40", Elixir: "#6e4a7e", Haskell: "#5e5086",
  "Jupyter Notebook": "#DA5B0B", Zig: "#ec915c", Nim: "#ffc200", OCaml: "#ef7a08", "Objective-C": "#438eff",
  R: "#198CE7", Julia: "#a270ba", MDX: "#fcb32c", Astro: "#ff5a03", Nix: "#7e7eff", Clojure: "#db5855",
  Perl: "#0298c3", PowerShell: "#012456", Dockerfile: "#384d54", Makefile: "#427819", TeX: "#3D6117",
  Assembly: "#6E4C13", Solidity: "#AA6746", Elm: "#60B5CC", "F#": "#b845fc", Erlang: "#B83998",
  "Vim Script": "#199f4b", "Emacs Lisp": "#c065db", HCL: "#844FBA", GDScript: "#355570", Verilog: "#b2b7f8",
};
export const langColor = (l: string) => C[l] ?? "#9ca3af";
