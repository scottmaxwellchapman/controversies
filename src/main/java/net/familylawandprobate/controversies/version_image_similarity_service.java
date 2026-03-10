package net.familylawandprobate.controversies;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compares document version image hashes for similarity and deduplication.
 */
public final class version_image_similarity_service {

    private static final class Holder {
        private static final version_image_similarity_service INSTANCE = new version_image_similarity_service();
    }

    public static final class SimilarityRec {
        public String matterUuid = "";
        public String matterLabel = "";
        public String documentUuid = "";
        public String documentTitle = "";
        public String partUuid = "";
        public String partLabel = "";
        public String versionUuid = "";
        public String versionLabel = "";
        public String source = "";
        public String mimeType = "";
        public String createdAt = "";
        public String createdBy = "";
        public int sourcePages = 0;
        public int candidatePages = 0;
        public int exactPageMatches = 0;
        public int nearPageMatches = 0;
        public int bestHammingDistance = 64;
        public int averageHammingDistance = 64;
        public int similarityPercent = 0;
        public boolean duplicateCandidate = false;
    }

    private static final class VersionTarget {
        String matterUuid = "";
        String matterLabel = "";
        String documentUuid = "";
        String documentTitle = "";
        String partUuid = "";
        String partLabel = "";
        part_versions.VersionRec version;
    }

    private static final class ComparisonStats {
        int sourcePages = 0;
        int candidatePages = 0;
        int exactPageMatches = 0;
        int nearPageMatches = 0;
        int bestHammingDistance = 64;
        int averageHammingDistance = 64;
        int similarityPercent = 0;
        boolean duplicateCandidate = false;
    }

    private final version_ocr_companion_service ocrService = version_ocr_companion_service.defaultService();

    private version_image_similarity_service() {
    }

    public static version_image_similarity_service defaultService() {
        return Holder.INSTANCE;
    }

    public ArrayList<SimilarityRec> findSimilarVersions(String tenantUuid,
                                                        String sourceMatterUuid,
                                                        String sourceDocumentUuid,
                                                        String sourcePartUuid,
                                                        part_versions.VersionRec sourceVersion,
                                                        String scope,
                                                        int maxResults,
                                                        int maxHammingDistance) throws Exception {
        String tu = safe(tenantUuid).trim();
        String mu = safe(sourceMatterUuid).trim();
        String du = safe(sourceDocumentUuid).trim();
        String pu = safe(sourcePartUuid).trim();
        String vu = safe(sourceVersion == null ? "" : sourceVersion.uuid).trim();
        if (tu.isBlank() || mu.isBlank() || du.isBlank() || pu.isBlank() || vu.isBlank()) {
            throw new IllegalArgumentException("Missing source identifiers.");
        }

        int maxOut = clamp(maxResults, 1, 200);
        int maxDist = clamp(maxHammingDistance, 0, 64);
        String normalizedScope = normalizeScope(scope);
        String matterFilter = "matter".equals(normalizedScope) ? mu : "";

        version_ocr_companion_service.CompanionRec sourceCompanion = ocrService.ensureCompanion(
                tu,
                mu,
                du,
                pu,
                sourceVersion
        );
        ArrayList<String> sourceHashes = differenceHashes(sourceCompanion);
        if (sourceHashes.isEmpty()) return new ArrayList<SimilarityRec>();

        ArrayList<VersionTarget> candidates = collectTargets(tu, matterFilter);
        ArrayList<SimilarityRec> out = new ArrayList<SimilarityRec>();

        for (VersionTarget candidate : candidates) {
            if (candidate == null || candidate.version == null) continue;
            String candidateVu = safe(candidate.version.uuid).trim();
            if (candidateVu.isBlank()) continue;
            if (vu.equals(candidateVu)) continue;

            version_ocr_companion_service.CompanionRec companion;
            try {
                companion = ocrService.ensureCompanion(
                        tu,
                        candidate.matterUuid,
                        candidate.documentUuid,
                        candidate.partUuid,
                        candidate.version
                );
            } catch (Exception ignored) {
                continue;
            }
            ArrayList<String> candidateHashes = differenceHashes(companion);
            if (candidateHashes.isEmpty()) continue;

            ComparisonStats stats = compare(sourceHashes, candidateHashes, maxDist);
            if (stats.nearPageMatches <= 0) continue;

            SimilarityRec row = new SimilarityRec();
            row.matterUuid = safe(candidate.matterUuid);
            row.matterLabel = safe(candidate.matterLabel);
            row.documentUuid = safe(candidate.documentUuid);
            row.documentTitle = safe(candidate.documentTitle);
            row.partUuid = safe(candidate.partUuid);
            row.partLabel = safe(candidate.partLabel);
            row.versionUuid = safe(candidate.version.uuid);
            row.versionLabel = safe(candidate.version.versionLabel);
            row.source = safe(candidate.version.source);
            row.mimeType = safe(candidate.version.mimeType);
            row.createdAt = safe(candidate.version.createdAt);
            row.createdBy = safe(candidate.version.createdBy);
            row.sourcePages = stats.sourcePages;
            row.candidatePages = stats.candidatePages;
            row.exactPageMatches = stats.exactPageMatches;
            row.nearPageMatches = stats.nearPageMatches;
            row.bestHammingDistance = stats.bestHammingDistance;
            row.averageHammingDistance = stats.averageHammingDistance;
            row.similarityPercent = stats.similarityPercent;
            row.duplicateCandidate = stats.duplicateCandidate;
            out.add(row);
        }

        out.sort(Comparator
                .comparing((SimilarityRec a) -> !a.duplicateCandidate)
                .thenComparing((SimilarityRec a) -> -a.exactPageMatches)
                .thenComparing((SimilarityRec a) -> -a.similarityPercent)
                .thenComparingInt(a -> a.bestHammingDistance)
                .thenComparing((SimilarityRec a) -> safe(a.createdAt), Comparator.reverseOrder()));

        if (out.size() > maxOut) {
            return new ArrayList<SimilarityRec>(out.subList(0, maxOut));
        }
        return out;
    }

    private static ComparisonStats compare(List<String> sourceHashes,
                                           List<String> candidateHashes,
                                           int maxHammingDistance) {
        ComparisonStats out = new ComparisonStats();
        ArrayList<String> source = normalizeHashList(sourceHashes);
        ArrayList<String> candidate = normalizeHashList(candidateHashes);
        out.sourcePages = source.size();
        out.candidatePages = candidate.size();
        if (source.isEmpty() || candidate.isEmpty()) return out;

        int nearDistanceTotal = 0;
        for (String sh : source) {
            int best = 64;
            for (String ch : candidate) {
                int d = image_hash_tools.hammingDistance64(sh, ch);
                if (d < best) best = d;
                if (best == 0) break;
            }
            if (best < out.bestHammingDistance) out.bestHammingDistance = best;
            if (best == 0) out.exactPageMatches++;
            if (best <= maxHammingDistance) {
                out.nearPageMatches++;
                nearDistanceTotal += best;
            }
        }

        if (out.nearPageMatches > 0) {
            out.averageHammingDistance = (int) Math.round((double) nearDistanceTotal / (double) out.nearPageMatches);
            int denom = Math.max(out.sourcePages, out.candidatePages);
            if (denom <= 0) denom = out.sourcePages;
            if (denom <= 0) denom = 1;
            out.similarityPercent = clamp((int) Math.round((100.0d * out.nearPageMatches) / denom), 0, 100);
        }
        out.duplicateCandidate = out.sourcePages == out.candidatePages && multisetEquivalent(source, candidate);
        return out;
    }

    private static boolean multisetEquivalent(List<String> a, List<String> b) {
        ArrayList<String> left = normalizeHashList(a);
        ArrayList<String> right = normalizeHashList(b);
        if (left.size() != right.size()) return false;
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (String v : left) {
            counts.put(v, counts.getOrDefault(v, 0) + 1);
        }
        for (String v : right) {
            Integer c = counts.get(v);
            if (c == null || c <= 0) return false;
            if (c == 1) counts.remove(v);
            else counts.put(v, c - 1);
        }
        return counts.isEmpty();
    }

    private static ArrayList<VersionTarget> collectTargets(String tenantUuid, String matterFilter) throws Exception {
        String tu = safe(tenantUuid).trim();
        String mf = safe(matterFilter).trim();
        ArrayList<VersionTarget> out = new ArrayList<VersionTarget>();
        if (tu.isBlank()) return out;

        matters matterStore = matters.defaultStore();
        documents docStore = documents.defaultStore();
        document_parts partStore = document_parts.defaultStore();
        part_versions versionStore = part_versions.defaultStore();

        List<matters.MatterRec> matterRows = matterStore.listAll(tu);
        for (matters.MatterRec matter : matterRows) {
            if (matter == null || matter.trashed || !matter.enabled) continue;
            String matterUuid = safe(matter.uuid).trim();
            if (matterUuid.isBlank()) continue;
            if (!mf.isBlank() && !mf.equals(matterUuid)) continue;

            List<documents.DocumentRec> docRows = docStore.listAll(tu, matterUuid);
            for (documents.DocumentRec doc : docRows) {
                if (doc == null || doc.trashed) continue;
                String docUuid = safe(doc.uuid).trim();
                if (docUuid.isBlank()) continue;

                List<document_parts.PartRec> partRows = partStore.listAll(tu, matterUuid, docUuid);
                for (document_parts.PartRec part : partRows) {
                    if (part == null || part.trashed) continue;
                    String partUuid = safe(part.uuid).trim();
                    if (partUuid.isBlank()) continue;

                    List<part_versions.VersionRec> versionRows = versionStore.listAll(tu, matterUuid, docUuid, partUuid);
                    for (part_versions.VersionRec version : versionRows) {
                        if (version == null) continue;
                        VersionTarget row = new VersionTarget();
                        row.matterUuid = matterUuid;
                        row.matterLabel = safe(matter.label);
                        row.documentUuid = docUuid;
                        row.documentTitle = safe(doc.title);
                        row.partUuid = partUuid;
                        row.partLabel = safe(part.label);
                        row.version = version;
                        out.add(row);
                    }
                }
            }
        }
        return out;
    }

    private static ArrayList<String> differenceHashes(version_ocr_companion_service.CompanionRec companion) {
        ArrayList<String> out = new ArrayList<String>();
        if (companion == null || companion.imageHashes == null) return out;
        for (version_ocr_companion_service.ImageHashRec row : companion.imageHashes) {
            if (row == null) continue;
            String d = image_hash_tools.normalizeHex64(row.differenceHash64);
            if (!d.isBlank()) out.add(d);
        }
        return out;
    }

    private static ArrayList<String> normalizeHashList(List<String> hashes) {
        ArrayList<String> out = new ArrayList<String>();
        if (hashes == null) return out;
        for (String raw : hashes) {
            String v = image_hash_tools.normalizeHex64(raw);
            if (!v.isBlank()) out.add(v);
        }
        return out;
    }

    private static String normalizeScope(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if ("tenant".equals(v)) return "tenant";
        return "matter";
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
