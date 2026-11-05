package com.ipgo.server.service;

import java.util.List;

/** Reviewed, immutable scope. Never derive a new grant from the current product list or request. */
public final class InsightBatchManifest {
    private InsightBatchManifest() { }
    public static final String ID = "master-current-20261105";
    public static final long OWNER = 1;
    public static final int MAX_CALLS = 5;
    public record Target(long productId, long resultId) { }
    public static final List<Target> TARGETS = List.of(
            new Target(33, 812), new Target(34, 813), new Target(35, 814),
            new Target(36, 815), new Target(37, 816));

    static String marker(long resultId) { return "batch/" + ID + "/" + resultId + "/attempt-1"; }
}
