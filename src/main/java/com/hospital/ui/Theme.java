package com.hospital.ui;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Application stylesheet. Kept as a Java text block (served as a data: URI) so the
 * project contains only Java sources.
 */
public final class Theme {

    private Theme() {}

    private static final String CSS = """
            .root {
                -fx-font-family: "Segoe UI", "Helvetica Neue", Arial, sans-serif;
                -fx-font-size: 13px;
                -fx-background-color: #f1f5f9;
                -fx-accent: #2563eb;
                -fx-focus-color: #2563eb;
                -fx-faint-focus-color: #2563eb22;
            }
            .sidebar { -fx-background-color: #0f2a44; -fx-padding: 20 12 16 12; -fx-spacing: 4; }
            .brand-title { -fx-text-fill: white; -fx-font-size: 17px; -fx-font-weight: bold; }
            .brand-sub { -fx-text-fill: #8fb3d9; -fx-font-size: 11px; }
            .nav-button {
                -fx-background-color: transparent; -fx-text-fill: #c9d8e8; -fx-alignment: center-left;
                -fx-padding: 10 14; -fx-background-radius: 8; -fx-font-size: 13.5px; -fx-cursor: hand;
            }
            .nav-button:hover { -fx-background-color: #1b3d61; -fx-text-fill: white; }
            .nav-button.active { -fx-background-color: #2563eb; -fx-text-fill: white; -fx-font-weight: bold; }
            .sidebar-footer { -fx-text-fill: #8fb3d9; -fx-font-size: 11px; }

            .topbar {
                -fx-background-color: white; -fx-padding: 12 22;
                -fx-border-color: transparent transparent #e2e8f0 transparent;
            }
            .page-title { -fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #0f172a; }
            .clock { -fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #0f172a; }
            .muted { -fx-text-fill: #64748b; }
            .small { -fx-font-size: 11px; }
            .bold { -fx-font-weight: bold; }

            .card {
                -fx-background-color: white; -fx-background-radius: 12; -fx-border-color: #e2e8f0;
                -fx-border-radius: 12; -fx-padding: 16; -fx-spacing: 10;
                -fx-effect: dropshadow(gaussian, rgba(15,23,42,0.06), 10, 0, 0, 2);
            }
            .card-title { -fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #0f172a; }
            .kpi-value { -fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: #0f172a; }
            .kpi-label { -fx-text-fill: #64748b; -fx-font-size: 12px; }
            .section-title { -fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #0f172a; }

            .button { -fx-background-radius: 8; -fx-padding: 8 14; -fx-cursor: hand; }
            .btn-primary { -fx-background-color: #2563eb; -fx-text-fill: white; -fx-font-weight: bold; }
            .btn-primary:hover { -fx-background-color: #1d4ed8; }
            .btn-danger { -fx-background-color: #dc2626; -fx-text-fill: white; -fx-font-weight: bold; }
            .btn-danger:hover { -fx-background-color: #b91c1c; }
            .btn-success { -fx-background-color: #16a34a; -fx-text-fill: white; -fx-font-weight: bold; }
            .btn-success:hover { -fx-background-color: #15803d; }
            .btn-warning { -fx-background-color: #f59e0b; -fx-text-fill: #1f2937; -fx-font-weight: bold; }
            .btn-secondary { -fx-background-color: #e2e8f0; -fx-text-fill: #0f172a; }
            .btn-secondary:hover { -fx-background-color: #cbd5e1; }
            .btn-small { -fx-padding: 4 10; -fx-font-size: 11.5px; }

            .esi-badge {
                -fx-background-radius: 999; -fx-padding: 2 10; -fx-text-fill: white;
                -fx-font-weight: bold; -fx-font-size: 11px;
            }
            .esi-badge-large { -fx-font-size: 20px; -fx-padding: 6 18; }
            .alert-badge {
                -fx-background-color: #dc2626; -fx-text-fill: white; -fx-background-radius: 999;
                -fx-padding: 1 7; -fx-font-size: 11px; -fx-font-weight: bold;
            }
            .pill { -fx-background-radius: 999; -fx-padding: 2 9; -fx-font-size: 11px; -fx-font-weight: bold; }
            .pill-red { -fx-background-color: #fee2e2; -fx-text-fill: #b91c1c; }
            .pill-green { -fx-background-color: #dcfce7; -fx-text-fill: #15803d; }
            .pill-amber { -fx-background-color: #fef3c7; -fx-text-fill: #b45309; }
            .pill-grey { -fx-background-color: #e2e8f0; -fx-text-fill: #334155; }
            .pill-blue { -fx-background-color: #dbeafe; -fx-text-fill: #1d4ed8; }

            .status-bar {
                -fx-background-color: white; -fx-padding: 7 22;
                -fx-border-color: #e2e8f0 transparent transparent transparent;
            }
            .status-ok { -fx-text-fill: #15803d; }
            .status-error { -fx-text-fill: #b91c1c; }

            .table-view { -fx-background-radius: 10; -fx-border-radius: 10; -fx-border-color: #e2e8f0; }
            .table-view .column-header, .table-view .filler { -fx-background-color: #f8fafc; }
            .table-view .column-header .label { -fx-font-weight: bold; -fx-text-fill: #334155; }
            .table-row-cell.overdue { -fx-background-color: #fff1f2; }
            .table-row-cell.overdue:selected { -fx-background-color: #2563eb; }

            .bed-tile {
                -fx-background-radius: 10; -fx-border-radius: 10; -fx-padding: 10; -fx-spacing: 4;
                -fx-pref-width: 185; -fx-min-height: 128; -fx-border-width: 2;
            }
            .bed-available { -fx-background-color: #f0fdf4; -fx-border-color: #22c55e; }
            .bed-occupied { -fx-background-color: #fef2f2; -fx-border-color: #ef4444; }
            .bed-cleaning { -fx-background-color: #fffbeb; -fx-border-color: #f59e0b; }
            .bed-maintenance { -fx-background-color: #f1f5f9; -fx-border-color: #94a3b8; }
            .bed-id { -fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #0f172a; }

            .toast {
                -fx-background-radius: 10; -fx-padding: 12 16; -fx-spacing: 2;
                -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.25), 14, 0, 0, 4);
            }
            .toast-title { -fx-text-fill: white; -fx-font-weight: bold; }
            .toast-text { -fx-text-fill: white; }

            .form-label { -fx-text-fill: #334155; -fx-font-weight: bold; }
            .hint { -fx-text-fill: #64748b; -fx-font-size: 11px; }
            .error-text { -fx-text-fill: #b91c1c; }
            .preview-box { -fx-background-radius: 12; -fx-padding: 16; -fx-spacing: 8; -fx-border-radius: 12; -fx-border-width: 2; }

            .alert-row { -fx-background-color: white; -fx-background-radius: 8; -fx-padding: 10 12; -fx-spacing: 10; }
            .alert-row-acked { -fx-opacity: 0.55; }
            .list-view { -fx-background-color: transparent; -fx-border-color: transparent; }
            .list-view .list-cell { -fx-background-color: transparent; -fx-padding: 3 0; }

            /* -fx-background must stay light: JavaFX derives the default text colour from it */
            .scroll-pane { -fx-background-color: transparent; -fx-background: #f1f5f9; }
            .scroll-pane > .viewport { -fx-background-color: transparent; }
            .progress-bar > .bar { -fx-background-radius: 6; -fx-background-insets: 0; }
            .progress-bar > .track { -fx-background-radius: 6; -fx-background-color: #e2e8f0; }
            .bar-red .bar { -fx-background-color: #ef4444; }
            .bar-amber .bar { -fx-background-color: #f59e0b; }
            .bar-green .bar { -fx-background-color: #22c55e; }
            .chart-title { -fx-font-size: 14px; -fx-font-weight: bold; }
            .chart-plot-background { -fx-background-color: white; }
            .default-color0.chart-bar { -fx-bar-fill: #2563eb; }
            .default-color1.chart-bar { -fx-bar-fill: #cbd5e1; }
            .default-color0.chart-legend-item-symbol { -fx-background-color: #2563eb; }
            .default-color1.chart-legend-item-symbol { -fx-background-color: #cbd5e1; }
            .chart-legend { -fx-background-color: transparent; }
            """;

    /** Overrides applied on top of {@link #stylesheet()} when dark mode is on. STUB - to be implemented. */
    private static final String DARK_CSS = """
            """;

    public static String darkStylesheet() {
        return "data:text/css;base64," + Base64.getEncoder().encodeToString(DARK_CSS.getBytes(StandardCharsets.UTF_8));
    }

    public static String stylesheet() {
        return "data:text/css;base64," + Base64.getEncoder().encodeToString(CSS.getBytes(StandardCharsets.UTF_8));
    }
}
