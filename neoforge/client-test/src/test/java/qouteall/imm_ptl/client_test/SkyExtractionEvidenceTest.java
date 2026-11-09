package qouteall.imm_ptl.client_test;

/** Diagnostic counters are dimension-addressed and cannot manufacture qualification evidence. */
public final class SkyExtractionEvidenceTest {
    public static void main(String[] args) {
        String realm = "test:realm";
        SkyExtractionEvidence.head(realm, false);
        SkyExtractionEvidence.returned(realm, false, 2, 1, 123);
        var early = SkyExtractionEvidence.snapshot(realm);
        require(early.get("heads").equals(1) && early.get("returns").equals(1), "Observe actual HEAD and RETURN separately");
        require(early.get("heads_before_expected_match").equals(1)
            && early.get("returns_before_expected_match").equals(1), "Keep extraction before expected-realm initialization visible");
        SkyExtractionEvidence.head(realm, true);
        SkyExtractionEvidence.returned(realm, true, 2, 1, 124);
        SkyExtractionEvidence.head("test:other", false);
        var latest = SkyExtractionEvidence.snapshot(realm);
        require(latest.get("heads").equals(2) && latest.get("returns").equals(2), "Other dimension must not contaminate realm counters");
        require(latest.get("last_suns").equals(2) && latest.get("last_moons").equals(1)
            && latest.get("last_cosmetic_seed").equals(124L), "Retain actual returned layout values");
        require(early.get("heads").equals(1), "First snapshot must not mutate when latest counters advance");
        require(SkyExtractionEvidence.snapshot("test:absent").get("heads").equals(0), "Absent observations are zero, not fabricated calls");
        require(Evidence.REALM_SKY_EXTRACTIONS.get() == 0 && Evidence.REALM_TWO_SUN_DRAWS.get() == 0,
            "Diagnostic calls must not increment original qualification counters");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private SkyExtractionEvidenceTest() {}
}
