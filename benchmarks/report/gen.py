#!/usr/bin/env python3
import json
import glob
import os
import statistics
import sys

BENCH = sys.argv[1]
OUT = sys.argv[2]
os.makedirs(OUT, exist_ok=True)

CONFIGS = {
    "s1-async": "Scale 1, asynchronous commits",
    "s1-sync": "Scale 1, synchronous commits",
    "s4-async": "Scale 4, asynchronous commits",
}
ENGINES = ("hstore", "hypergraphdb")
KEYS = {"s1-async": "A", "s1-sync": "B", "s4-async": "C"}
PCT = {"p50": "med", "p99": "ptwo", "p99.9": "pthree", "max": "max"}

THROUGHPUT = [
    ("ingest.nodes", "Insert nodes (1,000 per txn)"),
    ("ingest.edges", "Insert hyperedges (1,000 per txn)"),
    ("write.update", "Update node values (1,000 per txn)"),
    ("latency.commit", "Single-update transactions"),
    ("churn.delete", "Delete hyperedges (1,000 per txn)"),
    ("churn.remove", "Remove one member (1,000 per txn)"),
    ("large.ingest", "Build one giant hyperedge"),
    ("read.incidence", "Incidence set of a node"),
    ("read.members", "Members of a hyperedge"),
    ("read.comembership", "Co-membership count of a pair"),
    ("read.twohop", "Two-hop neighbourhood"),
    ("read.incidence.parallel", "Incidence sets, 8 threads"),
    ("latency.read", "One lookup per read txn"),
    ("read.incidence.cold", "Incidence sets after reopen"),
    ("read.incidence.churned", "Incidence sets after deletes"),
    ("large.scan", "Scan the giant hyperedge"),
    ("large.probe", "Membership probe, giant hyperedge"),
    ("mixed.read", "Reads beside a 1,000 updates/s writer"),
]
TIMES = [("reopen", "Clean reopen"), ("recover", "Open after SIGKILL")]
SIZES = [("disk", "After load"), ("disk.churned", "After deletes"), ("disk.compacted", "After compaction")]
LATENCY = [("latency.read", "Read transaction"), ("latency.commit", "Commit"),
           ("mixed.read", "Read beside writer"), ("mixed.write", "Commit beside readers"),
           ("compaction.baseline", "Commit, no compaction"), ("compaction", "Commit during compaction")]
WRITES = ["ingest.nodes", "ingest.edges", "write.update", "latency.commit", "mixed.write",
          "large.ingest", "churn.delete", "churn.remove"]
READS_IO = ["read.incidence.cold"]


def load(cfg):
    runs = {}
    for engine in ENGINES:
        files = sorted(glob.glob(f"{BENCH}/{cfg}/{engine}-*.json"))
        runs[engine] = [json.load(open(f)) for f in files]
    return runs


def results(run):
    return {r["workload"]: r for r in run["results"]}


def series(runs, workload, metric):
    values = []
    for run in runs:
        r = results(run).get(workload)
        if r is not None:
            v = metric(r)
            if v is not None:
                values.append(v)
    return values


def ops(r):
    return r["operations"] / (r["millis"] / 1000.0)


def med(values):
    return statistics.median(values) if values else None


def fmt_rate(v):
    if v >= 1e9:
        return f"{v / 1e9:.2f}\\,G"
    if v >= 1e6:
        return f"{v / 1e6:.2f}\\,M"
    if v >= 1e3:
        return f"{v / 1e3:.1f}\\,k"
    return f"{v:.0f}"


def fmt_int(v):
    return f"{v:,.0f}".replace(",", "{,}")


def fmt_us(v):
    if v >= 1000:
        return f"{v / 1000:.2f}\\,ms"
    if v >= 10:
        return f"{v:.0f}\\,\\textmu s"
    return f"{v:.1f}\\,\\textmu s"


def fmt_bytes(v):
    if v < 0:
        return "$-$" + fmt_bytes(-v)
    if v >= 1 << 30:
        return f"{v / (1 << 30):.2f}\\,GiB"
    if v >= 1 << 20:
        return f"{v / (1 << 20):.1f}\\,MiB"
    if v >= 1 << 10:
        return f"{v / (1 << 10):.1f}\\,KiB"
    return f"{v:.0f}\\,B"


def fmt_ratio(v):
    if v is None:
        return "--"
    if v >= 100:
        return f"{v:.0f}$\\times$"
    if v >= 10:
        return f"{v:.1f}$\\times$"
    return f"{v:.2f}$\\times$"


def agree(runs, workload):
    sums = set()
    for engine in ENGINES:
        for run in runs[engine]:
            r = results(run).get(workload)
            if r is None:
                return "--"
            if r.get("checked") is False:
                return "n/a"
            sums.add(r["checksum"])
    return "yes" if len(sums) == 1 else "\\textbf{no}"


def rng(values, f):
    return f"{f(min(values))}--{f(max(values))}"


macros = {}


def macro(name, value):
    macros[name] = value


def main_table(cfg, runs):
    rows = []
    for workload, label in THROUGHPUT:
        h = series(runs["hstore"], workload, ops)
        g = series(runs["hypergraphdb"], workload, ops)
        if not h or not g:
            continue
        ratio = med(h) / med(g)
        rows.append((workload, label, fmt_rate(med(h)), fmt_rate(med(g)), ratio, agree(runs, workload)))
    for workload, label in TIMES:
        h = series(runs["hstore"], workload, lambda r: r["millis"])
        g = series(runs["hypergraphdb"], workload, lambda r: r["millis"])
        if not h or not g:
            continue
        rows.append((workload, label, fmt_int(med(h)) + "\\,ms", fmt_int(med(g)) + "\\,ms", med(g) / med(h), agree(runs, workload)))
    for workload, label in SIZES:
        h = series(runs["hstore"], workload, lambda r: r["operations"])
        g = series(runs["hypergraphdb"], workload, lambda r: r["operations"])
        if not h or not g:
            continue
        rows.append((workload, "Disk, " + label.lower(), fmt_bytes(med(h)), fmt_bytes(med(g)), med(g) / med(h), "n/a"))
    return rows


def write_main(cfg, rows):
    key = KEYS[cfg]
    with open(f"{OUT}/main-{cfg}.tex", "w") as f:
        f.write("\\begin{tabular}{@{}llrrrc@{}}\n\\toprule\n")
        f.write("Workload & Operation & HStore & HyperGraphDB & Ratio & Agree \\\\\n\\midrule\n")
        section = None
        for workload, label, h, g, ratio, ok in rows:
            kind = "write" if workload in ("ingest.nodes", "ingest.edges", "write.update", "latency.commit", "churn.delete", "churn.remove", "large.ingest") \
                else "time" if workload in ("reopen", "recover") else "size" if workload.startswith("disk") else "read"
            if section is not None and kind != section:
                f.write("\\midrule\n")
            section = kind
            win = "\\textbf{" + fmt_ratio(ratio) + "}" if ratio >= 1 else fmt_ratio(ratio)
            f.write(f"\\texttt{{{workload}}} & {label} & {h} & {g} & {win} & {ok} \\\\\n")
        f.write("\\bottomrule\n\\end{tabular}\n")
    for workload, label, h, g, ratio, ok in rows:
        macro(f"ratio{key}{workload.replace('.', '')}", fmt_ratio(ratio))
        macro(f"h{key}{workload.replace('.', '')}", h)
        macro(f"g{key}{workload.replace('.', '')}", g)


def write_ratio_figure(cfg, rows):
    with open(f"{OUT}/ratios-{cfg}.tex", "w") as f:
        names = [r[0] for r in rows]
        f.write("\\def\\ratiosymbols{" + ",".join(names) + "}\n")
        f.write("\\def\\ratiocoords{" + " ".join(f"({r[4]:.4f},{r[0]})" for r in rows) + "}\n")


def latency_table(cfg, runs):
    key = KEYS[cfg]
    with open(f"{OUT}/latency-{cfg}.tex", "w") as f:
        f.write("\\begin{tabular}{@{}llrrrrrrrr@{}}\n\\toprule\n")
        f.write(" & & \\multicolumn{4}{c}{HStore} & \\multicolumn{4}{c}{HyperGraphDB} \\\\\n")
        f.write("\\cmidrule(lr){3-6}\\cmidrule(l){7-10}\n")
        f.write("Workload & Operation & p50 & p99 & p99.9 & max & p50 & p99 & p99.9 & max \\\\\n\\midrule\n")
        for workload, label in LATENCY:
            cells = []
            for engine in ENGINES:
                for p in ("p50", "p99", "p99.9", "max"):
                    v = series(runs[engine], workload, lambda r, p=p: r.get("latency", {}).get(p))
                    cells.append(fmt_us(med(v)) if v else "--")
                    if v:
                        macro(f"lat{key}{engine.replace('hypergraphdb', 'hgdb')}{workload.replace('.', '')}{PCT[p]}", fmt_us(med(v)))
            if all(c == "--" for c in cells):
                continue
            f.write(f"\\texttt{{{workload}}} & {label} & " + " & ".join(cells) + " \\\\\n")
        f.write("\\bottomrule\n\\end{tabular}\n")
    with open(f"{OUT}/cdf-{cfg}.tex", "w") as f:
        for workload in ("latency.read", "latency.commit", "mixed.write"):
            for engine in ENGINES:
                pts = []
                for i, p in enumerate(("p50", "p99", "p99.9", "max")):
                    v = series(runs[engine], workload, lambda r, p=p: r.get("latency", {}).get(p))
                    if v:
                        pts.append(f"({i},{med(v):.4f})")
                name = f"lat{workload.replace('.', '')}{engine}"
                f.write(f"\\def\\{name.replace('hypergraphdb', 'hgdb')}{{{' '.join(pts)}}}\n")


def resources_table(cfg, runs):
    key = KEYS[cfg]
    rows = []
    for workload in WRITES:
        cells = []
        for engine in ENGINES:
            per = series(runs[engine], workload, lambda r: r["bytesWritten"] / r["operations"] if r.get("bytesWritten") is not None else None)
            amp = series(runs[engine], workload, lambda r: r["bytesWritten"] / r["logicalWritten"] if r.get("logicalWritten") else None)
            cells.append((med(per), med(amp)))
        if cells[0][0] is None or cells[1][0] is None:
            continue
        rows.append((workload, cells))
        macro(f"bpo{key}hstore{workload.replace('.', '')}", fmt_bytes(cells[0][0]))
        macro(f"bpo{key}hgdb{workload.replace('.', '')}", fmt_bytes(cells[1][0]))
    with open(f"{OUT}/writes-{cfg}.tex", "w") as f:
        f.write("\\begin{tabular}{@{}lrrrrr@{}}\n\\toprule\n")
        f.write(" & \\multicolumn{2}{c}{Bytes written per operation} & \\multicolumn{2}{c}{Write amplification} & \\\\\n")
        f.write("\\cmidrule(lr){2-3}\\cmidrule(lr){4-5}\n")
        f.write("Workload & HStore & HyperGraphDB & HStore & HyperGraphDB & Ratio \\\\\n\\midrule\n")
        for workload, cells in rows:
            (hb, ha), (gb, ga) = cells
            f.write(f"\\texttt{{{workload}}} & {fmt_bytes(hb)} & {fmt_bytes(gb)} & {ha:.1f}$\\times$ & {ga:.1f}$\\times$ & \\textbf{{{fmt_ratio(gb / hb)}}} \\\\\n")
        f.write("\\bottomrule\n\\end{tabular}\n")
    with open(f"{OUT}/writes-fig-{cfg}.tex", "w") as f:
        f.write("\\def\\writesymbols{" + ",".join(w for w, _ in rows) + "}\n")
        f.write("\\def\\writeh{" + " ".join(f"({w},{c[0][0]:.1f})" for w, c in rows) + "}\n")
        f.write("\\def\\writeg{" + " ".join(f"({w},{c[1][0]:.1f})" for w, c in rows) + "}\n")

    with open(f"{OUT}/memory-{cfg}.tex", "w") as f:
        f.write("\\begin{tabular}{@{}lrrrr@{}}\n\\toprule\n")
        f.write(" & \\multicolumn{2}{c}{Heap allocated per operation} & \\multicolumn{2}{c}{GC pause (ms)} \\\\\n")
        f.write("\\cmidrule(lr){2-3}\\cmidrule(l){4-5}\n")
        f.write("Workload & HStore & HyperGraphDB & HStore & HyperGraphDB \\\\\n\\midrule\n")
        for workload, _ in THROUGHPUT + TIMES:
            a = [series(runs[e], workload, lambda r: r["allocated"] / r["operations"] if r.get("allocated") is not None else None) for e in ENGINES]
            g = [series(runs[e], workload, lambda r: r.get("gcMillis")) for e in ENGINES]
            if not a[0] or not a[1]:
                continue
            f.write(f"\\texttt{{{workload}}} & {fmt_bytes(med(a[0]))} & {fmt_bytes(med(a[1]))} & {med(g[0]):.0f} & {med(g[1]):.0f} \\\\\n")
            macro(f"alloc{key}hstore{workload.replace('.', '')}", fmt_bytes(med(a[0])))
            macro(f"alloc{key}hgdb{workload.replace('.', '')}", fmt_bytes(med(a[1])))
        f.write("\\bottomrule\n\\end{tabular}\n")

    retained = [med([run["retainedHeap"] for run in runs[e]]) for e in ENGINES]
    total = [med([run["bytesWritten"] for run in runs[e]]) for e in ENGINES]
    macro(f"retained{key}hstore", fmt_bytes(retained[0]))
    macro(f"retained{key}hgdb", fmt_bytes(retained[1]))
    macro(f"totalwritten{key}hstore", fmt_bytes(total[0]))
    macro(f"totalwritten{key}hgdb", fmt_bytes(total[1]))
    for e, short in zip(ENGINES, ("hstore", "hgdb")):
        comp = series(runs[e], "compaction", lambda r: r.get("compactionMillis"))
        recl = series(runs[e], "compaction", lambda r: r.get("reclaimed"))
        lost = series(runs[e], "recover", lambda r: r.get("lost"))
        ack = series(runs[e], "recover", lambda r: r.get("acknowledged"))
        if comp:
            macro(f"compms{key}{short}", fmt_int(med(comp)) + "\\,ms")
        if recl:
            macro(f"reclaimed{key}{short}", fmt_bytes(med(recl)))
        if lost:
            macro(f"lost{key}{short}", f"{max(lost):.0f}")
            macro(f"ack{key}{short}", fmt_int(med(ack)))
        mix = series(runs[e], "mixed.write", ops)
        if mix:
            macro(f"mixmin{key}{short}", fmt_int(min(mix)))
        cold = series(runs[e], "read.incidence.cold", lambda r: r["bytesRead"] / r["operations"] if r.get("bytesRead") is not None else None)
        if cold:
            macro(f"coldread{key}{short}", fmt_bytes(med(cold)))


def runs_table(cfg, runs):
    with open(f"{OUT}/runs-{cfg}.tex", "w") as f:
        n = max(len(runs[e]) for e in ENGINES)
        f.write("\\begin{longtable}{@{}ll" + "r" * n + "@{}}\n\\toprule\n")
        f.write("Workload & Engine & " + " & ".join(f"Run {i + 1}" for i in range(n)) + " \\\\\n\\midrule\n\\endhead\n")
        for workload, _ in THROUGHPUT + TIMES + SIZES:
            for e, name in zip(ENGINES, ("HStore", "HyperGraphDB")):
                cells = []
                for run in runs[e]:
                    r = results(run).get(workload)
                    if r is None:
                        cells.append("--")
                    elif workload in dict(TIMES):
                        cells.append(f"{r['millis']:.0f}\\,ms")
                    elif workload.startswith("disk"):
                        cells.append(fmt_bytes(r["operations"]))
                    else:
                        cells.append(fmt_rate(ops(r)))
                cells += [""] * (n - len(cells))
                label = f"\\texttt{{{workload}}}" if e == "hstore" else ""
                f.write(f"{label} & {name} & " + " & ".join(cells) + " \\\\\n")
            f.write("\\addlinespace[2pt]\n")
        f.write("\\bottomrule\n\\end{longtable}\n")


def ranges_table(cfg, runs):
    with open(f"{OUT}/ranges-{cfg}.tex", "w") as f:
        f.write("\\begin{tabular}{@{}lrrrr@{}}\n\\toprule\n")
        f.write(" & \\multicolumn{2}{c}{HStore} & \\multicolumn{2}{c}{HyperGraphDB} \\\\\n")
        f.write("\\cmidrule(lr){2-3}\\cmidrule(l){4-5}\n")
        f.write("Workload & min--max & CV & min--max & CV \\\\\n\\midrule\n")
        for workload, _ in THROUGHPUT:
            cells = []
            for e in ENGINES:
                v = series(runs[e], workload, ops)
                if not v:
                    cells += ["--", "--"]
                    continue
                cv = statistics.pstdev(v) / statistics.mean(v) * 100 if len(v) > 1 else 0
                cells += [rng(v, fmt_rate), f"{cv:.0f}\\%"]
            f.write(f"\\texttt{{{workload}}} & " + " & ".join(cells) + " \\\\\n")
        f.write("\\bottomrule\n\\end{tabular}\n")


def environment(cfg, runs):
    key = KEYS[cfg]
    h = runs["hstore"][0]
    macro(f"nodes{key}", fmt_int(h["nodes"]))
    macro(f"edges{key}", fmt_int(h["edges"]))
    macro(f"incidences{key}", fmt_int(h["incidences"]))
    macro(f"runs{key}", str(min(len(runs[e]) for e in ENGINES)))



def metric_for(workload):
    if workload in dict(TIMES):
        return (lambda r: r["millis"]), True
    if workload.startswith("disk"):
        return (lambda r: r["operations"]), True
    return ops, False


def summary(all_runs):
    cfgs = [c for c in CONFIGS if c in all_runs]
    names = {"s1-async": "S1 async", "s1-sync": "S1 sync", "s4-async": "S4 async"}
    with open(f"{OUT}/summary.tex", "w") as f:
        f.write("\\begin{tabular}{@{}l" + "r" * len(cfgs) + "@{}}\n\\toprule\n")
        f.write("Workload & " + " & ".join(names[c] for c in cfgs) + " \\\\\n\\midrule\n")
        groups = [THROUGHPUT, TIMES, SIZES]
        for gi, group in enumerate(groups):
            if gi:
                f.write("\\midrule\n")
            for workload, _ in group:
                cells = []
                for c in cfgs:
                    runs = all_runs[c]
                    metric, lower = metric_for(workload)
                    h = series(runs["hstore"], workload, metric)
                    g = series(runs["hypergraphdb"], workload, metric)
                    if not h or not g:
                        cells.append("--")
                        continue
                    ratio = med(g) / med(h) if lower else med(h) / med(g)
                    disjoint = (max(h) < min(g) or min(h) > max(g))
                    text = fmt_ratio(ratio)
                    if ratio >= 1:
                        text = "\\textbf{" + text + "}"
                    if not disjoint:
                        text += "$^{\\dagger}$"
                    cells.append(text)
                    macros[f"sum{KEYS[c]}{workload.replace('.', '')}"] = fmt_ratio(ratio)
                    macros[f"dis{KEYS[c]}{workload.replace('.', '')}"] = "yes" if disjoint else "no"
                f.write(f"\\texttt{{{workload}}} & " + " & ".join(cells) + " \\\\\n")
        f.write("\\bottomrule\n\\end{tabular}\n")
    with open(f"{OUT}/summary-fig.tex", "w") as f:
        rows = [w for w, _ in THROUGHPUT + TIMES + SIZES]
        f.write("\\def\\sumsymbols{" + ",".join(rows) + "}\n")
        for c in cfgs:
            pts = []
            for workload in rows:
                runs = all_runs[c]
                metric, lower = metric_for(workload)
                h = series(runs["hstore"], workload, metric)
                g = series(runs["hypergraphdb"], workload, metric)
                if h and g:
                    ratio = med(g) / med(h) if lower else med(h) / med(g)
                    pts.append(f"({ratio:.4f},{workload})")
            f.write(f"\\def\\sum{KEYS[c]}{{{' '.join(pts)}}}\n")

available = []
all_runs = {}
for cfg in CONFIGS:
    runs = load(cfg)
    if not runs["hstore"] or not runs["hypergraphdb"]:
        continue
    available.append(cfg)
    all_runs[cfg] = runs
    rows = main_table(cfg, runs)
    write_main(cfg, rows)
    write_ratio_figure(cfg, rows)
    latency_table(cfg, runs)
    resources_table(cfg, runs)
    runs_table(cfg, runs)
    ranges_table(cfg, runs)
    environment(cfg, runs)

summary(all_runs)

loads = []
if os.path.exists(f"{BENCH}/progress.log"):
    import re
    for line in open(f"{BENCH}/progress.log"):
        m = re.search(r"load \{ ([0-9.]+)", line)
        if m:
            loads.append(float(m.group(1)))
if loads:
    macros["loadmin"] = f"{min(loads):.1f}"
    macros["loadmax"] = f"{max(loads):.1f}"
    macros["loadmed"] = f"{statistics.median(loads):.1f}"

with open(f"{OUT}/macros.tex", "w") as f:
    for name, value in sorted(macros.items()):
        safe = "".join(c for c in name if c.isalpha())
        f.write(f"\\expandafter\\def\\csname {safe}\\endcsname{{{value}}}\n")
print("configs:", ", ".join(available), "macros:", len(macros))


SHORT = {
    "ingest.nodes": "ingest.nodes", "ingest.edges": "ingest.edges", "write.update": "write.update",
    "latency.commit": "latency.commit", "churn.delete": "churn.delete", "churn.remove": "churn.remove",
    "large.ingest": "large.ingest", "read.incidence": "read.incidence", "read.members": "read.members",
    "read.comembership": "read.comemb.", "read.twohop": "read.twohop", "read.incidence.parallel": "read.inc.parallel",
    "latency.read": "latency.read", "read.incidence.cold": "read.inc.cold", "read.incidence.churned": "read.inc.churned",
    "large.scan": "large.scan", "large.probe": "large.probe", "mixed.read": "mixed.read", "reopen": "reopen",
    "recover": "recover", "disk": "disk", "disk.churned": "disk.churned", "disk.compacted": "disk.compacted",
}


def ratio_of(runs, workload):
    metric, lower = metric_for(workload)
    h = series(runs["hstore"], workload, metric)
    g = series(runs["hypergraphdb"], workload, metric)
    if not h or not g:
        return None
    return med(g) / med(h) if lower else med(h) / med(g)


def fig_ratios():
    cfgs = [c for c in CONFIGS if c in all_runs]
    rows = [w for w, _ in THROUGHPUT + TIMES + SIZES]
    labels = ",".join(SHORT[w] for w in rows)
    styles = {"s1-async": "fill=hs,draw=hs", "s1-sync": "fill=hs!45,draw=hs!45", "s4-async": "fill=hg,draw=hg!80!black"}
    legend = {"s1-async": "S1 async", "s1-sync": "S1 sync", "s4-async": "S4 async"}
    with open(f"{OUT}/fig-ratios.tex", "w") as f:
        f.write("\\begin{tikzpicture}\n\\begin{axis}[report, ybar=0pt, bar width=2.6pt, width=\\textwidth, height=5.6cm,\n")
        f.write(f"  ymode=log, log origin=infty, ymin=0.25, ymax=200, symbolic x coords={{{labels}}}, xtick=data,\n")
        f.write("  x tick label style={rotate=50, anchor=east, font=\\scriptsize\\ttfamily}, enlarge x limits=0.02,\n")
        f.write("  ylabel={HStore advantage ($\\times$, log)}, ymajorgrids, yticklabel style={/pgf/number format/fixed},\n")
        f.write("  ytick={0.5,1,2,5,10,20,50,100}, yticklabels={0.5,1,2,5,10,20,50,100},\n")
        f.write("  legend style={at={(0.5,1.02)}, anchor=south, legend columns=-1, /tikz/every even column/.append style={column sep=6pt}}]\n")
        for c in cfgs:
            pts = []
            for w in rows:
                r = ratio_of(all_runs[c], w)
                if r is not None:
                    pts.append(f"({SHORT[w]},{r:.4f})")
            f.write(f"\\addplot[{styles[c]}] coordinates {{{' '.join(pts)}}};\n\\addlegendentry{{{legend[c]}}}\n")
        f.write("\\addplot[rule, densely dashed, sharp plot, update limits=false] coordinates {(ingest.nodes,1) (disk.compacted,1)};\n")
        f.write("\\end{axis}\n\\end{tikzpicture}\n")


def fig_latency():
    panels = [("s1-async", "latency.commit", "Commit, async"), ("s1-sync", "latency.commit", "Commit, sync"),
              ("s1-async", "latency.read", "Read transaction"), ("s1-async", "mixed.write", "Commit beside 7 readers")]
    with open(f"{OUT}/fig-latency.tex", "w") as f:
        for i, (c, w, title) in enumerate(panels):
            if c not in all_runs:
                continue
            f.write("\\begin{tikzpicture}\n\\begin{axis}[report, width=0.27\\textwidth, height=3.9cm, ymode=log,\n")
            f.write("  xtick={0,1,2,3}, xticklabels={p50,p99,p99.9,max}, xmin=-0.3, xmax=3.3, ymajorgrids,\n")
            f.write(f"  title={{\\footnotesize {title}}}, title style={{yshift=-4pt}}")
            f.write(", ylabel={latency (\\textmu s)}" if i == 0 else "")
            f.write(", legend style={at={(0.03,0.97)}, anchor=north west}" if i == 0 else "")
            f.write("]\n")
            for e, style, name in (("hstore", "hs, mark=*, mark size=1.4pt, thick", "HStore"),
                                   ("hypergraphdb", "hg!70!black, mark=square*, mark size=1.3pt, thick", "HyperGraphDB")):
                pts = []
                for k, p in enumerate(("p50", "p99", "p99.9", "max")):
                    v = series(all_runs[c][e], w, lambda r, p=p: r.get("latency", {}).get(p))
                    if v:
                        pts.append(f"({k},{med(v):.3f})")
                f.write(f"\\addplot[{style}] coordinates {{{' '.join(pts)}}};\n")
                if i == 0:
                    f.write(f"\\addlegendentry{{{name}}}\n")
            f.write("\\end{axis}\n\\end{tikzpicture}%\n")
            if i < len(panels) - 1:
                f.write("\\hfill\n")


def fig_writes():
    c = "s1-async"
    if c not in all_runs:
        return
    rows = [w for w in WRITES if series(all_runs[c]["hstore"], w, lambda r: r.get("bytesWritten"))]
    with open(f"{OUT}/fig-writes.tex", "w") as f:
        f.write("\\begin{tikzpicture}\n\\begin{axis}[report, ybar=0pt, bar width=4pt, width=\\columnwidth, height=4.6cm,\n")
        f.write(f"  ymode=log, log origin=infty, symbolic x coords={{{','.join(rows)}}}, xtick=data,\n")
        f.write("  x tick label style={rotate=40, anchor=east, font=\\scriptsize\\ttfamily}, enlarge x limits=0.08,\n")
        f.write("  ylabel={bytes written per op}, ymajorgrids, legend style={at={(0.02,0.98)}, anchor=north west}]\n")
        for e, style, name in (("hstore", "fill=hs,draw=hs", "HStore"), ("hypergraphdb", "fill=hg,draw=hg!80!black", "HyperGraphDB")):
            pts = []
            for w in rows:
                v = series(all_runs[c][e], w, lambda r: r["bytesWritten"] / r["operations"] if r.get("bytesWritten") is not None else None)
                pts.append(f"({w},{med(v):.1f})")
            f.write(f"\\addplot[{style}] coordinates {{{' '.join(pts)}}};\n\\addlegendentry{{{name}}}\n")
        f.write("\\end{axis}\n\\end{tikzpicture}\n")


def fig_disk():
    cfgs = [c for c in ("s1-async", "s4-async") if c in all_runs]
    with open(f"{OUT}/fig-disk.tex", "w") as f:
        for i, c in enumerate(cfgs):
            f.write("\\begin{tikzpicture}\n\\begin{axis}[report, ybar=0pt, bar width=7pt, width=0.55\\columnwidth, height=4.2cm,\n")
            f.write("  symbolic x coords={load,deletes,compacted}, xtick=data, enlarge x limits=0.25, ymin=0, ymajorgrids,\n")
            f.write(f"  title={{\\footnotesize {'Scale 1' if c == 's1-async' else 'Scale 4'}}}, title style={{yshift=-4pt}}")
            f.write(", ylabel={MiB on disk}" if i == 0 else "")
            f.write(", legend style={at={(0.98,0.98)}, anchor=north east}" if i == 0 else "")
            f.write("]\n")
            for e, style, name in (("hstore", "fill=hs,draw=hs", "HStore"), ("hypergraphdb", "fill=hg,draw=hg!80!black", "HyperGraphDB")):
                pts = []
                for w, label in (("disk", "load"), ("disk.churned", "deletes"), ("disk.compacted", "compacted")):
                    v = series(all_runs[c][e], w, lambda r: r["operations"])
                    if v:
                        pts.append(f"({label},{med(v) / (1 << 20):.1f})")
                f.write(f"\\addplot[{style}] coordinates {{{' '.join(pts)}}};\n")
                if i == 0:
                    f.write(f"\\addlegendentry{{{name}}}\n")
            f.write("\\end{axis}\n\\end{tikzpicture}%\n")
            if i < len(cfgs) - 1:
                f.write("\\hfill\n")


fig_ratios()
fig_latency()
fig_writes()
fig_disk()


def resources_summary():
    cfgs = [c for c in CONFIGS if c in all_runs]
    names = {"s1-async": "S1 async", "s1-sync": "S1 sync", "s4-async": "S4 async"}
    with open(f"{OUT}/resources.tex", "w") as f:
        f.write("\\begin{tabular}{@{}l" + "rr" * len(cfgs) + "@{}}\n\\toprule\n")
        f.write(" & " + " & ".join(f"\\multicolumn{{2}}{{c}}{{{names[c]}}}" for c in cfgs) + " \\\\\n")
        f.write("".join(f"\\cmidrule(lr){{{2 + 2 * i}-{3 + 2 * i}}}" for i in range(len(cfgs))) + "\n")
        f.write("Measure" + " & HS & HG" * len(cfgs) + " \\\\\n\\midrule\n")

        def row(label, fn, fmt):
            cells = []
            for c in cfgs:
                for e in ENGINES:
                    v = [x for x in (fn(run) for run in all_runs[c][e]) if x is not None]
                    cells.append(fmt(med(v)) if v else "--")
            f.write(label + " & " + " & ".join(cells) + " \\\\\n")

        def wl(name, field):
            return lambda run: results(run).get(name, {}).get(field)

        row("Retained heap after ingest", lambda run: run.get("retainedHeap"), fmt_bytes)
        row("Bytes written, whole run", lambda run: run.get("bytesWritten"), fmt_bytes)
        row("GC pause, whole run (ms)", lambda run: sum(r.get("gcMillis", 0) for r in run["results"]), lambda v: fmt_int(v))
        row("Reclamation time (ms)", wl("compaction", "compactionMillis"), lambda v: fmt_int(v))
        row("Space reclaimed", wl("compaction", "reclaimed"), fmt_bytes)
        row("Acknowledged before kill", wl("recover", "acknowledged"), lambda v: fmt_int(v))
        lost = lambda run: results(run).get("recover", {}).get("lost")
        cells = []
        for c in cfgs:
            for e in ENGINES:
                v = [x for x in (lost(run) for run in all_runs[c][e]) if x is not None]
                cells.append(fmt_int(max(v)) if v else "--")
        f.write("Acknowledged lost (max) & " + " & ".join(cells) + " \\\\\n")
        f.write("\\bottomrule\n\\end{tabular}\n")


resources_summary()
