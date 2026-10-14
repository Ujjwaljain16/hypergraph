suppressPackageStartupMessages({
  library(grid)
  library(ggplot2)
  library(jsonlite)
  library(scales)
})

sans <- "Linux Biolinum O"
mono <- "Inconsolatazi4"

ink <- "#1f1f1f"
rule <- "#5b5b5b"
faint <- "#d9d9d9"
paper <- "#f4f4f2"
hs <- "#1f5f8b"
hs_light <- "#d6e4ef"
hg <- "#b5532f"
hg_light <- "#f2ddd3"
memory <- "#fbf3d5"
disk <- "#e3ecf3"

column <- 3.33
textwidth <- 7.0

readme_images <- Sys.getenv("PAPER_README_IMAGES", "")

open_device <- function(file, width, height) {
  if (grepl("\\.svg$", file)) svg(file, width = width, height = height, family = sans, bg = "white")
  else cairo_pdf(file, width = width, height = height, family = sans)
}

save_grid <- function(file, width, height, draw) {
  open_device(file, width, height)
  on.exit(dev.off())
  grid.newpage()
  pushViewport(viewport(xscale = c(0, width * 25.4), yscale = c(0, height * 25.4)))
  draw()
  popViewport()
}

mm <- function(v) unit(v, "native")

box <- function(x, y, w, h, label = NULL, fill = "white", col = rule, size = 7, face = "plain",
                family = sans, lwd = 0.6, just = "centre", r = 0.6, text_col = ink, lineheight = 1.05) {
  cx <- if (identical(just[1], "left")) x + w / 2 else x
  grid.roundrect(mm(cx), mm(y), mm(w), mm(h), r = unit(r, "mm"),
                 gp = gpar(fill = fill, col = col, lwd = lwd))
  if (!is.null(label)) {
    grid.text(label, mm(cx), mm(y), gp = gpar(fontsize = size, fontface = face, fontfamily = family,
                                              col = text_col, lineheight = lineheight))
  }
}

rect <- function(x, y, w, h, fill = "white", col = rule, lwd = 0.5) {
  grid.rect(mm(x), mm(y), mm(w), mm(h), just = c("left", "bottom"), gp = gpar(fill = fill, col = col, lwd = lwd))
}

label <- function(text, x, y, size = 7, face = "plain", family = sans, col = ink, hjust = 0.5, vjust = 0.5,
                  rot = 0, lineheight = 1.05) {
  grid.text(text, mm(x), mm(y), hjust = hjust, vjust = vjust, rot = rot,
            gp = gpar(fontsize = size, fontface = face, fontfamily = family, col = col, lineheight = lineheight))
}

arrow_line <- function(x, y, col = rule, lwd = 0.6, lty = "solid", head = TRUE, length = 1.4) {
  grid.lines(mm(x), mm(y), gp = gpar(col = col, lwd = lwd, lty = lty, fill = col),
             arrow = if (head) arrow(length = unit(length, "mm"), type = "closed", angle = 22) else NULL)
}

arrow_curve <- function(x1, y1, x2, y2, curvature = 0.3, col = rule, lwd = 0.6, lty = "solid") {
  grid.curve(mm(x1), mm(y1), mm(x2), mm(y2), curvature = curvature, square = FALSE, ncp = 8,
             gp = gpar(col = col, lwd = lwd, lty = lty, fill = col),
             arrow = arrow(length = unit(1.4, "mm"), type = "closed", angle = 22))
}

theme_paper <- function(size = 7.5) {
  theme_minimal(base_size = size, base_family = sans) +
    theme(
      text = element_text(colour = ink),
      axis.text = element_text(colour = ink, size = rel(0.92)),
      axis.title = element_text(size = rel(0.95)),
      panel.grid.minor = element_blank(),
      panel.grid.major = element_line(colour = "#e6e6e6", linewidth = 0.25),
      strip.text = element_text(face = "bold", hjust = 0, size = rel(0.95)),
      legend.position = "top",
      legend.title = element_blank(),
      legend.key.size = unit(3, "mm"),
      legend.margin = margin(0, 0, 0, 0),
      legend.box.spacing = unit(1, "mm"),
      plot.margin = margin(2, 4, 2, 2)
    )
}

engine_colours <- c(HStore = hs, HyperGraphDB = hg)

save_figure <- function(name, width, height, draw) {
  save_grid(file.path(Sys.getenv("PAPER_FIGURES", "figures"), paste0(name, ".pdf")), width, height, draw)
  if (nzchar(readme_images)) save_grid(file.path(readme_images, paste0(name, ".svg")), width, height, draw)
}

save_plot <- function(name, plot, width, height, readme = TRUE) {
  ggsave(file.path(Sys.getenv("PAPER_FIGURES", "figures"), paste0(name, ".pdf")), plot, device = cairo_pdf,
         width = width, height = height)
  if (readme && nzchar(readme_images)) ggsave(file.path(readme_images, paste0(name, ".svg")), plot, device = svg,
                                    width = width, height = height, bg = "white")
}
