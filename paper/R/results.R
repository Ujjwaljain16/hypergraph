source(file.path(Sys.getenv("PAPER_R", "R"), "theme.R"))
suppressPackageStartupMessages(library(patchwork))

results_dir <- Sys.getenv("PAPER_RESULTS", "results")
generated <- Sys.getenv("PAPER_GENERATED", "generated")
figures <- Sys.getenv("PAPER_FIGURES", "figures")
dir.create(generated, showWarnings = FALSE)

configs <- c(`s1-async` = "S1 async", `s1-sync` = "S1 sync", `s4-async` = "S4 async")
config_colours <- c(`S1 async` = hs, `S1 sync` = "#6a9a3a", `S4 async` = hg)

num <- function(v) if (is.null(v)) NA_real_ else as.numeric(v)
lat <- function(r, p) if (is.null(r$latency)) NA_real_ else num(r$latency[[p]])

read_config <- function(cfg) {
  files <- list.files(file.path(results_dir, cfg), pattern = "\\.json$", full.names = TRUE)
  do.call(rbind, lapply(files, function(f) {
    d <- fromJSON(f, simplifyVector = FALSE)
    engine <- if (d$store == "HStore") "HStore" else "HyperGraphDB"
    run <- as.integer(sub(".*-(\\d+)\\.json$", "\\1", basename(f)))
    do.call(rbind, lapply(d$results, function(r) data.frame(
      config = cfg, engine = engine, run = run, workload = r$workload,
      ops = num(r$operations), millis = num(r$millis), checksum = num(r$checksum),
      allocated = num(r$allocated), written = num(r$bytesWritten), read = num(r$bytesRead),
      gc_ms = num(r$gcMillis), p50 = lat(r, "p50"), p99 = lat(r, "p99"), p999 = lat(r, "p99.9"),
      max = lat(r, "max"), lost = num(r$lost), acknowledged = num(r$acknowledged),
      reclaimed = num(r$reclaimed), compaction_ms = num(r$compactionMillis),
      retained = num(d$retainedHeap), total_written = num(d$bytesWritten),
      stringsAsFactors = FALSE)))
  }))
}

runs <- do.call(rbind, lapply(names(configs), read_config))
runs$rate <- runs$ops / runs$millis * 1000

size_workloads <- c("disk", "disk.churned", "disk.compacted")
time_workloads <- c("reopen", "recover")
fixed_rate <- c("mixed.write", "compaction.baseline", "compaction")

value_of <- function(d, w) {
  if (w %in% size_workloads) d$checksum else if (w %in% time_workloads) d$millis else d$rate
}
higher_is_better <- function(w) !(w %in% c(size_workloads, time_workloads))

cell <- function(cfg, w) {
  d <- runs[runs$config == cfg & runs$workload == w, ]
  h <- value_of(d[d$engine == "HStore", ], w)
  g <- value_of(d[d$engine == "HyperGraphDB", ], w)
  if (length(h) == 0 || length(g) == 0) return(NULL)
  hm <- median(h)
  gm <- median(g)
  up <- higher_is_better(w)
  ratio <- if (up) hm / gm else gm / hm
  lo <- if (up) min(h) / max(g) else min(g) / max(h)
  hi <- if (up) max(h) / min(g) else max(g) / min(h)
  overlap <- max(min(h), min(g)) <= min(max(h), max(g))
  data.frame(config = cfg, workload = w, hstore = hm, hgdb = gm, ratio = ratio, lo = lo, hi = hi,
             overlap = overlap, runs_h = length(h), runs_g = length(g), stringsAsFactors = FALSE)
}

workload_order <- unique(runs$workload)
cells <- do.call(rbind, lapply(names(configs), function(cfg) do.call(rbind, lapply(workload_order, function(w) cell(cfg, w)))))

macros <- character()
put <- function(key, value) macros <<- c(macros, sprintf("\\expandafter\\def\\csname m@%s\\endcsname{%s}", key, value))

sig <- function(x, digits = 2) {
  if (is.na(x)) return("n/a")
  if (abs(x) >= 100) return(format(round(x), big.mark = ",", trim = TRUE))
  formatC(signif(x, digits), format = "fg", digits = digits, flag = "#") |> sub(pattern = "\\.$", replacement = "")
}
ratio_text <- function(x) {
  if (x >= 10) format(round(x, 1), nsmall = 1, big.mark = ",") |> sub(pattern = "\\.0$", replacement = "")
  else sig(x, 2)
}
rate_text <- function(x) {
  if (x >= 1e6) paste0(sig(x / 1e6, 3), "\\,M") else if (x >= 1e3) paste0(sig(x / 1e3, 3), "\\,k") else sig(x, 3)
}
bytes_text <- function(x) {
  if (x >= 2^30) paste0(sig(x / 2^30, 3), "\\,GiB") else if (x >= 2^20) paste0(sig(x / 2^20, 3), "\\,MiB")
  else if (x >= 2^10) paste0(sig(x / 2^10, 3), "\\,KiB") else paste0(round(x), "\\,B")
}
micros_text <- function(us) {
  if (us >= 1e6) paste0(sig(us / 1e6, 3), "\\,s") else if (us >= 1e3) paste0(sig(us / 1e3, 3), "\\,ms") else paste0(sig(us, 3), "\\,\\textmu{}s")
}
value_text <- function(w, v) {
  if (w %in% size_workloads) bytes_text(v) else if (w %in% time_workloads) paste0(sig(v, 3), "\\,ms") else rate_text(v)
}

key <- function(...) gsub("[^A-Za-z0-9]", "", paste(..., sep = ""))

for (i in seq_len(nrow(cells))) {
  c <- cells[i, ]
  base <- key(c$config, c$workload)
  put(paste0("ratio", base), paste0(ratio_text(c$ratio), if (c$overlap) "$^\\dagger$" else ""))
  put(paste0("hs", base), value_text(c$workload, c$hstore))
  put(paste0("hg", base), value_text(c$workload, c$hgdb))
}
put("workloadCount", length(workload_order))
for (cfg in names(configs)) {
  n <- unique(runs$run[runs$config == cfg & runs$engine == "HStore"])
  put(paste0("runs", key(cfg)), length(n))
}

percentile_names <- c(p50 = "pfifty", p99 = "pninetynine", p999 = "pninetyninenine", max = "max")
for (cfg in names(configs)) for (w in c("latency.read", "latency.commit", "mixed.read", "mixed.write", "compaction.baseline", "compaction")) {
  for (engine in c("HStore", "HyperGraphDB")) {
    d <- runs[runs$config == cfg & runs$workload == w & runs$engine == engine, ]
    if (nrow(d) == 0) next
    for (p in names(percentile_names)) {
      put(paste0("lat", key(cfg, w), if (engine == "HStore") "hs" else "hg", percentile_names[[p]]), micros_text(median(d[[p]])))
    }
    put(paste0("lat", key(cfg, w), if (engine == "HStore") "hs" else "hg", "worst"), micros_text(max(d$max)))
  }
}

per_op <- function(cfg, w, engine, field) {
  d <- runs[runs$config == cfg & runs$workload == w & runs$engine == engine, ]
  median(d[[field]] / d$ops)
}
for (cfg in names(configs)) for (w in workload_order) for (engine in c("HStore", "HyperGraphDB")) {
  e <- if (engine == "HStore") "hs" else "hg"
  a <- per_op(cfg, w, engine, "allocated")
  b <- per_op(cfg, w, engine, "written")
  r <- per_op(cfg, w, engine, "read")
  if (!is.na(a)) put(paste0("alloc", key(cfg, w), e), bytes_text(a))
  if (!is.na(b)) put(paste0("written", key(cfg, w), e), bytes_text(b))
  if (!is.na(r)) put(paste0("read", key(cfg, w), e), bytes_text(r))
}
for (cfg in names(configs)) for (engine in c("HStore", "HyperGraphDB")) {
  d <- runs[runs$config == cfg & runs$engine == engine & runs$workload == "ingest.nodes", ]
  put(paste0("retained", key(cfg), if (engine == "HStore") "hs" else "hg"), bytes_text(median(d$retained)))
}

seconds_text <- function(ms) if (ms >= 60000) paste0(sig(ms / 60000, 2), "\\,min") else if (ms >= 1000) paste0(sig(ms / 1000, 2), "\\,s") else paste0(sig(ms, 2), "\\,ms")
for (cfg in names(configs)) for (engine in c("HStore", "HyperGraphDB")) {
  e <- if (engine == "HStore") "hs" else "hg"
  d <- runs[runs$config == cfg & runs$engine == engine, ]
  comp <- d[d$workload == "compaction", ]
  packed <- d[d$workload == "disk.compacted", ]
  if (nrow(comp) > 0) {
    put(paste0("compactms", key(cfg), e), seconds_text(median(comp$compaction_ms)))
    put(paste0("compactmsmax", key(cfg), e), seconds_text(max(comp$compaction_ms)))
    put(paste0("compactwritten", key(cfg), e), bytes_text(median(comp$written)))
    before <- merge(comp[, c("run", "reclaimed")], packed[, c("run", "checksum")], by = "run")
    put(paste0("diskbeforecompact", key(cfg), e), bytes_text(median(before$checksum + before$reclaimed)))
  }
  totals <- d[d$workload == "ingest.nodes", ]
  put(paste0("totalwritten", key(cfg), e), bytes_text(median(totals$total_written)))
  put(paste0("totalwrittenmax", key(cfg), e), bytes_text(max(totals$total_written)))
  for (w in c("mixed.read", "ingest.edges")) {
    g <- d[d$workload == w, ]
    if (nrow(g) > 0) put(paste0("gcms", key(cfg, w), e), seconds_text(median(g$gc_ms)))
  }
}

writeLines(c("\\makeatletter", "\\newcommand{\\M}[1]{\\@ifundefined{m@#1}{\\textbf{??#1}}{\\csname m@#1\\endcsname}}", macros, "\\makeatother"),
           file.path(generated, "numbers.tex"))

byte_breaks <- 4^(0:15)
byte_labels <- function(v) ifelse(v >= 2^30, paste0(v / 2^30, " GiB"), ifelse(v >= 2^20, paste0(v / 2^20, " MiB"),
  ifelse(v >= 2^10, paste0(v / 2^10, " KiB"), paste0(v, " B"))))

labels <- c(
  ingest.nodes = "ingest nodes", ingest.edges = "ingest hyperedges", read.incidence = "incidence set",
  read.members = "member list", read.comembership = "co-membership", read.twohop = "two-hop",
  read.incidence.parallel = "incidence, 8 threads", latency.read = "single lookup txn", write.update = "update",
  latency.commit = "single-update commit", mixed.read = "reads during writes", large.ingest = "load giant edge",
  large.scan = "scan giant edge", large.probe = "probe giant edge", reopen = "clean reopen",
  read.incidence.cold = "incidence, cold", disk = "disk after load", churn.delete = "delete hyperedges",
  churn.remove = "remove members", read.incidence.churned = "incidence after deletes", disk.churned = "disk after deletes",
  disk.compacted = "disk after reclaim", recover = "crash recovery")
groups <- c(ingest.nodes = "write", ingest.edges = "write", write.update = "write", latency.commit = "write",
            churn.delete = "write", churn.remove = "write", large.ingest = "write",
            read.incidence = "read", read.members = "read", read.comembership = "read", read.twohop = "read",
            read.incidence.parallel = "read", latency.read = "read", mixed.read = "read", large.scan = "read",
            large.probe = "read", read.incidence.cold = "read", read.incidence.churned = "read",
            reopen = "lifecycle", recover = "lifecycle", disk = "space", disk.churned = "space", disk.compacted = "space")

plotted <- cells[cells$workload %in% names(labels), ]
plotted$label <- factor(labels[plotted$workload], levels = rev(labels[names(labels) %in% plotted$workload]))
plotted$group <- factor(groups[plotted$workload], levels = c("write", "read", "lifecycle", "space"))
plotted$Configuration <- factor(configs[plotted$config], levels = configs)

ratio_plot <- ggplot(plotted, aes(x = ratio, y = label, colour = Configuration)) +
  geom_vline(xintercept = 1, colour = rule, linewidth = 0.35) +
  geom_linerange(aes(xmin = lo, xmax = hi), position = position_dodge(width = 0.65), linewidth = 0.35, alpha = 0.7) +
  geom_point(position = position_dodge(width = 0.65), size = 1.1) +
  scale_x_log10(breaks = c(0.1, 0.3, 1, 3, 10, 30, 100, 300), labels = c("0.1", "0.3", "1", "3", "10", "30", "100", "300")) +
  scale_colour_manual(values = config_colours) +
  facet_grid(group ~ ., scales = "free_y", space = "free_y") +
  labs(x = "HStore advantage (log scale; above 1 favours HStore)", y = NULL) +
  theme_paper(7) +
  theme(strip.text.y = element_text(angle = 0, hjust = 0), panel.spacing = unit(1.2, "mm"))
save_plot("ratios", ratio_plot, column, 4.6, readme = FALSE)
if (nzchar(readme_images)) {
  wide <- ratio_plot + facet_wrap(~ group, ncol = 2, scales = "free_y") + theme(legend.position = "top")
  ggsave(file.path(readme_images, "ratios-wide.svg"), wide, device = svg, width = textwidth, height = 3.6, bg = "white")
}

lat_workloads <- c(latency.read = "single lookup txn", mixed.read = "reads during writes",
                   latency.commit = "single-update commit", mixed.write = "writer during reads")
lat <- runs[runs$workload %in% names(lat_workloads), c("config", "engine", "run", "workload", "p50", "p99", "p999", "max")]
lat_long <- do.call(rbind, lapply(c("p50", "p99", "p999", "max"), function(p) {
  data.frame(lat[, c("config", "engine", "run", "workload")], percentile = p, micros = lat[[p]])
}))
lat_med <- aggregate(micros ~ config + engine + workload + percentile, lat_long, median)
lat_med$percentile <- factor(lat_med$percentile, levels = c("p50", "p99", "p999", "max"), labels = c("p50", "p99", "p99.9", "max"))
lat_med$workload <- factor(lat_workloads[lat_med$workload], levels = lat_workloads)
lat_med$config <- factor(configs[lat_med$config], levels = configs)
latency_plot <- ggplot(lat_med, aes(x = percentile, y = micros, colour = engine, group = engine)) +
  geom_line(linewidth = 0.4) + geom_point(size = 0.9) +
  scale_y_log10(labels = function(v) ifelse(v >= 1000, paste0(v / 1000, " ms"), paste0(v, " µs"))) +
  scale_colour_manual(values = engine_colours) +
  facet_grid(config ~ workload) +
  labs(x = NULL, y = "latency (log scale)") +
  theme_paper(7)
save_plot("latency", latency_plot, textwidth, 2.9, readme = FALSE)

write_workloads <- c(ingest.nodes = "ingest nodes", ingest.edges = "ingest hyperedges", write.update = "update (1,000 per txn)",
                     latency.commit = "single-update commit", churn.delete = "delete hyperedge", churn.remove = "remove member")
wr <- runs[runs$workload %in% names(write_workloads) & runs$config %in% c("s1-async", "s4-async"), ]
wr$per_op <- wr$written / wr$ops
wr_med <- aggregate(per_op ~ config + engine + workload, wr, median)
wr_med$workload <- factor(write_workloads[wr_med$workload], levels = rev(write_workloads))
wr_med$config <- factor(configs[wr_med$config], levels = configs)
dumbbell <- function(d, xlab, title, legend = TRUE) {
  span <- aggregate(per_op ~ config + workload, d, function(v) c(min(v), max(v)))
  span <- data.frame(config = span$config, workload = span$workload, lo = span$per_op[, 1], hi = span$per_op[, 2])
  ggplot(d, aes(x = per_op, y = workload)) +
    geom_segment(data = span, aes(x = lo, xend = hi, y = workload, yend = workload), colour = rule, linewidth = 0.5) +
    geom_point(aes(colour = engine), size = 1.4) +
    scale_x_log10(breaks = byte_breaks, labels = byte_labels) +
    scale_colour_manual(values = engine_colours) +
    facet_grid(. ~ config) +
    labs(x = xlab, y = NULL, title = title) +
    theme_paper(7) +
    theme(plot.title = element_text(size = 7.5, face = "bold"), legend.position = if (legend) "top" else "none")
}
written_plot <- dumbbell(wr_med, "bytes written per operation (log scale)", "(a) Write amplification")

read_workloads <- c(read.incidence = "incidence set", read.members = "member list", read.comembership = "co-membership",
                    read.twohop = "two-hop", latency.read = "single lookup txn")
rd <- runs[runs$workload %in% names(read_workloads) & runs$config %in% c("s1-async", "s4-async"), ]
rd$per_op <- rd$allocated / rd$ops
rd_med <- aggregate(per_op ~ config + engine + workload, rd, median)
rd_med$per_op <- pmax(rd_med$per_op, 1)
rd_med$workload <- factor(read_workloads[rd_med$workload], levels = rev(read_workloads))
rd_med$config <- factor(configs[rd_med$config], levels = configs)
alloc_plot <- dumbbell(rd_med, "heap bytes allocated per operation (log scale; below 1 B drawn at 1 B)",
                       "(b) Allocation on reads", legend = FALSE)

disk_steps <- c(disk = "after load", disk.churned = "after deletes", disk.compacted = "after reclaim")
dk <- runs[runs$workload %in% names(disk_steps), ]
dk_med <- aggregate(checksum ~ config + engine + workload, dk, median)
dk_med$step <- factor(disk_steps[dk_med$workload], levels = disk_steps)
dk_med$config <- factor(configs[dk_med$config], levels = configs)
disk_plot <- ggplot(dk_med, aes(x = step, y = checksum / 2^20, colour = engine, group = engine)) +
  geom_line(linewidth = 0.45) + geom_point(size = 1) +
  scale_colour_manual(values = engine_colours) +
  facet_wrap(~ config, nrow = 1, scales = "free_y") +
  expand_limits(y = 0) +
  labs(x = NULL, y = "MiB on disk", title = "(c) Space through the churn") +
  theme_paper(7) + theme(plot.title = element_text(size = 7.5, face = "bold"), legend.position = "none")

resources <- written_plot / alloc_plot / disk_plot + plot_layout(heights = c(1.15, 1, 0.9))
save_plot("resources", resources, textwidth, 5.2)

combined_rows <- sapply(workload_order, function(w) {
  get <- function(cfg) cells[cells$config == cfg & cells$workload == w, ]
  a <- get("s1-async"); y <- get("s1-sync"); b <- get("s4-async")
  if (nrow(a) == 0 || nrow(b) == 0 || nrow(y) == 0) return(NA_character_)
  mark <- function(r) paste0(ratio_text(r$ratio), if (r$overlap) "$^\\dagger$" else "")
  sprintf("\\wl{%s} & %s & %s & %s & %s & %s & %s & %s \\\\", w,
          value_text(w, a$hstore), value_text(w, a$hgdb), mark(a), mark(y),
          value_text(w, b$hstore), value_text(w, b$hgdb), mark(b))
})
writeLines(c(combined_rows[!is.na(combined_rows)], "\\bottomrule"), file.path(generated, "table-all.tex"))

ablation_dir <- Sys.getenv("PAPER_ABLATION", "ablation")
ablations <- c(logical = "logged keys", inline = "inline member trees", lazy = "records decoded on demand")
ablation_workloads <- list(
  logical = c(write.update = "update", latency.commit = "single-update commit", ingest.edges = "ingest hyperedges",
              read.incidence = "incidence set", recover = "crash recovery"),
  inline = c(read.members = "member list", read.twohop = "two-hop", large.scan = "scan giant edge",
             latency.read = "single lookup txn", disk.compacted = "disk after reclaim"),
  lazy = c(read.members = "member list", read.twohop = "two-hop", read.incidence.cold = "incidence, cold",
           read.incidence.parallel = "incidence, 8 threads", churn.delete = "delete hyperedges"))
read_ablation <- function(name) {
  files <- list.files(file.path(ablation_dir, name), pattern = "\\.json$", full.names = TRUE)
  if (length(files) == 0) return(NULL)
  do.call(rbind, lapply(files, function(f) {
    d <- fromJSON(f, simplifyVector = FALSE)
    side <- sub("-\\d+\\.json$", "", basename(f))
    do.call(rbind, lapply(d$results, function(r) data.frame(
      ablation = name, side = side, workload = r$workload, ops = num(r$operations), millis = num(r$millis),
      checksum = num(r$checksum), written = num(r$bytesWritten), allocated = num(r$allocated), stringsAsFactors = FALSE)))
  }))
}
abl <- do.call(rbind, lapply(names(ablations), read_ablation))
if (!is.null(abl)) {
  abl$rate <- abl$ops / abl$millis * 1000
  abl_cell <- function(name, w, field) {
    d <- abl[abl$ablation == name & abl$workload == w, ]
    value <- function(x) if (field == "written") x$written / x$ops else value_of(x, w)
    b <- value(d[d$side == "before", ])
    a <- value(d[d$side == "after", ])
    if (length(a) == 0 || length(b) == 0 || anyNA(c(a, b))) return(NULL)
    up <- field != "written" && higher_is_better(w)
    data.frame(ablation = name, workload = w, field = field, before = median(b), after = median(a),
               ratio = if (up) median(a) / median(b) else median(b) / median(a),
               lo = if (up) min(a) / max(b) else min(b) / max(a), hi = if (up) max(a) / min(b) else max(b) / min(a),
               overlap = max(min(a), min(b)) <= min(max(a), max(b)), stringsAsFactors = FALSE)
  }
  abl_cells <- do.call(rbind, lapply(names(ablations), function(name) do.call(rbind, c(
    lapply(names(ablation_workloads[[name]]), function(w) abl_cell(name, w, "value")),
    lapply(names(ablation_workloads[[name]]), function(w) abl_cell(name, w, "written"))))))
  ablation_macros <- character()
  for (i in seq_len(nrow(abl_cells))) {
    c <- abl_cells[i, ]
    base <- key("abl", c$ablation, c$workload, if (c$field == "written") "written" else "")
    ablation_macros <- c(ablation_macros,
      sprintf("\\expandafter\\def\\csname m@%s\\endcsname{%s}", base, paste0(ratio_text(c$ratio), if (c$overlap) "$^\\dagger$" else "")),
      sprintf("\\expandafter\\def\\csname m@%sbefore\\endcsname{%s}", base,
              if (c$field == "written") bytes_text(c$before) else value_text(c$workload, c$before)),
      sprintf("\\expandafter\\def\\csname m@%safter\\endcsname{%s}", base,
              if (c$field == "written") bytes_text(c$after) else value_text(c$workload, c$after)))
  }
  for (name in names(ablations)) {
    n <- length(unique(sub("^.*-", "", list.files(file.path(ablation_dir, name), pattern = "^after-"))))
    ablation_macros <- c(ablation_macros, sprintf("\\expandafter\\def\\csname m@ablruns%s\\endcsname{%d}", name, n))
  }
  writeLines(c("\\makeatletter", ablation_macros, "\\makeatother"), file.path(generated, "ablation.tex"))
  shown <- abl_cells[abl_cells$field == "value", ]
  shown$label <- mapply(function(name, w) ablation_workloads[[name]][[w]], shown$ablation, shown$workload)
  shown$Change <- factor(ablations[shown$ablation], levels = ablations)
  shown$label <- factor(paste(shown$ablation, shown$label), levels = rev(unlist(lapply(names(ablation_workloads), function(n) paste(n, ablation_workloads[[n]])))))
  ablation_plot <- ggplot(shown, aes(x = ratio, y = label)) +
    scale_y_discrete(labels = function(v) sub("^[a-z]+ ", "", v)) +
    geom_vline(xintercept = 1, colour = rule, linewidth = 0.35) +
    geom_linerange(aes(xmin = lo, xmax = hi), linewidth = 0.35, colour = hs, alpha = 0.7) +
    geom_point(size = 1.1, colour = hs) +
    scale_x_log10(breaks = c(0.25, 0.5, 1, 2, 4, 8, 16), labels = c("0.25", "0.5", "1", "2", "4", "8", "16")) +
    facet_grid(Change ~ ., scales = "free_y", space = "free_y") +
    labs(x = "with the change / without it (log scale; above 1 is better)", y = NULL) +
    theme_paper(7) +
    theme(strip.text.y = element_text(angle = 0, hjust = 0), panel.spacing = unit(1.2, "mm"))
  save_plot("ablation", ablation_plot, column, 2.6, readme = FALSE)
} else {
  writeLines(character(), file.path(generated, "ablation.tex"))
}
