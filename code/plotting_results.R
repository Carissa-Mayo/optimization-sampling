library(readr)
library(stringr)
library(dplyr)
library(ggplot2)
library(ggrepel)

sol_path  <- "/Users/carissamayo/Desktop/UW_work/SEFS 540/Project/final_solution.sol"
coords_csv <- "/Users/carissamayo/Desktop/UW_work/SEFS 540/Project/node_coords.csv"

# Read coords
coords <- read_csv(coords_csv, show_col_types = FALSE)

# Robust parser for CBC/HiGHS .sol formats: lines like "x_12 1" or "x_12 = 1"
sol_lines <- read_lines(sol_path)

# helper to extract (name, value) pairs with value > 0.5
grab_vars <- function(prefix) {
  pat <- paste0("^\\s*", prefix, "_([0-9]+)\\s*(=)?\\s*([-+]?[0-9]*\\.?[0-9]+)")
  m <- str_match(sol_lines, pat)
  m <- m[!is.na(m[,1]), , drop = FALSE]
  if (nrow(m) == 0) return(tibble(id = integer(), val = numeric()))
  tibble(id = as.integer(m[,2]), val = as.numeric(m[,4])) %>% filter(val > 0.5)
}

grab_arcs <- function() {
  # e_i_j
  pat <- "^\\s*e_([0-9]+)_([0-9]+)\\s*(=)?\\s*([-+]?[0-9]*\\.?[0-9]+)"
  m <- str_match(sol_lines, pat)
  m <- m[!is.na(m[,1]), , drop = FALSE]
  if (nrow(m) == 0) return(tibble(i = integer(), j = integer(), val = numeric()))
  tibble(i = as.integer(m[,2]), j = as.integer(m[,3]), val = as.numeric(m[,5])) %>%
    filter(val > 0.5)
}

ys <- grab_vars("y")
xs <- grab_vars("x")
starts <- grab_vars("start")  # in the cycle model we include start_v

es <- grab_arcs()

# Join to coordinates
sel_nodes <- coords %>% semi_join(ys, by = c("node_id" = "id"))
sample_nodes <- coords %>% semi_join(xs, by = c("node_id" = "id"))
start_nodes <- coords %>% semi_join(starts, by = c("node_id" = "id"))

arcs_xy <- es %>%
  inner_join(coords, by = c("i" = "node_id")) %>% rename(lon_i = lon, lat_i = lat) %>%
  inner_join(coords, by = c("j" = "node_id")) %>% rename(lon_j = lon, lat_j = lat)

# Base map as before
map_data <- map_data("state", region = "washington")

p_route <- ggplot() +
  geom_polygon(data = map_data, aes(x = long, y = lat, group = group),
               fill = "honeydew3", color = "honeydew3") +
  # draw arcs (route)
  geom_segment(data = arcs_xy,
               aes(x = lon_i, y = lat_i, xend = lon_j, yend = lat_j),
               arrow = arrow(length = unit(0.15, "cm")), alpha = 0.7) +
  # visited nodes
  geom_point(data = sel_nodes, aes(lon, lat), size = 2, color = "dodgerblue2") +
  # sampled nodes
  geom_point(data = sample_nodes, aes(lon, lat), size = 3, shape = 21, stroke = 1) +
  # start node (chosen)
  geom_point(data = start_nodes, aes(lon, lat), size = 3.5, color = "purple", shape = 8) +
  coord_fixed(1.3, xlim = c(-124, -122), ylim = c(47, 49)) +
  labs(title = "Optimized Closed Route (chosen start=end)",
       x = "Longitude", y = "Latitude") +
  theme_minimal()

print(p_route)
ggsave("/Users/carissamayo/Desktop/UW_work/SEFS 540/Project/optimized_cycle.png",
       plot = p_route, width = 10, height = 8, dpi = 300)

