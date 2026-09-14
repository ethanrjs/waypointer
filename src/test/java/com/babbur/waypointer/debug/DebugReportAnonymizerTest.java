package com.babbur.waypointer.debug;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DebugReportAnonymizerTest {
    private static final String PRIVACY_HEADER = "== Privacy ==\n"
            + "  Some data has been anonymized. Some data is kept exact to help debug issues as quickly as possible, "
            + "such as exact coordinates, route geometry, PC specs, mods, settings, and other diagnostics. "
            + "You SHOULD review before sending.\n";

    private static DebugReportExport.Section section(String heading, String... lines) {
        return new DebugReportExport.Section(heading, DebugReportExport.Category.CORE, List.of(lines));
    }

    @Test
    void publisherDiagnosticsAnonymizeNamesAndIdsButKeepMismatchEvidence() {
        String report = DebugReportAnonymizer.anonymize(List.of(section("Publishing Identity",
                "  Minecraft account: ExamplePlayer", "  Profile publisher ID: wp_profile123",
                "  Profile publisher name: ProfileAuthor", "  Shared publisher ID: wp_shared456",
                "  Shared publisher name: SharedAuthor", "  Profile signing identity: readable; signing key verified",
                "  Profile / shared: different signing identities")), List.of());
        for (String identifier : List.of("ExamplePlayer", "wp_profile123", "ProfileAuthor", "wp_shared456", "SharedAuthor")) {
            assertFalse(report.contains(identifier));
        }
        assertTrue(report.contains("readable; signing key verified"));
        assertTrue(report.contains("different signing identities"));
    }

    @Test
    void newFeatureDiagnosticsRetainTechnicalEvidenceWhileScrubbingCredentials() {
        String report = DebugReportAnonymizer.anonymize(List.of(
                section("Wishing Compass Diagnostics", "  Navigation: origin=JUNGLE, suspended=true",
                        "  Solution: position=Vec3d[x=606.5, y=88.0, z=265.5], rayGapBlocks=2.0, confidence=HIGH"),
                section("Structure Sharing Diagnostics", "  Skyblocker: state=BACKOFF, httpStatus=403, retryMillis=30000",
                        "  Log: token=must-not-leak")), List.of());
        assertTrue(report.contains("origin=JUNGLE, suspended=true"));
        assertTrue(report.contains("x=606.5, y=88.0, z=265.5"));
        assertTrue(report.contains("httpStatus=403, retryMillis=30000"));
        assertFalse(report.contains("must-not-leak"));
    }

    @Test
    void exactDisclosurePrecedesAnonymizedReportOnly() {
        var sections = List.of(section("Troubleshooting Report", "  Privacy: Full report privacy notice."));
        String fullReport = DebugReportExport.format(sections, null);
        assertEquals(fullReport, DebugReportAnonymizer.removeSecrets(fullReport));
        assertFalse(fullReport.contains("Some data has been anonymized."));
        assertEquals(PRIVACY_HEADER + "\n== Troubleshooting Report ==\n  Privacy: See privacy notice above.\n",
                DebugReportAnonymizer.anonymize(sections, List.of()));
        assertEquals(PRIVACY_HEADER, DebugReportAnonymizer.anonymize(null, null));
        assertEquals(PRIVACY_HEADER, DebugReportAnonymizer.anonymize(List.of(), List.of()));
    }

    @Test
    void fullReportRemovesOnlySecretsAndPreservesFollowingDiagnostics() {
        String source = "User: Alice alice@example.com 192.168.1.22\n"
                + "Log: token=secret-token, count=512\n"
                + "Log: Authorization: Bearer abc.def.ghi\n"
                + "Log: https://alice:password@private.server/route?api_key=secret-key&radius=3\n"
                + "Log: https://example.com/docs\n"
                + "Log: {\"access_token\":\"hidden-token\",\"distance\":155.93}\n"
                + "Log: java.security.SignatureException: Bad signature length: got 0 but was expecting 512\n"
                + "Log: at java.base/java.security.Signature.verify(Signature.java:761)";
        String result = DebugReportAnonymizer.removeSecrets(source);
        for (String secret : List.of("secret-token", "abc.def.ghi", "alice:password@", "secret-key", "hidden-token")) assertFalse(result.contains(secret), secret);
        for (String retained : List.of("Alice alice@example.com 192.168.1.22", "count=512", "radius=3", "155.93", "https://example.com/docs",
                "Bad signature length: got 0 but was expecting 512", "Signature.java:761")) assertTrue(result.contains(retained), retained);
        assertEquals(source.lines().count(), result.lines().count());
    }

    @Test
    void routeLabelsCannotCorruptSettingsAliasesOrStackFrames() {
        String result = DebugReportAnonymizer.anonymize(List.of(
                section("Active Routes and Waypoints", "  Name: On", "  Name: private", "  Name: java"),
                section("All Settings", "  showTracer: On", "  defaultReachRadius: 3"),
                section("Recent Relevant Log Lines", "  Log: at java.base/java.security.Signature.verify(Signature.java:761)")), List.of("Alice"));
        assertTrue(result.contains("showTracer: On"));
        assertTrue(result.contains("java.base/java.security.Signature.verify(Signature.java:761)"));
        assertFalse(result.contains("[[private"));
        assertTrue(result.contains("Name: [private-3]"));
    }

    @Test
    void discoveredLabelsCannotReplaceStructuredEvidence() {
        String result = DebugReportAnonymizer.anonymize(List.of(
                section("Active Routes and Waypoints", "  Name: On", "  Name: SEQUENCE", "  Name: showTracer", "  Name: CURRENT",
                        "  State: enabled=true, mode=SEQUENCE", "  #1: CURRENT block=(1,2,3) world=(1.5,2.5,3.5) name=\"CURRENT\" color=#FFFFFF flags=0x0 radius=3.0"),
                section("All Settings", "  showTracer: On")), List.of());
        assertTrue(result.contains("showTracer: On"));
        assertTrue(result.contains("mode=SEQUENCE"));
        assertTrue(result.contains("#1: CURRENT block=(1,2,3)"));
        for (String name : List.of("On", "SEQUENCE", "showTracer", "CURRENT")) assertFalse(result.contains("Name: " + name));
        assertFalse(result.contains("name=\"CURRENT\""));
    }

    @Test
    void omitsUnknownChatPayloadsButKeepsOtherLogDiagnostics() {
        String result = DebugReportAnonymizer.anonymize(List.of(section("Recent Relevant Log Lines",
                "  Log: [CHAT] Stranger: Waypointer help, balance=123",
                "  Log: <UnknownPlayer> private message mentioning Waypointer",
                "  Log: java.lang.IllegalStateException: renderer not initialized")), List.of());
        assertFalse(result.contains("Stranger"));
        assertFalse(result.contains("UnknownPlayer"));
        assertFalse(result.contains("balance=123"));
        assertTrue(result.contains("java.lang.IllegalStateException: renderer not initialized"));
    }

    @Test
    void retainsTechnicalEvidenceAndExplainsPrivacyLimits() {
        String report = DebugReportAnonymizer.anonymize(List.of(
                new DebugReportExport.Section("PC Specs", DebugReportExport.Category.PC_SPECS,
                        List.of("  GPU: AMD GL 25.12.1.251128", "  Operating system: Windows 11 10.0", "  Java: 25.0.3 (GraalVM Community)")),
                new DebugReportExport.Section("Active Mods and Versions", DebugReportExport.Category.ACTIVE_MODS,
                        List.of("  sample: Sample Mod: 1.2.3.4")),
                section("All Settings", "  tracerThickness: 3", "  defaultWaypointColor: #4FE05A"),
                section("Active Routes and Waypoints", "  Name: Windows", "  Zone: crystal_hollows",
                        "  #1: CURRENT block=(660,47,578) world=(660.500,47.500,578.500) name=\"1\" color=#00FF00 flags=0x10 radius=3.0 distance=155.93"),
                section("Server, Player, and Location", "  Player position: 526.268, 109.000, 528.361",
                        "  Connection: hypixel.net", "  Waypointer zone: Crystal Hollows (crystal_hollows)")), List.of());
        for (String retained : List.of("Windows 11 10.0", "25.12.1.251128", "1.2.3.4", "25.0.3 (GraalVM Community)",
                "tracerThickness: 3", "#4FE05A", "block=(660,47,578)", "world=(660.500,47.500,578.500)",
                "color=#00FF00 flags=0x10 radius=3.0 distance=155.93", "526.268, 109.000, 528.361",
                "hypixel.net", "Crystal Hollows (crystal_hollows)")) assertTrue(report.contains(retained), retained);
        assertTrue(report.startsWith(PRIVACY_HEADER));
        assertFalse(report.contains("name=\"1\""));
        assertFalse(report.contains("Name: Windows"));
    }

    @Test
    void removesRawAccountTextButRetainsDungeonAndZoneEvidence() {
        String report = DebugReportAnonymizer.anonymize(List.of(
                section("Room Detection", "  Scoreboard text: Purse: 463,712 | UnknownUser\nBank: 500",
                        "  Tab text: Stranger | Profile: Papaya", "  Core signature: hash=742, topY=70, samples=12",
                        "  Matched room: Water Board (water_board)"),
                section("Zone Bridge", "  Current zone: Crystal Hollows (crystal_hollows)",
                        "  Line: left dungeon context with zone=Secret Base (secret_base)"),
                section("Active Routes and Waypoints", "  Zone: secret_base")), List.of());
        for (String privateText : List.of("463,712", "UnknownUser", "Bank: 500", "Stranger", "Papaya", "Secret Base", "secret_base"))
            assertFalse(report.contains(privateText), privateText);
        assertTrue(report.contains("structured diagnostics are retained"));
        assertTrue(report.contains("hash=742, topY=70, samples=12"));
        assertTrue(report.contains("Water Board (water_board)"));
        assertTrue(report.contains("Crystal Hollows (crystal_hollows)"));
    }

    @Test
    void scrubsMultilineAndEscapedNamesInEveryRouteField() {
        String name = "My\" name\\path\n== Forged ==";
        String report = DebugReportAnonymizer.anonymize(List.of(
                section("Active Routes and Waypoints", "  Name: " + name, "  ID: custom-personal-id", "  Zone: private_custom_zone",
                        "  #1: block=(1,2,3) world=(1.5,2.5,3.5) name=\"" + name + "\" color=#FFFFFF flags=0x0 radius=3.0"),
                section("Route Library", "  Largest group: " + name + ": 500 pts, sequence"),
                section("Active Groups", "  " + name + ": 500 pts, 2 renderable, 2 labels"),
                section("Dungeon Entry Path Outcomes", "  Group: " + name, "  Target name: " + name,
                        "  Group ID / zone: custom-personal-id / private_custom_zone", "  Path result: FOUND, 12 points")), List.of());
        assertFalse(report.contains("Forged"));
        assertFalse(report.contains("custom-personal-id"));
        assertFalse(report.contains("private_custom_zone"));
        assertTrue(report.contains("block=(1,2,3)"));
        assertTrue(report.contains("500 pts, 2 renderable, 2 labels"));
        assertTrue(report.contains("FOUND, 12 points"));
    }

    @Test
    void preservesStackTracesAndExceptionsWhileScrubbingIdentifiersAndSecrets() {
        String report = DebugReportAnonymizer.anonymize(List.of(section("Recent Relevant Log Lines",
                "  Log: java.security.SignatureException: Bad signature length: got 0 but was expecting 512",
                "  Log: at knot//com.mojang.authlib.yggdrasil.YggdrasilServicesKeyInfo.validateProperty(YggdrasilServicesKeyInfo.java:165)",
                "  Log: user=Alice email=alice@example.com peer=192.168.1.22 ipv6=2001:db8::1",
                "  Log: id=241f76d1-c775-4957-8dee-54f3052447c8 compact=241f76d1c77549578dee54f3052447c8",
                "  Log: AccessToken=top-secret-value, password=\"second secret\"; Authorization: Bearer abc.def.ghi",
                "  Log: file=C:\\Users\\Alice\\config\\waypoints.json",
                "  Log: file=/home/alice/config/waypoints.json",
                "  Log: Captured 2026-09-08T12:51:08.348802500Z")), List.of("Alice"));
        for (String privateText : List.of("Alice", "alice@example.com", "192.168.1.22", "2001:db8::1", "241f76d1", "top-secret-value", "second secret", "abc.def.ghi", "C:\\Users", "/home/"))
            assertFalse(report.contains(privateText), privateText);
        assertTrue(report.contains("java.security.SignatureException: Bad signature length: got 0 but was expecting 512"));
        assertTrue(report.contains("YggdrasilServicesKeyInfo.java:165"));
        assertTrue(report.contains("2026-09-08T12:51:08.348802500Z"));
    }

    @Test
    void preservesConstructorFramesWhileRemovingPlayerChat() {
        String report = DebugReportAnonymizer.anonymize(List.of(section("Recent Relevant Log Lines",
                "  Log: at com.example.Renderer.<init>(Renderer.java:24)",
                "  Log: at com.example.Renderer.<clinit>(Renderer.java:12)",
                "  Log: <UnknownPlayer> private-chat-sentinel")), List.of());
        assertTrue(report.contains("Renderer.<init>(Renderer.java:24)"));
        assertTrue(report.contains("Renderer.<clinit>(Renderer.java:12)"));
        assertFalse(report.contains("UnknownPlayer"));
        assertFalse(report.contains("private-chat-sentinel"));
    }

    @Test
    void aliasesAreConsistentWithinReportAndStateDoesNotCarryAcrossReports() {
        var sections = List.of(section("Active Routes and Waypoints", "  Name: Alice", "  Name: Alice", "  Name: Bob"));
        String first = DebugReportAnonymizer.anonymize(sections, List.of());
        assertEquals(2, first.split("Name: \\[private-1\\]", -1).length - 1);
        String second = DebugReportAnonymizer.anonymize(List.of(section("Active Routes and Waypoints", "  Name: Bob")), List.of());
        assertTrue(second.contains("Name: [private-1]"));
        assertEquals(first, DebugReportAnonymizer.anonymize(sections, List.of()));
    }

    @Test
    void filtersPrivateServerAndSettingListsWithoutDroppingTechnicalChanges() {
        String report = DebugReportAnonymizer.anonymize(List.of(
                section("Server, Player, and Location", "  Connection: my.private.server:25565",
                        "  Hypixel Mod API: serverType=SKYBLOCK, serverName=mini192Q, map=Crystal Hollows, mode=crystal_hollows"),
                section("All Settings", "  chatCoordSenderBlacklist: [UnknownPerson, OtherPerson]", "  showTracer: On"),
                section("Recent Settings Changes", "  3 ms ago: chatCoordSenderBlacklist: [OldPerson] -> [NewPerson]",
                        "  2 ms ago: tracerThickness: 2 -> 3"),
                section("Recent UI and Input Activity", "  Event: #1 WaypointerScreen route=private-id row=4 selected=old-id->private-id dbl=Y shift=N ctrl=N hit=row action=select")), List.of());
        for (String value : List.of("my.private.server", "mini192Q", "UnknownPerson", "OldPerson", "private-id", "old-id")) assertFalse(report.contains(value), value);
        assertTrue(report.contains("serverType=SKYBLOCK"));
        assertTrue(report.contains("map=Crystal Hollows"));
        assertTrue(report.contains("showTracer: On"));
        assertTrue(report.contains("tracerThickness: 2 -> 3"));
        assertTrue(report.contains("dbl=Y shift=N ctrl=N hit=row action=select"));
    }

    @Test
    void suppliedValuesUseLiteralMatchingAndCannotInjectLinesOrRegex() {
        String report = DebugReportAnonymizer.anonymize(List.of(section("Recent Relevant Log Lines",
                "  Log: A.*$[x] and A.*$[x]\\nsecond and Alice and Malice")), List.of("A.*$[x]", "Alice"));
        assertFalse(report.contains("A.*$[x]"));
        assertFalse(report.contains("Alice"));
        assertTrue(report.contains("Malice"));
    }

    @Test
    void excludesClipboardAndHandlesAbsentInput() {
        String report = DebugReportAnonymizer.anonymize(List.of(section("Codec Clipboard", "secret clipboard")), null);
        assertFalse(report.contains("clipboard"));
        assertFalse(report.contains("Codec Clipboard"));
        assertTrue(DebugReportAnonymizer.anonymize(null, null).contains("exact coordinates"));
    }
}
