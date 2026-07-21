package com.babbur.waypointer.debug;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugReportExportTest {

    @Test
    void allCategoriesAreIncludedByDefault() {
        List<DebugReportExport.Section> sections = sampleSections();

        String report = DebugReportExport.format(sections, DebugReportExport.Options.allEnabled());

        assertTrue(report.contains("core-sentinel"));
        assertTrue(report.contains("pc-secret"));
        assertTrue(report.contains("mods-secret"));
        assertFalse(report.contains(DebugReportExport.REDACTED));
    }

    @Test
    void excludedCategoryIsReplacedWithoutLeakingItsBody() {
        DebugReportExport.Options options = new DebugReportExport.Options(EnumSet.of(
                DebugReportExport.Category.CORE,
                DebugReportExport.Category.ACTIVE_MODS));

        String report = DebugReportExport.format(sampleSections(), options);

        assertTrue(report.contains("== PC Specs ==\n  [User redacted]\n"));
        assertFalse(report.contains("pc-secret"));
        assertTrue(report.contains("mods-secret"));
        assertTrue(report.contains("core-sentinel"));
    }

    @Test
    void everyUserControlledCategoryStartsEnabled() {
        DebugReportExport.Options options = DebugReportExport.Options.allEnabled();

        for (DebugReportExport.Category category : DebugReportExport.Category.userControlledValues()) {
            assertTrue(options.includes(category), category.name());
        }
        assertEquals(6, DebugReportExport.Category.userControlledValues().size());
    }

    @Test
    void eachDisclosureCategoryCanBeRedactedIndependently() {
        List<DebugReportExport.Section> sections = new java.util.ArrayList<>();
        sections.add(new DebugReportExport.Section("Core", DebugReportExport.Category.CORE,
                List.of("  core-sentinel")));
        for (DebugReportExport.Category category : DebugReportExport.Category.userControlledValues()) {
            sections.add(new DebugReportExport.Section(category.label(), category,
                    List.of("  secret-" + category.name())));
        }

        for (DebugReportExport.Category excluded : DebugReportExport.Category.userControlledValues()) {
            EnumSet<DebugReportExport.Category> included = EnumSet.allOf(DebugReportExport.Category.class);
            included.remove(excluded);

            String report = DebugReportExport.format(sections, new DebugReportExport.Options(included));

            assertFalse(report.contains("secret-" + excluded.name()), excluded.name());
            assertTrue(report.contains("== " + excluded.label() + " ==\n  [User redacted]\n"),
                    excluded.name());
            assertTrue(report.contains("core-sentinel"));
            for (DebugReportExport.Category other : DebugReportExport.Category.userControlledValues()) {
                if (other != excluded) assertTrue(report.contains("secret-" + other.name()), other.name());
            }
        }
    }

    @Test
    void disclosureLabelsAreReadableAndDescriptionsExplainTheData() {
        List<String> labels = DebugReportExport.Category.userControlledValues().stream()
                .map(DebugReportExport.Category::label)
                .toList();

        assertEquals(List.of(
                "PC Specs",
                "Active Mods and Versions",
                "Server, Player, and Location",
                "Routes, Waypoints, and Coordinates",
                "Settings and Recent Changes",
                "Recent Logs and Activity"), labels);
        assertTrue(DebugReportExport.Category.userControlledValues().stream()
                .allMatch(category -> !category.description().isBlank()));
    }

    @Test
    void controlCharactersCannotInjectExtraReportLines() {
        String report = DebugReportExport.format(List.of(
                new DebugReportExport.Section("Core\n== Forged ==", DebugReportExport.Category.CORE,
                        List.of("  Value: safe\n== Forged body =="))),
                DebugReportExport.Options.allEnabled());

        assertFalse(report.contains("\n== Forged =="));
        assertFalse(report.contains("\n== Forged body =="));
        assertTrue(report.contains("Core == Forged =="));
        assertTrue(report.contains("Value: safe == Forged body =="));
    }

    private static List<DebugReportExport.Section> sampleSections() {
        return List.of(
                new DebugReportExport.Section("Core", DebugReportExport.Category.CORE,
                        List.of("  core-sentinel")),
                new DebugReportExport.Section("PC Specs", DebugReportExport.Category.PC_SPECS,
                        List.of("  pc-secret")),
                new DebugReportExport.Section("Mods", DebugReportExport.Category.ACTIVE_MODS,
                        List.of("  mods-secret")));
    }
}
