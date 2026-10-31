package com.jenkinschina.atom.plot;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Glob;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns CSV series into one self-contained SVG chart.
 *
 * <p>The plot plugin draws JFreeChart images on the master. An atom only has files, and the useful output — a chart
 * you can look at in the build page — is just geometry. Writing SVG keeps it dependency-free, readable and
 * diff-friendly: a chart whose coordinates you can assert in a test is a chart you can trust.
 */
public class PlotAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String pattern = context.getParam("pattern", "**/*.csv");
        String title = context.getParam("title", "Plot");
        String xColumn = context.getParam("xColumn", "").trim();
        String yColumns = context.getParam("yColumns", "").trim();
        String outputFile = context.getParam("outputFile", "out/plot/plot.svg");
        int width = Math.max(240, Math.min(context.getIntParam("width", 720), 4000));
        int height = Math.max(180, Math.min(context.getIntParam("height", 420), 4000));
        String yLabel = context.getParam("yLabel", "").trim();
        boolean skipNonNumeric = context.getBooleanParam("skipNonNumeric", true);

        Path target = workspace.resolve(outputFile).normalize();
        if (!target.startsWith(workspace)) {
            return AtomResult.error(ErrorType.USER, 40001, "outputFile must stay inside the workspace");
        }

        List<Path> files = Glob.find(workspace, pattern);
        if (files.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40002, "no CSV matched " + pattern);
        }

        Map<String, List<Point>> series = new LinkedHashMap<>();
        int skipped = 0;
        for (Path file : files) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                continue;
            }
            String[] header = split(lines.get(0));
            int xIndex = indexOf(header, xColumn, 0);
            List<Integer> yIndexes = new ArrayList<>();
            if (yColumns.isBlank()) {
                for (int i = 0; i < header.length; i++) {
                    if (i != xIndex) {
                        yIndexes.add(i);
                    }
                }
            } else {
                for (String name : yColumns.split(",")) {
                    String trimmed = name.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    int index = indexOf(header, trimmed, -1);
                    if (index < 0) {
                        return AtomResult.error(ErrorType.USER, 40003,
                                "column '" + trimmed + "' is not in " + workspace.relativize(file)
                                        + "; the header has " + String.join(", ", header));
                    }
                    yIndexes.add(index);
                }
            }
            if (xIndex < 0 || yIndexes.isEmpty()) {
                return AtomResult.error(ErrorType.USER, 40004, "cannot tell which columns to plot in "
                        + workspace.relativize(file));
            }
            String prefix = files.size() > 1 ? workspace.relativize(file).getFileName() + " " : "";
            for (int row = 1; row < lines.size(); row++) {
                String[] cells = split(lines.get(row));
                if (cells.length <= xIndex) {
                    continue;
                }
                double x = number(cells[xIndex]);
                if (Double.isNaN(x)) {
                    if (!skipNonNumeric) {
                        return AtomResult.error(ErrorType.USER, 40005,
                                "row " + (row + 1) + " of " + workspace.relativize(file) + " has no number in column '"
                                        + header[xIndex] + "'");
                    }
                    skipped++;
                    continue;
                }
                for (int index : yIndexes) {
                    if (index >= cells.length) {
                        continue;
                    }
                    double y = number(cells[index]);
                    if (Double.isNaN(y)) {
                        if (!skipNonNumeric) {
                            return AtomResult.error(ErrorType.USER, 40006,
                                    "row " + (row + 1) + " of " + workspace.relativize(file)
                                            + " has no number in column '" + header[index] + "'");
                        }
                        skipped++;
                        continue;
                    }
                    String name = prefix + header[index];
                    series.computeIfAbsent(name, key -> new ArrayList<>()).add(new Point(x, y));
                }
            }
        }
        if (series.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40007, "nothing to plot: every row was skipped");
        }

        Files.createDirectories(target.getParent() == null ? workspace : target.getParent());
        String svg = render(title, yLabel, series, width, height);
        Files.writeString(target, svg, StandardCharsets.UTF_8);

        int points = series.values().stream().mapToInt(List::size).sum();
        double[] range = range(series);
        context.log(String.format(Locale.ROOT, "[plot] %d series, %d points, y %s..%s -> %s",
                series.size(), points, trim(range[0]), trim(range[1]), outputFile));

        return AtomResult.success()
                .data("series", String.valueOf(series.size()))
                .data("points", String.valueOf(points))
                .data("skipped", String.valueOf(skipped))
                .data("minY", trim(range[0]))
                .data("maxY", trim(range[1]))
                .report("report", title,
                        (target.getParent() == null ? workspace : target.getParent()).toString(),
                        target.getFileName().toString());
    }

    /** One SVG with axes, ticks and a polyline per series: no fonts, no scripts, no external references. */
    static String render(String title, String yLabel, Map<String, List<Point>> series, int width, int height) {
        double[] xRange = rangeFor(series, true);
        double[] yRange = rangeFor(series, false);
        double xMin = xRange[0];
        double xMax = xRange[1];
        double yMin = yRange[0];
        double yMax = yRange[1];
        if (xMax == xMin) {
            xMax = xMin + 1;
        }
        if (yMax == yMin) {
            yMax = yMin + 1;
        }
        int left = 64;
        int right = 16;
        int top = 44;
        int bottom = 48;
        int plotWidth = width - left - right;
        int plotHeight = height - top - bottom;

        StringBuilder svg = new StringBuilder();
        svg.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        svg.append(String.format(Locale.ROOT, "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" "
                + "viewBox=\"0 0 %d %d\" role=\"img\" aria-label=\"%s\">", width, height, width, height, escape(title)));
        svg.append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>");
        svg.append(String.format(Locale.ROOT, "<text x=\"%d\" y=\"26\" font-family=\"sans-serif\" font-size=\"16\" "
                + "fill=\"#1f2328\">%s</text>", left, escape(title)));

        // the plot frame
        svg.append(String.format(Locale.ROOT, "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"none\" "
                + "stroke=\"#d0d7de\"/>", left, top, plotWidth, plotHeight));

        // y ticks: five gridlines, labelled with the value they stand for
        for (int i = 0; i <= 4; i++) {
            double value = yMin + (yMax - yMin) * i / 4.0;
            int y = top + plotHeight - (int) Math.round(plotHeight * i / 4.0);
            svg.append(String.format(Locale.ROOT, "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#eef0f2\"/>",
                    left, y, left + plotWidth, y));
            svg.append(String.format(Locale.ROOT, "<text x=\"%d\" y=\"%d\" font-family=\"sans-serif\" font-size=\"11\" "
                    + "text-anchor=\"end\" fill=\"#57606a\">%s</text>", left - 8, y + 4, trim(value)));
        }

        // x ticks: only the ends, because CSV x values are rarely round
        svg.append(String.format(Locale.ROOT, "<text x=\"%d\" y=\"%d\" font-family=\"sans-serif\" font-size=\"11\" "
                + "text-anchor=\"middle\" fill=\"#57606a\">%s</text>", left, top + plotHeight + 18, trim(xMin)));
        svg.append(String.format(Locale.ROOT, "<text x=\"%d\" y=\"%d\" font-family=\"sans-serif\" font-size=\"11\" "
                + "text-anchor=\"middle\" fill=\"#57606a\">%s</text>", left + plotWidth, top + plotHeight + 18, trim(xMax)));
        if (!yLabel.isEmpty()) {
            svg.append(String.format(Locale.ROOT, "<text x=\"16\" y=\"%d\" font-family=\"sans-serif\" font-size=\"11\" "
                    + "fill=\"#57606a\" transform=\"rotate(-90 16 %d)\" text-anchor=\"middle\">%s</text>",
                    top + plotHeight / 2, top + plotHeight / 2, escape(yLabel)));
        }

        String[] colours = {"#1f6feb", "#d13438", "#1a7f37", "#8250df", "#bf8700", "#0a7ea4"};
        int index = 0;
        int legendY = top - 8;
        for (Map.Entry<String, List<Point>> entry : series.entrySet()) {
            String colour = colours[index % colours.length];
            StringBuilder points = new StringBuilder();
            for (Point point : entry.getValue()) {
                int px = left + (int) Math.round(plotWidth * (point.x() - xMin) / (xMax - xMin));
                int py = top + plotHeight - (int) Math.round(plotHeight * (point.y() - yMin) / (yMax - yMin));
                points.append(px).append(',').append(py).append(' ');
            }
            svg.append(String.format(Locale.ROOT, "<polyline fill=\"none\" stroke=\"%s\" stroke-width=\"2\" "
                    + "points=\"%s\"/>", colour, points.toString().trim()));
            // legend entries sit side by side under the title, in the order the series were read
            int legendX = left + index * 150;
            svg.append(String.format(Locale.ROOT, "<rect x=\"%d\" y=\"%d\" width=\"10\" height=\"10\" fill=\"%s\"/>",
                    legendX, legendY - 9, colour));
            svg.append(String.format(Locale.ROOT, "<text x=\"%d\" y=\"%d\" font-family=\"sans-serif\" font-size=\"11\" "
                    + "fill=\"#1f2328\">%s</text>", legendX + 15, legendY, escape(entry.getKey())));
            index++;
        }
        svg.append("</svg>");
        return svg.toString();
    }

    private static double[] rangeFor(Map<String, List<Point>> series, boolean xAxis) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (List<Point> points : series.values()) {
            for (Point point : points) {
                double value = xAxis ? point.x() : point.y();
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }
        return new double[] {min, max};
    }

    private static double[] range(Map<String, List<Point>> series) {
        return rangeFor(series, false);
    }

    private static int indexOf(String[] header, String name, int fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        if (name.matches("\\d+")) {
            int index = Integer.parseInt(name);
            return index >= 0 && index < header.length ? index : -1;
        }
        for (int i = 0; i < header.length; i++) {
            if (header[i].trim().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    private static String[] split(String line) {
        // commas or tabs, and no quoting games: these files are written by scripts, not by people
        String separator = line.contains("\t") && !line.contains(",") ? "\t" : ",";
        String[] cells = line.split(separator, -1);
        for (int i = 0; i < cells.length; i++) {
            cells[i] = cells[i].trim();
        }
        return cells;
    }

    private static double number(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static String trim(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private record Point(double x, double y) {}
}
