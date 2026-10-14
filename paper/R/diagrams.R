source(file.path(Sys.getenv("PAPER_R", "R"), "theme.R"))

out <- Sys.getenv("PAPER_FIGURES", "figures")

triangle <- function(x, y, w, h, fill, col = rule, lwd = 0.5) {
  grid.polygon(mm(c(x, x - w / 2, x + w / 2)), mm(c(y, y - h, y - h)), gp = gpar(fill = fill, col = col, lwd = lwd))
}

disk_shape <- function(x, y, w, h, label, size = 6.6) {
  e <- 1.6
  grid.rect(mm(x), mm(y - h / 2 + e / 2), mm(w), mm(h - e), just = c("left", "bottom"), gp = gpar(fill = disk, col = NA))
  grid.lines(mm(c(x, x)), mm(c(y - h / 2 + e / 2, y + h / 2 - e / 2)), gp = gpar(col = rule, lwd = 0.5))
  grid.lines(mm(c(x + w, x + w)), mm(c(y - h / 2 + e / 2, y + h / 2 - e / 2)), gp = gpar(col = rule, lwd = 0.5))
  t <- seq(0, 2 * pi, length.out = 60)
  grid.polygon(mm(x + w / 2 + w / 2 * cos(t)), mm(y + h / 2 - e / 2 + e / 2 * sin(t)), gp = gpar(fill = disk, col = rule, lwd = 0.5))
  b <- seq(pi, 2 * pi, length.out = 30)
  grid.lines(mm(x + w / 2 + w / 2 * cos(b)), mm(y - h / 2 + e / 2 + e / 2 * sin(b)), gp = gpar(col = rule, lwd = 0.5))
  label(label, x + w / 2, y - e / 4, size = size)
}

overview <- function() {
  W <- textwidth * 25.4
  grid.lines(mm(c(12, W - 2)), mm(c(22.5, 22.5)), gp = gpar(col = rule, lwd = 0.5, lty = "22"))
  label("memory", 4, 46, size = 6.4, rot = 90, col = rule)
  label("disk", 4, 11, size = 6.4, rot = 90, col = rule)

  box(24, 56, 25, 8.5, "Transaction\nprivate workspace", size = 6.4)
  box(24, 43, 25, 8.5, "Transaction\nprivate workspace", size = 6.4)
  label("Writers", 24, 66, size = 7.2, face = "bold")
  box(56, 49.5, 29, 14, "Commit\nfast path or rebase,\nencode changed keys", fill = paper, size = 6.4)
  label("under the commit lock", 56, 58.6, size = 5.6, col = rule)
  arrow_line(c(36.5, 41.5), c(55, 52))
  arrow_line(c(36.5, 41.5), c(44, 47))

  gx <- 80
  gw <- 66
  for (k in 2:1) {
    box(gx + k * 1.8, 56 + k * 1.8, gw, 6, fill = "#f7f7f7", lwd = 0.45, just = c("left", "centre"))
  }
  box(gx, 56, gw, 6, fill = "white", lwd = 0.5, just = c("left", "centre"))
  slots <- c("catalog", "reverse", "type", "canonical", "symbols", "requests", "...")
  sw <- (gw - 1.6) / length(slots)
  for (i in seq_along(slots)) {
    sx <- gx + 0.8 + (i - 1) * sw
    rect(sx + 0.2, 53.8, sw - 0.4, 4.4, fill = hs_light, col = rule, lwd = 0.35)
    label(slots[i], sx + sw / 2, 56, size = 5.5)
  }
  label("Root vectors of published generations", gx + gw / 2 + 2, 66, size = 7.2, face = "bold")
  arrow_line(c(70.5, gx - 0.5), c(51, 54))
  label("publish", 74.5, 49, size = 5.6, col = rule)

  tree <- function(cx, slot_x, leaf) {
    triangle(cx, 49.6, 13, 13.5, fill = disk)
    arrow_line(c(slot_x, cx), c(53.8, 49.9), lwd = 0.4, length = 0.9)
    px <- c(cx, cx + leaf * 2.6, cx + leaf * 4.9)
    py <- c(49.6, 44.6, 37.4)
    grid.lines(mm(px), mm(py), gp = gpar(col = "#b8962e", lwd = 0.9))
    grid.circle(mm(px), mm(py), r = unit(0.95, "mm"), gp = gpar(fill = "#f3d36b", col = "#8a6d1a", lwd = 0.4))
  }
  for (j in 0:3) {
    cx <- gx + 0.8 + (2 * j + 0.5) * sw
    tree(cx, cx, c(1, -1, 0.4, -0.5)[j + 1])
  }

  box(gx + 32.5, 29, 46, 8, "Checkpoint (background)\nwrites each changed node once", fill = paper, size = 6.4)
  arrow_line(c(gx + 32.5, gx + 32.5), c(35.6, 33.4))

  disk_shape(37, 11, 38, 13, "Write-ahead log\nBegin, Changes, Feed, Commit")
  arrow_line(c(56, 56), c(42.3, 18))
  label("append, then one fsync\nper commit group", 57.5, 30, size = 5.6, hjust = 0, col = rule)
  arrow_line(c(gx + 9.5, 75.5), c(29, 14))
  label("truncate", gx + 4.5, 19.5, size = 5.6, col = rule, hjust = 0)

  disk_shape(gx + 4, 11, 24, 13, "Segment files\n64-byte units", size = 6.2)
  disk_shape(gx + 32, 11, 24, 13, "Page directory,\nreference counts", size = 6.2)
  disk_shape(gx + 60, 11, 24, 13, "Catalog\nimage", size = 6.2)
  arrow_line(c(gx + 24, gx + 16), c(25, 17.6))
  arrow_line(c(gx + 32.5, gx + 41), c(25, 17.6))
  arrow_line(c(gx + 41, gx + 70), c(25, 17.6))

  rx <- W - 12
  label("Readers", rx, 66, size = 7.2, face = "bold")
  box(rx, 56, 16, 6, "snapshot", size = 6)
  box(rx, 47, 16, 6, "snapshot", size = 6)
  arrow_line(c(rx - 8, gx + gw + 0.6), c(56, 56))
  arrow_line(c(rx - 8, gx + gw + 2.4), c(47, 57.8))
  label("pinned, read\nwithout locks", rx, 39.5, size = 5.6, col = rule)

  grid.circle(mm(rx - 6), mm(31), r = unit(0.95, "mm"), gp = gpar(fill = "#f3d36b", col = "#8a6d1a", lwd = 0.4))
  label("node in memory", rx - 4, 31, size = 5.6, hjust = 0)
  rect(rx - 7.2, 26.2, 2.4, 2.4, fill = disk, col = rule, lwd = 0.4)
  label("stored page", rx - 4, 27.4, size = 5.6, hjust = 0)
}

save_grid(file.path(out, "overview.pdf"), textwidth, 70 / 25.4, overview)

cell <- function(x, y, w, h, text, fill = "white", size = 5.6, family = sans, col = rule) {
  rect(x, y, w, h, fill = fill, col = col, lwd = 0.4)
  label(text, x + w / 2, y + h / 2, size = size, family = family)
}

layout_figure <- function() {
  label("(a) Catalog: a tree of trees", 2, 54, size = 7.2, face = "bold", hjust = 0)
  bx <- 20
  for (i in 0:3) {
    cell(bx + i * 15, 43.5, 4, 5, c("4000", "4100", "4183", "4270")[i + 1], fill = "#f7f7f7", size = 5.2)
    cell(bx + i * 15 + 4, 43.5, 11, 5, "#p  \u03a3", fill = hs_light, size = 5.4)
  }
  label("branch node", bx - 1.5, 46, size = 5.6, hjust = 1, col = rule)
  label("\u03a3: count, key range, weight and time ranges,\nrole mask and fingerprint of the child", bx + 61.5, 39.6,
        size = 5.4, col = rule, hjust = 1)
  arrow_line(c(bx + 4 + 15 + 5.5, 44), c(43.5, 33.6), lwd = 0.5, length = 1.1)
  label("packed leaf", 4, 35.6, size = 5.6, hjust = 0, col = rule)

  segs <- list(c("header", 9, "#f7f7f7"), c("\u03a3", 5, "#f7f7f7"), c("keys \u0394", 9, "#f7f7f7"),
               c("4100 node", 14, "white"), c("4101 edge", 15, "white"), c("4102 edge", 15, "white"),
               c("4103 node", 14, "white"), c("...", 7, "white"))
  x <- 4
  starts <- numeric()
  for (sg in segs) {
    w <- as.numeric(sg[2])
    starts <- c(starts, x)
    cell(x, 27.8, w, 5.8, sg[1], fill = sg[3], size = 5.4)
    if (x > 25) rect(x, 27.8, 1.6, 5.8, fill = "#e8e8e8", col = rule, lwd = 0.3)
    x <- x + w
  }
  label("length of each record", starts[4] + 0.8, 25.4, size = 5.2, col = rule, hjust = 0)

  ex <- starts[5] + 8.3
  arrow_line(c(ex, ex), c(27.8, 20.4), lwd = 0.5, length = 1.1)
  cell(ex - 9, 14.6, 18, 5.6, "members 7, 19, 23", fill = memory, size = 5.4)
  label("inline in the record\n(at most 32 entries)", ex, 9.6, size = 5.4, col = rule)

  fx <- starts[6] + 8.3
  arrow_line(c(fx, fx + 10), c(27.8, 21.6), lwd = 0.5, length = 1.1)
  triangle(fx + 12, 21.6, 12, 11, fill = disk)
  label("member tree on\nits own pages", fx + 12, 6.4, size = 5.4, col = rule)

  ox <- 110
  label("(b) Reverse incidence index", ox, 54, size = 7.2, face = "bold", hjust = 0)
  label("directory leaf: atom to posting set", ox, 48, size = 5.6, hjust = 0, col = rule)
  rows <- list(c("7", "4101, 4310, 5022"), c("19", "4101"), c("23", "4101, 4877"), c("3", "promoted"))
  for (i in seq_along(rows)) {
    y <- 40.5 - (i - 1) * 6.4
    cell(ox, y, 9, 5.4, rows[[i]][1], fill = "#f7f7f7", size = 5.6)
    fill <- if (rows[[i]][2] == "promoted") hs_light else memory
    cell(ox + 9, y, 30, 5.4, rows[[i]][2], fill = fill, size = 5.4)
  }
  label("inline while at most\n48 entries", ox + 54, 37.6, size = 5.4, col = rule)
  arrow_line(c(ox + 39.2, ox + 47.5), c(43.2, 39.8), lwd = 0.4, length = 0.9)
  arrow_line(c(ox + 39.2, ox + 50), c(24.2, 21.6), lwd = 0.5, length = 1.1)
  triangle(ox + 54, 21.6, 13, 11.5, fill = disk)
  label("postings tree,\nkeyed by hyperedge", ox + 54, 6.4, size = 5.4, col = rule)
  label("summaries count the postings\nunder any range of atoms", ox, 13.5, size = 5.4, col = rule, hjust = 0)
}

save_grid(file.path(out, "layout.pdf"), textwidth, 57 / 25.4, layout_figure)

compaction_figure <- function() {
  live <- hs_light
  dead <- "#ececec"
  cell(2, 41, 22, 6, "child ref #1234", fill = "white", size = 5.6)
  label("any node, any generation", 13, 38.4, size = 5.2, col = rule)
  arrow_line(c(24.2, 30.6), c(44, 44), lwd = 0.5, length = 1.1)
  rect(31, 41, 51, 6, fill = "white", col = rule, lwd = 0.45)
  label("pages.dir[1234]", 33, 44, size = 5.6, hjust = 0, family = mono)
  label("seg 3, unit 210", 57.5, 45.5, size = 5.3, hjust = 0, col = rule)
  grid.lines(mm(c(57.3, 73.6)), mm(c(45.35, 45.35)), gp = gpar(col = hg, lwd = 0.6))
  label("seg 7, unit 512", 57.5, 42.4, size = 5.3, hjust = 0, col = hs, face = "bold")
  label("Page numbers never change; only the directory entry moves.", 2, 51.5, size = 5.6, hjust = 0, col = ink)

  pattern3 <- c(2, 1, 1, 3, 1, 2, 1, 1, 2, 1, 3, 1)
  state3 <- c("live", "dead", "dead", "live", "dead", "live", "dead", "dead", "live", "dead", "dead", "dead")
  label("segment 3 (sealed, 30% live)", 2, 31.8, size = 5.6, hjust = 0)
  x <- 2
  centers <- numeric()
  for (i in seq_along(pattern3)) {
    w <- pattern3[i] * 3.3
    rect(x, 24, w, 5.6, fill = if (state3[i] == "live") live else dead, col = rule, lwd = 0.35)
    if (state3[i] == "live") centers <- c(centers, x + w / 2)
    x <- x + w
  }
  label("segment 7 (active)", 2, 15.6, size = 5.6, hjust = 0)
  rect(2, 7.8, 30, 5.6, fill = live, col = rule, lwd = 0.35)
  label("earlier pages", 17, 10.6, size = 5.2, col = rule)
  nx <- 32
  for (k in seq_along(centers)) {
    w <- c(2, 3, 2, 2)[k] * 3.3
    rect(nx, 7.8, w, 5.6, fill = "#c4d9ea", col = rule, lwd = 0.35)
    arrow_line(c(centers[k], nx + w / 2), c(24, 13.6), lwd = 0.45, length = 0.9)
    nx <- nx + w
  }
  rect(nx, 7.8, 82 - nx, 5.6, fill = "white", col = rule, lwd = 0.35)
  label("bytes copied unchanged: the header holds the page number, so the checksum stays valid",
        2, 3.4, size = 5.2, hjust = 0, col = rule)
  rect(62, 31, 2.4, 2.4, fill = live, col = rule, lwd = 0.35)
  label("live", 65.2, 32.2, size = 5.2, hjust = 0)
  rect(71, 31, 2.4, 2.4, fill = dead, col = rule, lwd = 0.35)
  label("released", 74.2, 32.2, size = 5.2, hjust = 0)
}

save_grid(file.path(out, "compaction.pdf"), column, 55 / 25.4, compaction_figure)

wal_figure <- function() {
  y <- 17
  h <- 6
  fills <- c(B = "#f7f7f7", X = hs_light, F = "#eef3e6", C = hs, K = "#f3d36b", P = "#e3d7ee")
  text_col <- c(B = ink, X = ink, F = ink, C = "white", K = ink, P = ink)
  names_short <- c(B = "B", X = "Changes", F = "Feed", C = "Commit", K = "Ckpt", P = "PageRef")
  widths <- c(B = 2.8, X = 8, F = 5, C = 7.5, K = 5.5, P = 7.5)
  groups <- list(
    list(g = "g102", recs = c("B", "X", "C")),
    list(g = "g103", recs = c("B", "X", "F", "C")),
    list(g = "g104", recs = c("B", "X", "C")),
    list(g = "", recs = c("K")),
    list(g = "g105", recs = c("B", "X", "F", "C")),
    list(g = "g106", recs = c("B", "P", "X", "C")),
    list(g = "txn 41", recs = c("B", "X"))
  )
  x0 <- 24
  grid.rect(mm(2), mm(y), mm(x0 - 2), mm(h), just = c("left", "bottom"), gp = gpar(fill = "#f1f1f1", col = rule, lwd = 0.35, lty = "22"))
  label("deleted by an\nearlier checkpoint", 2 + (x0 - 2) / 2, y + h / 2, size = 5, col = rule)
  x <- x0
  bounds <- list()
  for (gi in seq_along(groups)) {
    gx0 <- x
    for (r in groups[[gi]]$recs) {
      rect(x, y, widths[[r]], h, fill = fills[[r]], col = rule, lwd = 0.35)
      label(names_short[[r]], x + widths[[r]] / 2, y + h / 2, size = 5.2, col = text_col[[r]])
      x <- x + widths[[r]]
    }
    bounds[[gi]] <- c(gx0, x)
    if (nzchar(groups[[gi]]$g)) label(groups[[gi]]$g, (gx0 + x) / 2, y + h + 2.2, size = 5.8)
    x <- x + 0.8
  }
  ck <- bounds[[2]][2]
  grid.lines(mm(c(ck + 0.6, ck + 0.6)), mm(c(y - 3, y + h + 9)), gp = gpar(col = hs, lwd = 0.8))
  label("checkpoint LSN in the catalog image:\nend of g103's Commit", ck + 0.6, y + h + 12, size = 5.4, col = hs)
  arrow_curve(bounds[[4]][1] + 3.2, y + h + 0.2, ck + 1.2, y + h + 4.5, curvature = 0.45, col = "#b8962e", lwd = 0.45)
  label("written later, points back", bounds[[4]][1] + 6.5, y + h + 6.8, size = 5, col = "#8a6d1a", hjust = 0)
  endx <- bounds[[7]][2]
  grid.lines(mm(c(endx + 0.6, endx + 0.6)), mm(c(y - 3, y + h + 9)), gp = gpar(col = ink, lwd = 0.8))
  label("log end after\nthe torn tail is cut", endx - 1, y + h + 12, size = 5.4)

  bracket <- function(a, b, yy, text, col) {
    grid.lines(mm(c(a, a, b, b)), mm(c(yy + 1.4, yy, yy, yy + 1.4)), gp = gpar(col = col, lwd = 0.6))
    label(text, (a + b) / 2, yy - 2.6, size = 5.4, col = col)
  }
  bracket(bounds[[3]][1], bounds[[5]][2], y - 2, "replayed: g104, g105", hs)
  bracket(bounds[[6]][1], bounds[[6]][2], y - 2, "page fails its CRC:\ng106 and later dropped", hg)
  bracket(bounds[[7]][1], bounds[[7]][2], y - 2, "no Commit\nrecord: dropped", hg)
  label("After recovery the first checkpoint starts at the log end and the next transaction id is above every id in the log,\nso the records of txn 41 can never be grouped with a later transaction.",
        x0, 3.2, size = 5.4, hjust = 0, col = rule)
}

save_grid(file.path(out, "wal.pdf"), textwidth, 40 / 25.4, wal_figure)
