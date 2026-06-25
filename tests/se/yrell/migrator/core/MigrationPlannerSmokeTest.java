package se.yrell.migrator.core;

import java.util.Arrays;

import se.yrell.migrator.bmc.BmcTypeCatalog;
import se.yrell.migrator.bmc.BmcTypeGroup;
import se.yrell.migrator.config.CompareSettings;
import se.yrell.migrator.config.SettingsHealthReport;

/** Small dependency-free smoke test for the pure planning/reporting code. */
public final class MigrationPlannerSmokeTest {
    public static void main(String[] args) {
        CompareResult form = row("Form", "CHG:ChangeInterface", CompareStatus.CHANGED);
        form.setSourceFormType("Regular");
        CompareResult group = row("Group", "100000001", CompareStatus.MISSING_IN_TARGET);
        group.setSourceContextKey("100000001");
        CompareResult filter = row("Filter", "CHG:Validate", CompareStatus.CHANGED);

        MigrationPlanner planner = new MigrationPlanner();
        MigrationPlan plan = planner.buildPlan(Arrays.asList(filter, form, group), Arrays.asList(filter, form, group),
                MigrationDirection.SOURCE_TO_TARGET, 0);
        assertEquals("Group", plan.getSteps().get(0).getObjectType(), "groups must be planned before forms/workflow");
        assertEquals("Forms", plan.getSteps().get(1).getPhase(), "regular form should be in Forms phase");
        assertEquals("Workflow", plan.getSteps().get(2).getPhase(), "filter should be in Workflow phase");

        form.setDifferenceDetails(Arrays.asList(
                new DiffDetail("Permissions / 100000001", "permission", "View", "", "missing in target"),
                new DiffDetail("Audit", "auditType", "enabled", "disabled", "audit")));
        String summary = DiffSummaryAnalyzer.operatorSummary(form, 5);
        assertContains(summary, "Permissions check", "diff summary should include permission guidance");
        assertContains(summary, "Permission risk", "permission summary should classify risk");
        assertContains(summary, "Audit check", "diff summary should include audit guidance");
        assertContains(summary, "Audit enabled/disabled", "audit summary should classify enabled/disabled differences");

        CompareResult permissionOrder = row("Form", "ORDER:Test", CompareStatus.CHANGED);
        permissionOrder.setDifferenceDetails(Arrays.asList(
                new DiffDetail("Permissions", "permissionList", "100000001;200000002", "200000002;100000001", "changed")));
        PermissionDiffNormalizer.Summary normalized = PermissionDiffNormalizer.analyze(permissionOrder.getDifferenceDetails());
        assertEquals("true", String.valueOf(normalized.looksLikeFormattingOnly()), "same permission IDs in different order should be low-risk formatting");
        DiffRiskClassifier.Assessment lowRisk = DiffRiskClassifier.assess(permissionOrder);
        assertEquals("low", lowRisk.getLevel().getLabel(), "permission ordering should classify as low risk");
        DiffIgnoreAdvisor.IgnoreSuggestion ignore = DiffIgnoreAdvisor.suggest(
                new DiffDetail("Field 536870913", "definition.permissionList", "100000001", "", "changed"));
        assertEquals("permissionList", ignore.getShortToken(), "ignore advisor should simplify definition-prefixed properties");
        assertContains(ignore.getScopedToken(), "Field 536870913", "ignore advisor should keep a scoped variant");
        DiffIgnoreAdvisor.IgnoreSuggestion propertyLine = DiffIgnoreAdvisor.suggest(
                new DiffDetail("Field 536870913", "definition.permissionList", "100000001", "", "changed"));
        assertEquals("ignore.difference.name.contains=permissionList", propertyLine.getPropertyLine(),
                "ignore advisor should expose a copyable property-file line");
        assertContains(DiffIgnoreAdvisor.longHelp(new DiffDetail("Field 536870913", "definition.permissionList", "100000001", "", "changed")),
                "ignore.difference.name.contains=permissionList", "ignore advisor should produce property-file example");


        CompareResult fieldPermission = row("Form", "FIELD:Permission", CompareStatus.CHANGED);
        fieldPermission.setDifferenceDetails(Arrays.asList(
                new DiffDetail("Field 536870913", "definition.permissionList", "100000001", "", "missing in target")));
        PermissionDiffNormalizer.Summary fieldPermissionSummary = PermissionDiffNormalizer.analyze(fieldPermission.getDifferenceDetails());
        assertEquals("true", String.valueOf(fieldPermissionSummary.getSourceOnlyIds().contains("100000001")),
                "missing target permission should report source-only group id");
        assertEquals("false", String.valueOf(fieldPermissionSummary.getCommonIds().contains("536870913")),
                "field ids should not be treated as common group ids");
        DiffRiskClassifier.Assessment highPermissionRisk = DiffRiskClassifier.assess(fieldPermission);
        assertEquals("high", highPermissionRisk.getLevel().getLabel(), "missing permission IDs should classify as high risk");

        CompareResult auditMapping = row("Form", "AUDIT:Test", CompareStatus.CHANGED);
        auditMapping.setDifferenceDetails(Arrays.asList(
                new DiffDetail("Audit Settings", "audit field mapping", "Audit Field 536870914", "Audit Field 536870915", "changed"),
                new DiffDetail("Audit Settings", "audit form name", "AUD:Old", "AUD:New", "changed")));
        AuditDiffAnalyzer.Summary auditSummary = AuditDiffAnalyzer.analyze(auditMapping.getDifferenceDetails());
        assertEquals("2", String.valueOf(auditSummary.getAuditRows()), "audit analyzer should count audit rows");
        assertEquals("true", String.valueOf(auditSummary.getFieldMappingRows() > 0), "audit analyzer should classify field mapping rows");
        assertEquals("true", String.valueOf(auditSummary.getAuditFormRows() > 0), "audit analyzer should classify audit form references");
        assertContains(DiffSummaryAnalyzer.auditSummary(auditMapping), "Audit risk: high",
                "audit summary should mark form/mapping differences as high risk");
        assertContains(DiffSummaryAnalyzer.operatorSummary(auditMapping, 5), "Risk: high",
                "operator summary should include risk classification");

                CompareSettings settings = CompareSettings.load();
        String health = SettingsHealthReport.format(settings);
        assertContains(health, "Settings health:", "settings health report should be available in diagnostics/preferences");
        assertEquals("false", String.valueOf(settings.isSyncCacheBaseCustomization()), "base should not be cached by default");
        assertEquals("true", String.valueOf(settings.isSyncCacheCustomCustomization()), "custom should be cached by default");
        assertEquals("true", String.valueOf(settings.isSyncCacheOverlayCustomization()), "overlay should be cached by default");
        assertEquals("true", String.valueOf(settings.getCustomizationSyncDecision("Group", "Administrators", "").isIncluded()), "Groups with no customization type must stay in cache");
        assertEquals("false", String.valueOf(settings.getCustomizationSyncDecision("Form", "BMC:BaseForm", "base").isIncluded()), "base forms should be skipped by default");
        assertContains(health, "Base customization is not selected", "settings health should explain the default base-cache policy");

        boolean associationTypePresent = false;
        for (BmcTypeGroup groupDef : BmcTypeCatalog.getGroups()) {
            if ("Association".equals(groupDef.getLabel()) && !groupDef.getTypes().isEmpty()) {
                associationTypePresent = true;
                break;
            }
        }
        assertEquals("true", String.valueOf(associationTypePresent), "Developer Studio AssociationType should be exposed as its own selectable object type when available");

        String report = MigrationReportFormatter.formatObjectMigrationReport(MigrationDirection.SOURCE_TO_TARGET,
                Arrays.asList(MigrationResult.success(group, true, "created"), MigrationResult.stillDifferent(form, "still differs"), MigrationResult.failure(filter, "boom"), MigrationResult.cancelled(null, "cancelled by user")),
                1000L, 2500L, "smoke test");
        assertContains(report, "Succeeded: 2", "report should count warning successes");
        assertContains(report, "Still different after migration: 1", "report should count still-different warnings");
        assertContains(report, "Cancelled: 1", "report should count cancellations separately");
        assertContains(report, "Failed: 1", "report should count failures");
        assertContains(report, "Cause: Audit Settings", "report should include still-different cause summary");
        assertContains(report, "Risk: high", "report should include still-different risk context");
        assertContains(report, "Failures", "report should include failure section");
        System.out.println("OK: Migration planner/report formatter smoke test passed.");
    }

    private static CompareResult row(String type, String name, CompareStatus status) {
        CompareResult result = new CompareResult();
        result.setObjectType(type);
        result.setObjectName(name);
        result.setStatus(status);
        result.setSourceServer("source");
        result.setTargetServer("target");
        return result;
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertContains(String haystack, String needle, String message) {
        if (haystack == null || haystack.indexOf(needle) < 0) {
            throw new AssertionError(message + ": missing <" + needle + "> in <" + haystack + ">");
        }
    }
}
